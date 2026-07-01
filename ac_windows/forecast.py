"""Turn two hourly series (Open-Meteo, NWS) into a scored look-ahead Forecast.

Open-Meteo drives the score (it's the only source with hourly cloud, solar and
AQI); NWS's hourly forecast is folded in as a temperature cross-check so the
trajectory has the same multi-source agreement guarantee as the "now" reading.
Pure function of its inputs — no I/O — so it's fully unit-tested.
"""

from __future__ import annotations

from .config import Comfort
from .models import Forecast, ForecastPoint
from .score import window_score

# Temperature agreement tolerance for the forecast track (matches aggregate.py).
_FORECAST_TOL_F = 8.0
# Hours considered "overnight" for the night-flush heuristic.
_NIGHT_START, _NIGHT_END = 21, 8


def _hour(iso: str) -> int:
    try:
        return int(iso[11:13])
    except (ValueError, IndexError):
        return -1


def fmt_hour(iso: str) -> str:
    """'2026-06-15T21:00' -> '9 PM'."""
    h = _hour(iso)
    if h < 0:
        return iso
    ampm = "AM" if h < 12 else "PM"
    h12 = h % 12 or 12
    return f"{h12} {ampm}"


def _best_window(points: list[ForecastPoint], threshold: float):
    """Best contiguous run of open-worthy hours, by area above the threshold.

    Returns (start_iso, end_iso, avg_score) or (None, None, None) if no hour
    clears the threshold (outdoor air won't beat the AC over the horizon).
    """
    best = None  # (area, start_idx, end_idx)
    i, n = 0, len(points)
    while i < n:
        if points[i].score >= threshold:
            j = i
            area = 0.0
            while j < n and points[j].score >= threshold:
                area += points[j].score - threshold
                j += 1
            if best is None or area > best[0]:
                best = (area, i, j - 1)
            i = j
        else:
            i += 1
    if best is None:
        return None, None, None
    _, a, b = best
    avg = round(sum(p.score for p in points[a:b + 1]) / (b - a + 1), 1)
    return points[a].time, points[b].time, avg


def build_forecast(
    om_hourly: list[dict], nws_hourly: list[dict], c: Comfort
) -> Forecast:
    nws_temp = {h["time"]: h.get("temp_f") for h in nws_hourly if h.get("time")}

    points: list[ForecastPoint] = []
    for h in om_hourly:
        sb = window_score(
            c,
            temp_f=h.get("temp_f"),
            dew_point_f=h.get("dew_point_f"),
            aqi=h.get("aqi"),
            precip_prob=h.get("precip_prob"),
        )
        points.append(ForecastPoint(
            time=h["time"],
            score=sb.score,
            temp_f=h.get("temp_f"),
            dew_point_f=h.get("dew_point_f"),
            aqi=h.get("aqi"),
            precip_prob=h.get("precip_prob"),
            cloud_pct=h.get("cloud_pct"),
            shortwave_wm2=h.get("shortwave_wm2"),
            nws_temp_f=nws_temp.get(h["time"]),
        ))

    fc = Forecast(points=points)
    if not points:
        return fc

    fc.best_start, fc.best_end, fc.best_avg_score = _best_window(points, c.open_score_min)

    # Temperature agreement vs NWS over the overlapping hours.
    diffs = [
        abs(p.temp_f - p.nws_temp_f)
        for p in points if p.temp_f is not None and p.nws_temp_f is not None
    ]
    if not nws_temp:
        fc.temp_agreement = "degraded"   # Open-Meteo only
    elif diffs:
        fc.temp_spread_f = round(sum(diffs) / len(diffs), 1)
        fc.temp_agreement = "agree" if fc.temp_spread_f <= _FORECAST_TOL_F else "conflict"

    fc.notes = _notes(points, fc, c)
    return fc


def _notes(points: list[ForecastPoint], fc: Forecast, c: Comfort) -> list[str]:
    notes: list[str] = []
    thr = c.open_score_min
    now_open = points[0].score >= thr

    if fc.best_start is None:
        notes.append(
            f"Outdoor air won't beat the AC in the next {len(points)}h — "
            "keep windows closed / run AC.")
        return notes

    # When to open.
    if fc.best_start == points[0].time:
        notes.append(f"Good to open now (through {fmt_hour(fc.best_end)}).")
    else:
        notes.append(
            f"Best window to open: {fmt_hour(fc.best_start)}–{fmt_hour(fc.best_end)} "
            f"(avg score {fc.best_avg_score:.0f}).")

    # Overnight flush: best window lands mostly at night.
    if _hour(fc.best_start) >= _NIGHT_START or _hour(fc.best_start) < _NIGHT_END:
        notes.append("Overnight is the strong flush window — let the house coast on cool air.")

    # Open-now-then-pre-cool: good now but it drops off as it warms.
    if now_open and any(p.score < thr for p in points[1:]):
        drop = next(p for p in points[1:] if p.score < thr)
        notes.append(
            f"Open now, then close up before ~{fmt_hour(drop.time)} when it warms "
            "(pre-cool if you have AC).")

    # Solar context: strong sun is depressing daytime scores.
    sunny = [p for p in points if (p.shortwave_wm2 or 0) >= 0.6 * c.solar_strong_wm2]
    if sunny and any(thr * 0.4 <= p.score < thr for p in sunny):
        notes.append("Afternoon sun adds heat load — shading sunny windows helps as much as airflow.")

    return notes[:3]
