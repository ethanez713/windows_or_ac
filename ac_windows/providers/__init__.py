"""Weather data providers.

Each provider exposes `fetch(location, user_agent, timeout) -> WeatherReading`
and must NEVER raise: on failure it returns a reading with ok=False so that one
dead source can't take down the others. Add a new source by writing a module
with a `fetch` function and registering it here.
"""

from . import metno, nws, open_meteo

REGISTRY = {
    "open_meteo": open_meteo.fetch,
    "nws": nws.fetch,
    "metno": metno.fetch,
}
