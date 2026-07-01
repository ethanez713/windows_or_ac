"""Command-line entrypoint: fetch -> aggregate -> decide -> print.

    python -m ac_windows.cli                 # human-readable, uses ./config.toml
    python -m ac_windows.cli --json          # machine-readable (for the widget)
    python -m ac_windows.cli --lat 40.7 --lon -74.0   # one-off location override

The --json output is the contract a phone widget (or a future smart-home
controller) consumes, so its shape is kept stable and flat.
"""

from __future__ import annotations

import argparse
import dataclasses
import datetime
import json
import sys
from concurrent.futures import ThreadPoolExecutor

from .aggregate import aggregate
from .config import Config, load_config
from .decision import aqi_category, decide
from .forecast import build_forecast, fmt_hour
from .models import AggregateReading, Forecast, Recommendation
from .providers import REGISTRY, nws, open_meteo
from .score import window_score

_ACTION_EMOJI = {
    "OPEN_WINDOWS": "🪟",
    "RUN_AC": "❄️",
    "KEEP_CLOSED": "🚪",
    "COMFORTABLE": "🙂",
    "UNKNOWN": "❓",
}


def fetch_all(cfg: Config):
    """Query every configured provider in parallel. Providers never raise."""
    fns = [(name, REGISTRY[name]) for name in cfg.sources.providers if name in REGISTRY]
    if not fns:
        return []
    with ThreadPoolExecutor(max_workers=len(fns)) as ex:
        futures = [
            ex.submit(fn, cfg.location, cfg.sources.user_agent) for _, fn in fns
        ]
        return [f.result() for f in futures]


def fetch_forecast(cfg: Config) -> Forecast:
    """Pull the hourly look-ahead from each forecasting source in parallel and
    build the scored Forecast. Open-Meteo drives the score; NWS cross-checks the
    temperature track. Never raises — returns an empty Forecast on total failure.
    """
    hrs = cfg.comfort.forecast_hours
    ua = cfg.sources.user_agent
    with ThreadPoolExecutor(max_workers=2) as ex:
        f_om = ex.submit(open_meteo.fetch_hourly, cfg.location, ua, hrs)
        f_nws = ex.submit(nws.fetch_hourly, cfg.location, ua, hrs)
        om_hourly, nws_hourly = f_om.result(), f_nws.result()
    return build_forecast(om_hourly, nws_hourly, cfg.comfort)


def _round(x, n=1):
    return round(x, n) if isinstance(x, (int, float)) else x


def build_payload(
    cfg: Config, agg: AggregateReading, rec: Recommendation, fc: Forecast
) -> dict:
    """The stable JSON contract for downstream consumers (widget, controller)."""
    return {
        "generated_at": datetime.datetime.now(datetime.timezone.utc).isoformat(),
        "action": rec.action,
        "headline": rec.headline,
        "summary": rec.summary,
        "reasons": rec.reasons,
        "readings": {
            "temp_f": _round(agg.temp_f),
            "dew_point_f": _round(agg.dew_point_f),
            "humidity_pct": _round(agg.humidity_pct),
            "apparent_temp_f": _round(agg.apparent_temp_f),
            "wind_mph": _round(agg.wind_mph),
            "precip_mm": _round(agg.precip_mm),
            "aqi": agg.aqi,
            "aqi_category": aqi_category(agg.aqi),
        },
        "confidence": agg.confidence,
        "temp_spread_f": _round(agg.temp_spread_f),
        "sources_ok": agg.sources_ok,
        "sources_failed": [{"source": s, "error": e} for s, e in agg.sources_failed],
        "per_source": {
            name: dataclasses.asdict(r) for name, r in agg.per_source.items()
        },
        "now_score": window_score(
            cfg.comfort, temp_f=agg.temp_f, dew_point_f=agg.dew_point_f,
            aqi=agg.aqi, precip_mm=agg.precip_mm,
        ).score,
        "forecast": {
            "open_score_min": cfg.comfort.open_score_min,
            "best_start": fc.best_start,
            "best_end": fc.best_end,
            "best_avg_score": fc.best_avg_score,
            "temp_agreement": fc.temp_agreement,
            "notes": fc.notes,
            "hours": [
                {
                    "time": p.time,
                    "score": p.score,
                    "temp_f": _round(p.temp_f),
                    "dew_point_f": _round(p.dew_point_f),
                    "aqi": p.aqi,
                    "precip_prob": p.precip_prob,
                    "cloud_pct": p.cloud_pct,
                    "shortwave_wm2": p.shortwave_wm2,
                    "nws_temp_f": _round(p.nws_temp_f),
                }
                for p in fc.points
            ],
        },
        "config": {
            "max_temp_f": cfg.comfort.max_temp_f,
            "dew_point_max_f": cfg.comfort.dew_point_max_f,
            "aqi_max": cfg.comfort.aqi_max,
        },
    }


