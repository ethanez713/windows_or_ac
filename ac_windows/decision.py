"""The core recommendation engine: open windows vs. run AC.

This is a pure function of (consensus reading, comfort config) — no I/O — so it
is fully unit-testable and easy to reason about. It is also the natural seam for
future smart-home control: today it returns an action string; later a controller
can map OPEN_WINDOWS -> open the window openers, RUN_AC -> set the thermostat.

The mental model
----------------
Opening windows is "free cooling": worth it only when the outdoor air is
cooler than you want indoors AND comfortable AND clean AND dry.

  * Temperature — must be below your max (with a margin) for ventilation to help.
  * Dew point   — the real comfort gauge. Relative humidity moves with
                  temperature, but dew point tracks absolute moisture: <=55°F
                  feels dry, ~60°F starts to feel sticky, 65°F+ is oppressive.
                  If outdoor dew point is high, opening up just imports mugginess,
                  so AC (which dehumidifies) wins even at a tolerable temperature.
  * Air quality — high AQI (smoke, smog) is a hard stop on opening windows.
  * Precipitation — don't invite rain in.
"""

from __future__ import annotations

from .config import Comfort
from .models import AggregateReading, Recommendation


def aqi_category(aqi: int | None) -> str:
    if aqi is None:
        return "unknown"
    if aqi <= 50:
        return "good"
    if aqi <= 100:
        return "moderate"
    if aqi <= 150:
        return "unhealthy for sensitive groups"
    if aqi <= 200:
        return "unhealthy"
    if aqi <= 300:
        return "very unhealthy"
    return "hazardous"


def decide(agg: AggregateReading, c: Comfort) -> Recommendation:
    if agg.temp_f is None:
        return Recommendation(
            action="UNKNOWN",
            headline="No data",
            summary="Couldn't read the outdoor temperature from any source.",
            reasons=[f"{src}: {err}" for src, err in agg.sources_failed] or ["all sources unavailable"],
        )

    reasons: list[str] = []

    aqi_bad = agg.aqi is not None and agg.aqi > c.aqi_max
    raining = agg.precip_mm is not None and agg.precip_mm > 0
    muggy = agg.dew_point_f is not None and agg.dew_point_f > c.dew_point_max_f
    too_warm = agg.temp_f > c.max_temp_f
    cool_enough = agg.temp_f <= c.max_temp_f - c.temp_margin_f

    if aqi_bad:
        reasons.append(
            f"AQI {agg.aqi} ({aqi_category(agg.aqi)}) is above your limit of "
            f"{c.aqi_max} — outdoor air is unhealthy to let in"
        )
    if raining:
        reasons.append("precipitation right now — opening windows risks letting rain in")
    if muggy:
        reasons.append(
            f"dew point {agg.dew_point_f:.0f}°F is muggy (above {c.dew_point_max_f:.0f}°F) "
            "— fresh air would feel clammy"
        )

    windows_blocked = aqi_bad or raining or muggy

    # 1) Too warm to coast: ventilation can't cool below the outdoor temp.
    if too_warm:
        reasons.insert(0, f"it's {agg.temp_f:.0f}°F out, above your {c.max_temp_f:.0f}°F max")
        return Recommendation(
            action="RUN_AC",
            headline="Turn on the AC",
            summary="Outdoor air is warmer than your target, so opening up won't cool the house.",
            reasons=reasons,
        )

    # 2) Comfortable temperature, but a blocker keeps windows shut.
    if windows_blocked:
        if muggy:
            return Recommendation(
                action="RUN_AC",
                headline="Keep windows shut — run AC to dehumidify",
                summary="It isn't hot, but the air is muggy; AC will dry it out and stay comfortable.",
                reasons=reasons,
            )
        return Recommendation(
            action="KEEP_CLOSED",
            headline="Keep windows closed",
            summary="Indoor temperature is fine — just don't open up right now.",
            reasons=reasons,
        )

    # 3) Cooler than your target, and clean/dry: free cooling.
    if cool_enough:
        reasons.insert(0, f"it's {agg.temp_f:.0f}°F out, below your {c.max_temp_f:.0f}°F max")
        if agg.dew_point_f is not None:
            reasons.append(f"dew point {agg.dew_point_f:.0f}°F is comfortable")
        if agg.aqi is not None:
            reasons.append(f"AQI {agg.aqi} ({aqi_category(agg.aqi)})")
        return Recommendation(
            action="OPEN_WINDOWS",
            headline="Open your windows",
            summary="Cooler, comfortable, clean air outside — let it cool the house for free.",
            reasons=reasons,
        )

    # 4) Near your setpoint with no blockers: either choice is fine.
    return Recommendation(
        action="COMFORTABLE",
        headline="You're comfortable — windows optional",
        summary=(
            f"It's {agg.temp_f:.0f}°F out, near your {c.max_temp_f:.0f}°F target; "
            "open up if you'd like fresh air."
        ),
        reasons=reasons or ["conditions are within your comfort band"],
    )
