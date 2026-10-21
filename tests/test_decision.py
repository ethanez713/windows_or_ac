"""Unit tests for the pure decision engine. No network."""

import unittest

from ac_windows.config import Comfort
from ac_windows.decision import aqi_category, decide
from ac_windows.models import AggregateReading


def reading(**kw) -> AggregateReading:
    """Build a consensus reading with sensible, non-blocking defaults."""
    base = dict(
        temp_f=70.0,
        dew_point_f=50.0,
        humidity_pct=45.0,
        apparent_temp_f=70.0,
        precip_mm=0.0,
        wind_mph=4.0,
        aqi=20,
        confidence="high",
    )
    base.update(kw)
    return AggregateReading(**base)  # type: ignore[arg-type]


C = Comfort()  # defaults: max 78°F, dew_point_max 60, aqi_max 100, margin 2


class DecisionTests(unittest.TestCase):
    def test_cool_clean_dry_opens_windows(self):
        self.assertEqual(decide(reading(temp_f=70), C).action, "OPEN_WINDOWS")

    def test_hot_runs_ac(self):
        self.assertEqual(decide(reading(temp_f=85), C).action, "RUN_AC")

    def test_bad_aqi_when_cool_keeps_closed(self):
        # Cool enough to ventilate, but smoky air is a hard stop.
        self.assertEqual(decide(reading(temp_f=70, aqi=160), C).action, "KEEP_CLOSED")

    def test_bad_aqi_when_hot_runs_ac(self):
        rec = decide(reading(temp_f=88, aqi=160), C)
        self.assertEqual(rec.action, "RUN_AC")

    def test_muggy_when_cool_prefers_ac_to_dehumidify(self):
        # Pleasant temperature but high dew point -> AC wins to dry the air.
        self.assertEqual(decide(reading(temp_f=72, dew_point_f=68), C).action, "RUN_AC")

    def test_rain_when_cool_keeps_closed(self):
        self.assertEqual(decide(reading(temp_f=68, precip_mm=0.5), C).action, "KEEP_CLOSED")

    def test_near_setpoint_is_comfortable(self):
        # Within the margin band (76..78): not "cool enough", but nothing blocks.
        self.assertEqual(decide(reading(temp_f=77), C).action, "COMFORTABLE")

    def test_missing_temp_is_unknown(self):
        self.assertEqual(decide(reading(temp_f=None), C).action, "UNKNOWN")

    def test_custom_setpoint_respected(self):
        warm = Comfort(max_temp_f=72)
        self.assertEqual(decide(reading(temp_f=74), warm).action, "RUN_AC")

    def test_missing_aqi_does_not_block_opening(self):
        # If no source reports AQI, absence shouldn't be treated as "bad".
        self.assertEqual(decide(reading(temp_f=68, aqi=None), C).action, "OPEN_WINDOWS")

    def test_reasons_are_populated(self):
        self.assertTrue(decide(reading(temp_f=85), C).reasons)

    def test_freezing_keeps_closed_rather_than_heating_by_window(self):
        rec = decide(reading(temp_f=32, dew_point_f=20), C)
        self.assertEqual(rec.action, "KEEP_CLOSED")
        self.assertIn("won't warm", rec.summary)

    def test_cold_and_smoky_does_not_run_ac(self):
        rec = decide(reading(temp_f=32, aqi=160), C)
        self.assertEqual(rec.action, "KEEP_CLOSED")
        self.assertNotIn("AC", rec.headline)

    def test_fifty_degrees_still_opens(self):
        # Inside the cold ramp but the score (about 67) still clears 60.
        self.assertEqual(decide(reading(temp_f=50, dew_point_f=50), C).action, "OPEN_WINDOWS")

    def test_mid_ramp_cold_keeps_closed(self):
        self.assertEqual(decide(reading(temp_f=45, dew_point_f=35), C).action, "KEEP_CLOSED")

    def test_very_dry_air_keeps_closed(self):
        rec = decide(reading(temp_f=70, dew_point_f=30), C)
        self.assertEqual(rec.action, "KEEP_CLOSED")
        self.assertIn("very dry", rec.summary)

    def test_smoky_cool_evening_is_not_the_cold_message(self):
        rec = decide(reading(temp_f=50, aqi=160), C)
        self.assertEqual(rec.action, "KEEP_CLOSED")
        self.assertNotIn("won't warm", rec.summary)


class AqiCategoryTests(unittest.TestCase):
    def test_boundaries(self):
        self.assertEqual(aqi_category(50), "good")
        self.assertEqual(aqi_category(100), "moderate")
        self.assertEqual(aqi_category(150), "unhealthy for sensitive groups")
        self.assertEqual(aqi_category(None), "unknown")


if __name__ == "__main__":
    unittest.main()
