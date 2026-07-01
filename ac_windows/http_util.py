"""Minimal JSON-over-HTTP helper built on the standard library only.

Using urllib (instead of `requests`) keeps this project dependency-free, which
is the cheapest possible supply-chain posture for a personal tool.
"""

from __future__ import annotations

import json
import urllib.error
import urllib.request


class FetchError(Exception):
    """Any network/parse failure while fetching a source."""


def get_json(url: str, user_agent: str, timeout: float = 15.0) -> dict:
    """GET `url` and parse JSON, or raise FetchError.

    A descriptive User-Agent is required by some providers (NWS, met.no) and is
    polite to all of them, so we always send it.
    """
    req = urllib.request.Request(
        url,
        headers={"User-Agent": user_agent, "Accept": "application/json"},
    )
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            return json.loads(resp.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        raise FetchError(f"HTTP {e.code} from {url}") from e
    except (urllib.error.URLError, TimeoutError, OSError) as e:
        raise FetchError(f"network error for {url}: {e}") from e
    except (ValueError, json.JSONDecodeError) as e:
        raise FetchError(f"bad JSON from {url}: {e}") from e
