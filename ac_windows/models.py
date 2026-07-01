"""Plain data containers shared across the app. No logic here."""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Optional


@dataclass
class WeatherReading:
    """One source's view of the current outdoor conditions.

    Every numeric field is Optional because any given source may not provide
    it (e.g. NWS has no AQI). `ok=False` means the whole fetch failed and the
    reading should be ignored except for reporting which source went down.
    """

    source: str
    temp_f: Optional[float] = None
    humidity_pct: Optional[float] = None
    dew_point_f: Optional[float] = None
    apparent_temp_f: Optional[float] = None
    precip_mm: Optional[float] = None
    wind_mph: Optional[float] = None
    aqi: Optional[int] = None
    observed_at: Optional[str] = None  # ISO timestamp reported by the source
    station: Optional[str] = None      # e.g. NWS station id, for transparency
    ok: bool = True
    error: Optional[str] = None


@dataclass
class AggregateReading:
    """The cross-source consensus that the decision engine actually uses."""

    temp_f: Optional[float] = None
    dew_point_f: Optional[float] = None
    humidity_pct: Optional[float] = None
    apparent_temp_f: Optional[float] = None
    precip_mm: Optional[float] = None
    wind_mph: Optional[float] = None
    aqi: Optional[int] = None

    confidence: str = "none"          # high | degraded | conflict | none
    temp_spread_f: float = 0.0        # max-min across sources (disagreement)
    sources_ok: list[str] = field(default_factory=list)
    sources_failed: list[tuple[str, str]] = field(default_factory=list)
    per_source: dict[str, WeatherReading] = field(default_factory=dict)


@dataclass
class Recommendation:
    """The output of the decision engine."""

    action: str        # OPEN_WINDOWS | RUN_AC | KEEP_CLOSED | COMFORTABLE | UNKNOWN
    headline: str      # short imperative, e.g. "Open your windows"
    summary: str       # one-sentence rationale
    reasons: list[str] = field(default_factory=list)  # supporting bullet points


@dataclass
class ForecastPoint:
    """One scored hour of the look-ahead window."""

    time: str                              # local ISO, e.g. "2026-06-15T21:00"
    score: float = 0.0                     # Open-Window Score 0..100
    temp_f: Optional[float] = None
    dew_point_f: Optional[float] = None
    aqi: Optional[int] = None
    precip_prob: Optional[float] = None
    cloud_pct: Optional[float] = None
    shortwave_wm2: Optional[float] = None
    nws_temp_f: Optional[float] = None      # cross-check from NWS hourly forecast


@dataclass
class Forecast:
    """The next N hours of Open-Window Scores plus the best time to open up."""

    points: list[ForecastPoint] = field(default_factory=list)
    best_start: Optional[str] = None        # local ISO of best contiguous open window
    best_end: Optional[str] = None
    best_avg_score: Optional[float] = None
    temp_agreement: str = "none"            # agree | degraded | conflict | none
    temp_spread_f: float = 0.0              # mean |Open-Meteo - NWS| over the horizon
    notes: list[str] = field(default_factory=list)
