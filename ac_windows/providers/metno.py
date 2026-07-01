"""met.no (Norwegian Meteorological Institute) — free, keyless, global, forecasting.

A genuinely independent third opinion from Open-Meteo (ICON-family) and NWS
(US observations): met.no's own model. One simple HTTP GET to the Location
Forecast 2.0 "compact" product; the first timeseries entry is "now".

met.no requires a descriptive User-Agent with a contact (we send one). It does
NOT publish dew point in the compact product, so we derive it from temperature +
relative humidity via the Magnus formula — the same quantity, computed locally.
No AQI from met.no (that stays Open-Meteo's job).

Note: met.no's WAF blocks some datacenter IPs (it 403s from this dev sandbox),
but works fine from a home network or phone. A 403 just marks the source down;
the others still produce a result.

Docs: https://api.met.no/weatherapi/locationforecast/2.0/documentation
"""

from __future__ import annotations

import math

from ..config import Location
from ..http_util import FetchError, get_json
from ..models import WeatherReading

URL = "https://api.met.no/weatherapi/locationforecast/2.0/compact"


def _dew_point_c(temp_c: float | None, rh_pct: float | None) -> float | None:
    """Magnus-formula dew point (°C) from air temperature (°C) and RH (%)."""
    if temp_c is None or rh_pct is None or rh_pct <= 0:
        return None
    a, b = 17.625, 243.04
    gamma = math.log(rh_pct / 100.0) + a * temp_c / (b + temp_c)
    return b * gamma / (a - gamma)


def _c_to_f(c):
    return None if c is None else c * 9.0 / 5.0 + 32.0


def fetch(loc: Location, user_agent: str, timeout: float = 15.0) -> WeatherReading:
    r = WeatherReading(source="metno")
    url = f"{URL}?lat={loc.latitude}&lon={loc.longitude}"
    try:
        ts = get_json(url, user_agent, timeout)["properties"]["timeseries"]
        if not ts:
            raise FetchError("met.no returned an empty timeseries")
        now = ts[0]
        inst = now["data"]["instant"]["details"]
    except (FetchError, KeyError, IndexError, TypeError) as e:
        r.ok = False
        r.error = f"metno: {e}"
        return r

    temp_c = inst.get("air_temperature")
    rh = inst.get("relative_humidity")
    r.temp_f = _c_to_f(temp_c)
    r.humidity_pct = rh
    r.dew_point_f = _c_to_f(_dew_point_c(temp_c, rh))
    wind_ms = inst.get("wind_speed")
    r.wind_mph = None if wind_ms is None else wind_ms * 2.236936
    # Precipitation for the next hour, when present.
    nxt = now["data"].get("next_1_hours", {}).get("details", {})
    r.precip_mm = nxt.get("precipitation_amount")
    r.observed_at = now.get("time")
    return r

# Note: met.no also returns an hourly timeseries, but the forecast track is built
# from Open-Meteo (the score driver) cross-checked against NWS only — see
# forecast.py — so no met.no `fetch_hourly` is wired in. (met.no's compact product
# reports times in UTC, which would need converting to the local-time keys the
# other sources use before it could join that track.)
