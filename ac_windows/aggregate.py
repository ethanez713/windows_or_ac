"""Combine multiple per-source readings into one consensus reading.

The point of querying >=2 sources is twofold:
  * resilience  — if one source is down, we still produce a result
  * sanity      — if two sources wildly disagree, we flag low confidence

Numeric fields are averaged across the sources that reported them. AQI is taken
from whichever source(s) provide it (currently only Open-Meteo).
"""

from __future__ import annotations

from .models import AggregateReading, WeatherReading

# If sources' temperatures differ by more than this, something's off (wrong
# location, stale station, etc.) and we downgrade confidence to "conflict".
# A model (Open-Meteo) and a point station (NWS) can legitimately differ ~5-7°F,
# especially in cities, so this is deliberately loose to avoid false alarms.
AGREEMENT_TOLERANCE_F = 8.0


def _avg(readings: list[WeatherReading], attr: str):
    vals = [getattr(r, attr) for r in readings if getattr(r, attr) is not None]
    return sum(vals) / len(vals) if vals else None


def aggregate(readings: list[WeatherReading]) -> AggregateReading:
    ok = [r for r in readings if r.ok]
    failed = [(r.source, r.error or "unknown error") for r in readings if not r.ok]

    aqis = [r.aqi for r in ok if r.aqi is not None]
    agg = AggregateReading(
        temp_f=_avg(ok, "temp_f"),
        dew_point_f=_avg(ok, "dew_point_f"),
        humidity_pct=_avg(ok, "humidity_pct"),
        apparent_temp_f=_avg(ok, "apparent_temp_f"),
        precip_mm=_avg(ok, "precip_mm"),
        wind_mph=_avg(ok, "wind_mph"),
        aqi=int(round(sum(aqis) / len(aqis))) if aqis else None,
        sources_ok=[r.source for r in ok],
        sources_failed=failed,
        per_source={r.source: r for r in ok},
    )

    temps = [r.temp_f for r in ok if r.temp_f is not None]
    agg.temp_spread_f = (max(temps) - min(temps)) if len(temps) >= 2 else 0.0

    if not ok:
        agg.confidence = "none"
    elif len(ok) == 1:
        agg.confidence = "degraded"
    elif agg.temp_spread_f > AGREEMENT_TOLERANCE_F:
        agg.confidence = "conflict"
    else:
        agg.confidence = "high"

    return agg
