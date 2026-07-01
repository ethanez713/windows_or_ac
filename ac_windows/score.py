"""The Open-Window Score: a single 0-100 number for "how good is it to have the
windows open right now (or at a given forecast hour)?"

It is a *continuous* generalisation of the yes/no logic in `decision.py`. The
discrete decision is just this score thresholded, so the two never disagree.

How it's built (see README "Methodology" for the full write-up)
---------------------------------------------------------------
The score is the product of independent factors, each ramping linearly between
thresholds *you already configured* in `[comfort]`. It's a product because
windows are only worth opening when the air is cool AND dry AND clean AND it
isn't raining — any one of those failing kills the case on its own:

    score = 100 · f_temp · f_humid · f_aqi · f_rain

  * f_temp  — cooling feasibility. Ventilation can't cool the house below the
              outdoor temperature, so this is 1 when it's comfortably below your
              setpoint and 0 once it reaches it.
  * f_humid — dryness. 1 at/below your ideal dew point, 0 at/above your muggy
              cap. (f_temp · f_humid is a practical stand-in for the HVAC
              "differential-enthalpy economizer" test: is the outdoor air's
              total heat content below your indoor target?)
  * f_aqi   — clean air. Ramps down to 0 as AQI approaches your limit.
  * f_rain  — not raining. From precipitation probability (or current rain).
"""

from __future__ import annotations

from dataclasses import dataclass

from .config import Comfort


@dataclass
class ScoreBreakdown:
    """A scored hour, with every factor exposed so the reasoning is auditable."""

    score: float       # 0..100, higher = better to have windows open
    f_temp: float
    f_humid: float
    f_aqi: float
    f_rain: float


def _ramp_down(x: float | None, full: float, zero: float) -> float:
    """1.0 at x<=full, 0.0 at x>=zero, linear in between (full < zero).

    A missing value returns 1.0 (treat "unknown" as non-blocking — the same way
    `decision.py` only blocks on a factor it actually has data for).
    """
    if x is None:
        return 1.0
    if zero <= full:
        return 1.0 if x <= full else 0.0
    return max(0.0, min(1.0, (zero - x) / (zero - full)))


def window_score(
    c: Comfort,
    *,
    temp_f: float | None,
    dew_point_f: float | None = None,
    aqi: int | None = None,
    precip_prob: float | None = None,
    precip_mm: float | None = None,
) -> ScoreBreakdown:
    """Score one set of outdoor conditions against the comfort config."""
    if temp_f is None:
        return ScoreBreakdown(0.0, 0.0, 1.0, 1.0, 1.0)

    f_temp = _ramp_down(temp_f, c.max_temp_f - c.temp_margin_f, c.max_temp_f)
    f_humid = _ramp_down(dew_point_f, c.dew_point_ideal_f, c.dew_point_max_f)
    f_aqi = _ramp_down(aqi, c.aqi_max * 0.5, float(c.aqi_max))

    if precip_prob is not None:
        f_rain = _ramp_down(precip_prob, 20.0, 70.0)
    elif precip_mm is not None:
        f_rain = 0.0 if precip_mm > 0 else 1.0
    else:
        f_rain = 1.0

    score = 100.0 * f_temp * f_humid * f_aqi * f_rain
    return ScoreBreakdown(
        score=round(score, 1),
        f_temp=round(f_temp, 3),
        f_humid=round(f_humid, 3),
        f_aqi=round(f_aqi, 3),
        f_rain=round(f_rain, 3),
    )
