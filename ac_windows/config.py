"""Load and validate user configuration from a TOML file.

Uses stdlib `tomllib` (Python 3.11+). Unknown keys are ignored so the config
file can carry comments/future fields without breaking older code.
"""

from __future__ import annotations

import pathlib
import tomllib
from dataclasses import dataclass, fields


@dataclass
class Location:
    latitude: float
    longitude: float
    timezone: str = "auto"  # IANA name like "America/New_York", or "auto"


@dataclass
class Comfort:
    # You don't want the house warmer than this (°F).
    max_temp_f: float = 78.0
    # Dew point is the best single "muggy-ness" gauge (see README).
    dew_point_ideal_f: float = 55.0   # at/below this the air feels dry & pleasant
    dew_point_max_f: float = 60.0     # above this, ventilating feels clammy -> prefer AC
    # Indoor relative-humidity preferences (informational; dew point drives the call).
    humidity_ideal_low: float = 40.0
    humidity_ideal_high: float = 45.0
    humidity_ok_low: float = 30.0
    humidity_ok_high: float = 50.0
    # Outdoor must be at least this far below max_temp_f before opening windows is worth it.
    temp_margin_f: float = 2.0
    # Above this US AQI, keep windows shut regardless of temperature.
    aqi_max: int = 100

    # ── Forecast / scoring (the "should I open the windows" graph) ──
    # How many hours ahead to score and graph (widget shows as many as fit).
    forecast_hours: int = 48
    # Open-window score (0-100) at/above which opening is advised (vs AC/closed).
    open_score_min: float = 60.0
    # Solar-load modifier (see README "Methodology"): a clear, sunny sky modestly
    # lowers window favorability when the temperature is already near your limit,
    # because solar gain heats the house faster than cool air can flush it.
    solar_strong_wm2: float = 700.0   # ~full midday sun; scales the modifier
    solar_max_penalty: float = 0.15   # at most a 15% knock from strong sun


@dataclass
class Sources:
    providers: list[str]
    user_agent: str


@dataclass
class Config:
    location: Location
    comfort: Comfort
    sources: Sources


def _filter_known(cls, data: dict) -> dict:
    """Keep only keys that are real fields of dataclass `cls`."""
    known = {f.name for f in fields(cls)}
    return {k: v for k, v in data.items() if k in known}


def load_config(path: str | pathlib.Path) -> Config:
    p = pathlib.Path(path)
    if not p.exists():
        raise SystemExit(
            f"Config not found: {p}\n"
            f"Copy config.example.toml to {p} and fill in your location."
        )
    with open(p, "rb") as f:
        raw = tomllib.load(f)

    loc = raw.get("location", {})
    if "latitude" not in loc or "longitude" not in loc:
        raise SystemExit("config [location] must set both latitude and longitude")
    location = Location(
        latitude=float(loc["latitude"]),
        longitude=float(loc["longitude"]),
        timezone=str(loc.get("timezone", "auto")),
    )

    comfort = Comfort(**_filter_known(Comfort, raw.get("comfort", {})))

    sc = raw.get("sources", {})
    sources = Sources(
        providers=list(sc.get("providers", ["open_meteo", "nws", "metno"])),
        user_agent=str(sc.get("user_agent", "ac-widget/0.1 (set a contact in config)")),
    )

    return Config(location=location, comfort=comfort, sources=sources)
