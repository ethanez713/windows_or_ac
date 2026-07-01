"""Tests for the Open-Window Score (ac_windows/score.py)."""

import unittest

from ac_windows.config import Comfort
from ac_windows.score import _ramp_down, window_score


class TestRamp(unittest.TestCase):
    def test_ramp_endpoints_and_middle(self):
        self.assertEqual(_ramp_down(50, 55, 60), 1.0)   # at/below "full"
        self.assertEqual(_ramp_down(60, 55, 60), 0.0)   # at "zero"
        self.assertAlmostEqual(_ramp_down(57.5, 55, 60), 0.5)
        self.assertEqual(_ramp_down(70, 55, 60), 0.0)   # past zero, clamped
        self.assertEqual(_ramp_down(None, 55, 60), 1.0)  # unknown = non-blocking


class TestWindowScore(unittest.TestCase):
    def setUp(self):
        self.c = Comfort()  # max 78, margin 2, dew 55/60, aqi 100, solar 700/0.15

    def test_cool_dry_clean_is_perfect(self):
        s = window_score(self.c, temp_f=70, dew_point_f=50, aqi=20)
        self.assertEqual(s.score, 100.0)

    def test_too_warm_zeroes_it(self):
        # At/above the setpoint, ventilation can't cool -> windows score 0.
        self.assertEqual(window_score(self.c, temp_f=80, dew_point_f=50, aqi=20).score, 0.0)

    def test_muggy_zeroes_it(self):
        # Dew point at the muggy cap kills favorability even when cool.
        self.assertEqual(window_score(self.c, temp_f=70, dew_point_f=60, aqi=20).score, 0.0)

    def test_bad_air_zeroes_it(self):
        self.assertEqual(window_score(self.c, temp_f=70, dew_point_f=50, aqi=100).score, 0.0)

    def test_rain_probability_ramps(self):
        dry = window_score(self.c, temp_f=70, dew_point_f=50, aqi=20, precip_prob=10)
        wet = window_score(self.c, temp_f=70, dew_point_f=50, aqi=20, precip_prob=70)
        self.assertEqual(dry.score, 100.0)
        self.assertEqual(wet.score, 0.0)

    def test_temp_ramps_over_margin(self):
        # 77°F is halfway through the 76->78 ramp.
        s = window_score(self.c, temp_f=77, dew_point_f=50, aqi=20)
        self.assertAlmostEqual(s.f_temp, 0.5, places=2)
        self.assertAlmostEqual(s.score, 50.0, places=1)

    def test_missing_temp_is_zero(self):
        self.assertEqual(window_score(self.c, temp_f=None).score, 0.0)

    def test_unknown_aqi_does_not_block(self):
        # No AQI data should not penalize (matches decision.py behavior).
        self.assertEqual(window_score(self.c, temp_f=70, dew_point_f=50, aqi=None).score, 100.0)


if __name__ == "__main__":
    unittest.main()
