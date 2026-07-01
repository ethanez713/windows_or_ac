"""Open-Meteo provider — free, keyless, global.

Two endpoints:
  * forecast      -> temperature, humidity, dew point, apparent temp, precip, wind
  * air-quality   -> US AQI and components

Docs: https://open-meteo.com/en/docs  and  https://open-meteo.com/en/docs/air-quality-api
"""

from __future__ import annotations

from ..config import Location
from ..http_util import FetchError, get_json
from ..models import WeatherReading

FORECAST_URL = "https://api.open-meteo.com/v1/forecast"
AIR_QUALITY_URL = "https://air-quality-api.open-meteo.com/v1/air-quality"

_CURRENT_FIELDS = (
    "temperature_2m,relative_humidity_2m,dew_point_2m,apparent_temperature,"
    "precipitation,weather_code,wind_speed_10m"
)


def fetch(loc: Location, user_agent: str, timeout: float = 15.0) -> WeatherReading:
    r = WeatherReading(source="open-meteo")

    url = (
        f"{FORECAST_URL}?latitude={loc.latitude}&longitude={loc.longitude}"
        f"&current={_CURRENT_FIELDS}"
        f"&temperature_unit=fahrenheit&wind_speed_unit=mph&precipitation_unit=mm"
        f"&timezone={loc.timezone}"
    )
    try:
        cur = get_json(url, user_agent, timeout).get("current", {})
    except FetchError as e:
        r.ok = False
        r.error = str(e)
        return r

    r.temp_f = cur.get("temperature_2m")
    r.humidity_pct = cur.get("relative_humidity_2m")
    r.dew_point_f = cur.get("dew_point_2m")
    r.apparent_temp_f = cur.get("apparent_temperature")
    r.precip_mm = cur.get("precipitation")
    r.wind_mph = cur.get("wind_speed_10m")
    r.observed_at = cur.get("time")

    # Air quality lives on a separate endpoint; treat its failure as non-fatal
    # so we still return temperature/humidity even if AQI is unavailable.
    aq_url = (
        f"{AIR_QUALITY_URL}?latitude={loc.latitude}&longitude={loc.longitude}"
        f"&current=us_aqi,pm2_5,pm10,ozone&timezone={loc.timezone}"
    )
    try:
        aqi = get_json(aq_url, user_agent, timeout).get("current", {}).get("us_aqi")
        if aqi is not None:
            r.aqi = int(round(aqi))
    except FetchError:
        pass

    return r


_HOURLY_FIELDS = (
    "temperature_2m,dew_point_2m,relative_humidity_2m,precipitation_probability,"
    "cloud_cover,shortwave_radiation,wind_speed_10m"
)


def fetch_hourly(
    loc: Location, user_agent: str, hours: int = 8, timeout: float = 15.0
) -> list[dict]:
    """Next `hours` of hourly forecast, merged with hourly US AQI.

    Returns a list of dicts keyed by local ISO time; the richest forecasting
    source we have (it alone carries cloud cover, solar radiation, and AQI per
    hour). Returns [] on failure — the forecast is best-effort, never fatal.
    """
    url = (
        f"{FORECAST_URL}?latitude={loc.latitude}&longitude={loc.longitude}"
        f"&hourly={_HOURLY_FIELDS}&forecast_hours={hours}"
        f"&temperature_unit=fahrenheit&wind_speed_unit=mph&precipitation_unit=mm"
        f"&timezone={loc.timezone}"
    )
    try:
        h = get_json(url, user_agent, timeout).get("hourly", {})
    except FetchError:
        return []

    times = h.get("time") or []
    if not times:
        return []

    # AQI is a separate endpoint; merge by timestamp, tolerate its absence.
    aqi_by_time: dict[str, int] = {}
    aq_url = (
        f"{AIR_QUALITY_URL}?latitude={loc.latitude}&longitude={loc.longitude}"
        f"&hourly=us_aqi&forecast_hours={hours}&timezone={loc.timezone}"
    )
    try:
        ah = get_json(aq_url, user_agent, timeout).get("hourly", {})
        for t, a in zip(ah.get("time", []), ah.get("us_aqi", [])):
            if a is not None:
                aqi_by_time[t] = int(round(a))
    except FetchError:
        pass

    def col(name: str) -> list:
        return h.get(name) or [None] * len(times)

    temp, dew, rh = col("temperature_2m"), col("dew_point_2m"), col("relative_humidity_2m")
    pop, cloud = col("precipitation_probability"), col("cloud_cover")
    sw, wind = col("shortwave_radiation"), col("wind_speed_10m")

    out: list[dict] = []
    for i, t in enumerate(times):
        out.append({
            "time": t,
            "temp_f": temp[i],
            "dew_point_f": dew[i],
            "rh": rh[i],
            "precip_prob": pop[i],
            "cloud_pct": cloud[i],
            "shortwave_wm2": sw[i],
            "wind_mph": wind[i],
            "aqi": aqi_by_time.get(t),
        })
    return out
