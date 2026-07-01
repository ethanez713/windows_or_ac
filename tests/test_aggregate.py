"""Unit tests for cross-source aggregation. No network."""

import unittest

from ac_windows.aggregate import AGREEMENT_TOLERANCE_F, aggregate
from ac_windows.models import WeatherReading


class AggregateTests(unittest.TestCase):
    def test_two_agreeing_sources_average_and_high_confidence(self):
        a = WeatherReading(source="a", temp_f=70.0, dew_point_f=50.0)
        b = WeatherReading(source="b", temp_f=72.0, dew_point_f=52.0)
        agg = aggregate([a, b])
        self.assertAlmostEqual(agg.temp_f, 71.0)
        self.assertAlmostEqual(agg.dew_point_f, 51.0)
        self.assertEqual(agg.confidence, "high")
        self.assertEqual(set(agg.sources_ok), {"a", "b"})

    def test_one_failed_source_is_degraded_but_usable(self):
        good = WeatherReading(source="a", temp_f=70.0)
        bad = WeatherReading(source="b", ok=False, error="boom")
        agg = aggregate([good, bad])
        self.assertEqual(agg.temp_f, 70.0)
        self.assertEqual(agg.confidence, "degraded")
        self.assertEqual(agg.sources_failed, [("b", "boom")])

    def test_large_disagreement_flags_conflict(self):
        a = WeatherReading(source="a", temp_f=60.0)
        b = WeatherReading(source="b", temp_f=60.0 + AGREEMENT_TOLERANCE_F + 1)
        self.assertEqual(aggregate([a, b]).confidence, "conflict")

    def test_aqi_taken_from_whichever_source_has_it(self):
        a = WeatherReading(source="a", temp_f=70.0, aqi=42)
        b = WeatherReading(source="b", temp_f=70.0)  # no AQI (e.g. NWS)
        self.assertEqual(aggregate([a, b]).aqi, 42)

    def test_all_failed_is_none_confidence(self):
        agg = aggregate([WeatherReading(source="a", ok=False, error="x")])
        self.assertEqual(agg.confidence, "none")
        self.assertIsNone(agg.temp_f)


if __name__ == "__main__":
    unittest.main()
