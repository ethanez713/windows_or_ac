"""US National Weather Service provider — free, keyless, US-only.

This reads the latest *measured* observation from the nearest station, which is
a genuinely independent source from Open-Meteo's model output, so the two make a
meaningful agreement check. Three small calls:

    points/{lat},{lon}                -> observationStations URL
    {observationStations}             -> nearest station id
    stations/{id}/observations/latest -> the reading (SI units)

NWS does not provide AQI, so `aqi` stays None here.
Docs: https://www.weather.gov/documentation/services-web-api
"""

from __future__ import annotations

from ..config import Location
from ..http_util import FetchError, get_json
from ..models import WeatherReading

BASE = "https://api.weather.gov"


def _val(field: dict | None):
    """NWS wraps measurements as {"value": x, "unitCode": ...}; pull the value."""
    if not field:
        return None
    return field.get("value")


def _c_to_f(c):
    return None if c is None else c * 9.0 / 5.0 + 32.0


def _kmh_to_mph(kmh):
    return None if kmh is None else kmh * 0.621371


def fetch(loc: Location, user_agent: str, timeout: float = 15.0) -> WeatherReading:
    r = WeatherReading(source="nws")
    try:
        pts = get_json(f"{BASE}/points/{loc.latitude},{loc.longitude}", user_agent, timeout)
        stations_url = pts["properties"]["observationStations"]
        stations = get_json(stations_url, user_agent, timeout)["features"]
        if not stations:
            raise FetchError("no NWS observation stations near this location")
        station_id = stations[0]["properties"]["stationIdentifier"]
        obs = get_json(
            f"{BASE}/stations/{station_id}/observations/latest", user_agent, timeout
        )["properties"]
    except (FetchError, KeyError, IndexError, TypeError) as e:
        r.ok = False
        r.error = f"nws: {e}"
        return r

    r.station = station_id
    r.temp_f = _c_to_f(_val(obs.get("temperature")))
    r.dew_point_f = _c_to_f(_val(obs.get("dewpoint")))
    r.humidity_pct = _val(obs.get("relativeHumidity"))
    r.wind_mph = _kmh_to_mph(_val(obs.get("windSpeed")))
    r.observed_at = obs.get("timestamp")
    return r


def fetch_hourly(
    loc: Location, user_agent: str, hours: int = 8, timeout: float = 15.0
) -> list[dict]:
    """Next `hours` of NWS hourly *forecast* (temp + dew point), for cross-checking
    Open-Meteo's forecast trajectory. NWS gives no AQI/solar, so this is a
    temperature/dew-point sanity check only. Returns [] on any failure.
    """
    try:
        pts = get_json(f"{BASE}/points/{loc.latitude},{loc.longitude}", user_agent, timeout)
        hourly_url = pts["properties"]["forecastHourly"]
        periods = get_json(hourly_url, user_agent, timeout)["properties"]["periods"]
    except (FetchError, KeyError, IndexError, TypeError):
        return []

    out: list[dict] = []
    for p in periods[:hours]:
        temp = p.get("temperature")
        if temp is not None and p.get("temperatureUnit") == "C":
            temp = _c_to_f(temp)
        out.append({
            "time": (p.get("startTime") or "")[:16],  # "...T16:00" local, match Open-Meteo
            "temp_f": temp,
            "dew_point_f": _c_to_f(_val(p.get("dewpoint"))),
        })
    return out