def render_human(agg: AggregateReading, rec: Recommendation) -> str:
    emoji = _ACTION_EMOJI.get(rec.action, "")
    lines = [
        "",
        f"  {emoji}  {rec.headline.upper()}",
        f"     {rec.summary}",
        "",
    ]
    for reason in rec.reasons:
        lines.append(f"       • {reason}")
    lines.append("")

    def fmt(label, val, unit="", extra=""):
        if val is None:
            shown = "n/a"
        elif isinstance(val, float):
            shown = f"{val:.1f}{unit}"
        else:
            shown = f"{val}{unit}"
        return f"     {label:<14}{shown}{extra}"

    lines.append("     Readings")
    lines.append(fmt("temp", agg.temp_f, "°F"))
    lines.append(fmt("feels like", agg.apparent_temp_f, "°F"))
    lines.append(fmt("dew point", agg.dew_point_f, "°F"))
    lines.append(fmt("humidity", agg.humidity_pct, "%"))
    lines.append(fmt("wind", agg.wind_mph, " mph"))
    aqi_extra = f"  ({aqi_category(agg.aqi)})" if agg.aqi is not None else ""
    lines.append(fmt("AQI", agg.aqi, "", aqi_extra))
    lines.append("")

    # Per-source transparency so disagreements are visible.
    src_bits = []
    for name, r in agg.per_source.items():
        t = f"{r.temp_f:.0f}°F" if r.temp_f is not None else "n/a"
        src_bits.append(f"{name} {t}")
    for name, err in agg.sources_failed:
        src_bits.append(f"{name} DOWN")
    conf_note = {
        "high": "sources agree",
        "degraded": "only one source — degraded",
        "conflict": f"sources disagree by {agg.temp_spread_f:.0f}°F — check location",
        "none": "no sources available",
    }.get(agg.confidence, agg.confidence)
    lines.append(f"     Sources: {', '.join(src_bits) or 'none'}  ({conf_note})")
    lines.append("")
    return "\n".join(lines)


_BLOCKS = "▁▂▃▄▅▆▇█"


def render_forecast(fc: Forecast, c) -> str:
    """A compact unicode sparkline of the Open-Window Score for the next N hours.

    Tall blocks = open the windows; short = run the AC. `┊` marks midnight (day
    change), with the date below.
    """
    if not fc.points:
        return "     Forecast unavailable.\n"
    pts = fc.points
    spark = "".join(_BLOCKS[min(7, int(p.score / 100 * 8))] for p in pts)

    markers = [" "] * len(pts)
    labels = [" "] * len(pts)
    prev = None
    for i, p in enumerate(pts):
        d = p.time[:10]
        if prev is not None and d != prev:
            markers[i] = "┊"
            lbl = f"{int(p.time[5:7])}/{int(p.time[8:10])}"
            for j, ch in enumerate(lbl):
                if i + j < len(labels):
                    labels[i + j] = ch
        prev = d

    pad = " " * 11
    lines = [
        f"     Open-window score · next {len(pts)}h"
        f"   (open ≥ {c.open_score_min:.0f}, target ≤ {c.max_temp_f:.0f}°F)",
        "",
        f"       win {spark}",
        f"{pad}{''.join(markers)}",
        f"{pad}{''.join(labels).rstrip()}",
        "",
    ]
    for note in fc.notes:
        lines.append(f"     → {note}")
    if fc.temp_agreement == "conflict":
        lines.append(f"     ⚠ forecast sources disagree by ~{fc.temp_spread_f:.0f}°F")
    elif fc.temp_agreement == "degraded":
        lines.append("     (forecast from Open-Meteo only — NWS hourly unavailable)")
    lines.append("")
    return "\n".join(lines)


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description="Open windows or run the AC?")
    parser.add_argument("--config", default="config.toml", help="path to TOML config")
    parser.add_argument("--json", action="store_true", help="emit JSON for a widget")
    parser.add_argument("--lat", type=float, help="override latitude")
    parser.add_argument("--lon", type=float, help="override longitude")
    args = parser.parse_args(argv)

    cfg = load_config(args.config)
    if args.lat is not None:
        cfg.location.latitude = args.lat
    if args.lon is not None:
        cfg.location.longitude = args.lon

    readings = fetch_all(cfg)
    agg = aggregate(readings)
    rec = decide(agg, cfg.comfort)
    fc = fetch_forecast(cfg)

    if args.json:
        print(json.dumps(build_payload(cfg, agg, rec, fc), indent=2))
    else:
        print(render_human(agg, rec))
        print(render_forecast(fc, cfg.comfort))

    # Non-zero exit if we couldn't get any data, so callers/scripts can detect it.
    return 0 if agg.confidence != "none" else 1


if __name__ == "__main__":
    sys.exit(main())
