"""Tests for the scored look-ahead (ac_windows/forecast.py)."""

import unittest

from ac_windows.config import Comfort
from ac_windows.forecast import build_forecast, fmt_hour


def _h(time, temp, dew=50, aqi=20, pop=0, mm=None, sw=0):
    return {"time": time, "temp_f": temp, "dew_point_f": dew,
            "aqi": aqi, "precip_prob": pop, "precip_mm": mm, "shortwave_wm2": sw, "cloud_pct": 0}


def _rain(time, pop=None, mm=None):
    return {"time": time, "precip_prob": pop, "precip_mm": mm}


# A warm afternoon cooling into a comfortable evening.
WARM_THEN_COOL = [
    _h("2026-06-15T16:00", 80), _h("2026-06-15T17:00", 78),
    _h("2026-06-15T18:00", 75), _h("2026-06-15T19:00", 72),
    _h("2026-06-15T20:00", 70), _h("2026-06-15T21:00", 69),
]

# The real disagreement this exists for: Open-Meteo said 32% / 0.0mm for an hour
# NWS called 72% and met.no put real millimetres in.
ONE_WET_HOUR = [_h("2026-06-15T09:00", 70, pop=32, mm=0.0)]


class TestBuildForecast(unittest.TestCase):
    def setUp(self):
        self.c = Comfort()

    def test_scores_track_temperature(self):
        fc = build_forecast(WARM_THEN_COOL, [], self.c)
        self.assertEqual(len(fc.points), 6)
        self.assertEqual(fc.points[0].score, 0.0)      # 80°F, can't cool
        self.assertEqual(fc.points[-1].score, 100.0)   # 69°F, dry, clean

    def test_best_window_is_the_cool_stretch(self):
        fc = build_forecast(WARM_THEN_COOL, [], self.c)
        self.assertEqual(fc.best_start, "2026-06-15T18:00")
        self.assertEqual(fc.best_end, "2026-06-15T21:00")
        self.assertGreaterEqual(fc.best_avg_score, self.c.open_score_min)

    def test_no_window_when_always_warm(self):
        warm = [_h(f"2026-06-15T{h:02d}:00", 85) for h in range(16, 22)]
        fc = build_forecast(warm, [], self.c)
        self.assertIsNone(fc.best_start)
        self.assertTrue(any("won't beat the AC" in n for n in fc.notes))

    def test_no_window_when_always_cold(self):
        cold = [_h(f"2026-06-15T{h:02d}:00", 30, dew=20) for h in range(6)]
        fc = build_forecast(cold, [], self.c)
        self.assertIsNone(fc.best_start)
        self.assertTrue(any("Too cold" in n for n in fc.notes))
        self.assertFalse(any("won't beat the AC" in n for n in fc.notes))

    def test_temp_agreement_agree(self):
        nws = [{"time": p["time"], "temp_f": p["temp_f"] + 1} for p in WARM_THEN_COOL]
        fc = build_forecast(WARM_THEN_COOL, nws, self.c)
        self.assertEqual(fc.temp_agreement, "agree")
        self.assertLessEqual(fc.temp_spread_f, 2)

    def test_temp_agreement_conflict(self):
        nws = [{"time": p["time"], "temp_f": p["temp_f"] + 15} for p in WARM_THEN_COOL]
        fc = build_forecast(WARM_THEN_COOL, nws, self.c)
        self.assertEqual(fc.temp_agreement, "conflict")

    def test_temp_agreement_degraded_without_nws(self):
        fc = build_forecast(WARM_THEN_COOL, [], self.c)
        self.assertEqual(fc.temp_agreement, "degraded")

    def test_empty_forecast(self):
        fc = build_forecast([], [], self.c)
        self.assertEqual(fc.points, [])
        self.assertIsNone(fc.best_start)

    def test_open_now_note_when_currently_good(self):
        fc = build_forecast(list(reversed(WARM_THEN_COOL)), [], self.c)  # cool now -> warm
        self.assertTrue(any("Good to open now" in n for n in fc.notes))


class TestRainConsensus(unittest.TestCase):
    def setUp(self):
        self.c = Comfort()

    def test_rain_chance_averages_two_sources(self):
        fc = build_forecast(ONE_WET_HOUR, [_rain("2026-06-15T09:00", pop=72)], self.c)
        self.assertAlmostEqual(fc.points[0].precip_prob, 52.0, places=3)

    def test_rain_amount_averages_two_sources(self):
        fc = build_forecast(
            ONE_WET_HOUR, [], self.c,
            extra_hourly=[[_rain("2026-06-15T09:00", mm=0.7)]],
        )
        self.assertAlmostEqual(fc.points[0].precip_mm, 0.35, places=3)

    def test_rain_takes_median_of_three_sources(self):
        fc = build_forecast(
            ONE_WET_HOUR, [_rain("2026-06-15T09:00", pop=72)], self.c,
            extra_hourly=[[_rain("2026-06-15T09:00", pop=50)]],
        )
        self.assertAlmostEqual(fc.points[0].precip_prob, 50.0, places=3)

    def test_a_source_missing_the_hour_does_not_vote(self):
        fc = build_forecast(ONE_WET_HOUR, [_rain("2026-06-15T10:00", pop=72)], self.c)
        self.assertAlmostEqual(fc.points[0].precip_prob, 32.0, places=3)

    def test_a_null_field_does_not_vote(self):
        # met.no supplies an amount but never a chance outside the Nordics — its
        # null must not drag the consensus toward zero.
        fc = build_forecast(
            ONE_WET_HOUR, [_rain("2026-06-15T09:00", pop=72)], self.c,
            extra_hourly=[[_rain("2026-06-15T09:00", mm=0.7)]],
        )
        self.assertAlmostEqual(fc.points[0].precip_prob, 52.0, places=3)

    def test_rain_stays_null_when_no_source_has_it(self):
        h = dict(_h("2026-06-15T09:00", 70))
        del h["precip_prob"]
        fc = build_forecast([h], [], self.c)
        self.assertIsNone(fc.points[0].precip_prob)

    def test_consensus_chance_drives_the_score(self):
        alone = build_forecast(ONE_WET_HOUR, [], self.c).points[0].score
        merged = build_forecast(
            ONE_WET_HOUR, [_rain("2026-06-15T09:00", pop=72)], self.c,
        ).points[0].score
        self.assertLess(merged, alone)


class TestFmtHour(unittest.TestCase):
    def test_formats(self):
        self.assertEqual(fmt_hour("2026-06-15T21:00"), "9 PM")
        self.assertEqual(fmt_hour("2026-06-15T00:00"), "12 AM")
        self.assertEqual(fmt_hour("2026-06-15T08:00"), "8 AM")
        self.assertEqual(fmt_hour("2026-06-15T12:00"), "12 PM")


if __name__ == "__main__":
    unittest.main()
