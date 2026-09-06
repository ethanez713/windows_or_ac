# AC / Windows — design handoff

Four deliverables. Two are redesigns of screens that already ship; two are new
surfaces that do not exist in the codebase yet.

| # | Surface | Status | Replaces / adds |
|---|---------|--------|-----------------|
| A | Windows-score app screen | **REDESIGN** — re-skin + drawer rebuild | `MainActivity.kt` |
| B | Windows-score widget (4×2) | **REDESIGN** — re-skin only, layout unchanged | `AcWindowsProvider.kt`, `Graph.kt` |
| C | Forecast widget (4×2) | **NEW IMPLEMENTATION** | new provider, installed alongside the existing graphs widget |
| D | Forecast app screen | **NEW IMPLEMENTATION** | new screen; companion to C |

**The existing graphs widget stays.** `GraphsWidgetProvider`, `PanelGraph`,
`widget_graphs.xml` and their settings activity keep working and stay registered
in the manifest; C ships beside them as a second, separate widget the user can
place independently. Whether the graphs widget is eventually retired is a
decision to make after C is in use — nothing in this document depends on it.

The two questions are deliberately kept apart:

- **A and B answer "when do I open the windows."** Score-led. A has a screen, so
  each reading gets its own graph.
- **C and D answer "what will conditions be in the next 24 h."** C has 186 dp, so
  four readings layer into one panel.

Do not converge them. An earlier iteration applied C's condensed encoding to the
windows screen and it was rejected.

---

## 0. Foundations

### 0.1 Data colours — do NOT theme these

The series colours carry meanings the user already reads. They are **not** part of
the app's visual theme and must not be replaced by theme accents. Only the
lightness step changes per theme, so each clears 4.5:1 on its own ground.

| Series | Light | Dark |
|--------|-------|------|
| Temperature | `#B45309` | `#FB923C` |
| Dew point | `#4338CA` | `#A5B4FC` |
| Air quality (line/label) | `#15803D` | `#4ADE80` |
| Rain | `#1D4ED8` | `#60A5FA` |
| Score / open | `#047857` | `#34D399` |
| Now marker | `#92400E` | `#FBBF24` |
| Thunderstorm | `#6D28D9` | `#A78BFA` |
| Blocked-bar base | `#8893A1` | `#606B7A` |
| Comfort-cutoff rule | `rgba(62,56,48,.45)` | `rgba(214,208,197,.5)` |

A blocked score bar is `lerp(blockedBase, limitingFactorHue, 0.38)` in linear
sRGB — the existing `detractorGrey` idea, kept.

Every graphical mark must clear **3:1** against its card ground. That is why the
light steps are the 700-level values rather than the dark theme's 400s.

### 0.2 AQI gradient

A continuous ramp interpolated across the hour axis, anchored on the EPA
breakpoints so anything under 50 reads flat green.

```
0 → #00A400   50 → #A68A00   100 → #E86A00
150 → #E00000  200 → #8F3F97  300 → #7E0023      (light)
0 → #00E400   50 → #FFFF00   100 → #FF7E00
150 → #FF0000  200 → #B060BC  300 → #C2335B      (dark)
```

Interpolate in sRGB between adjacent stops. The light ramp darkens the green and
yellow steps: the published EPA values fix hue but not lightness, and #00E400 /
#FFFF00 measure 1.05:1 on off-white. Always draw the bar with a 0.75 px
`ink-rule-strong` outline so it has an edge under any palette.

Two alternate palettes are user-selectable (see §5).

### 0.3 Chrome — this is the theme

Chrome follows the design system; data does not.

| Role | Light | Dark |
|------|-------|------|
| Card surface | `#FDFCF9` | `#232019` |
| App ground | `#F8F6F1` | `#1A1712` |
| Sunken (location row) | `#F1EDE5` | `#12100C` |
| Heading text | `#211D18` | `#F7F4ED` |
| Body text | `#3E3830` | `#D6D0C5` |
| Muted text | `#5F7080` | `#B4AEA2` |
| Faint text / axis labels | `#6B7A8A` | `#9A9385` |
| Hairline border | `#E8E2D8` | `rgba(247,244,237,.10)` |
| Day/night band tint | `rgba(33,29,24,.06)` on the **night** half | same value on the **day** half |

The band tint is a 6% ink lift: it darkens light paper but lightens dark ground,
so the tinted half must flip with the theme or night reads brighter than day.

Radii: cards 10, controls 7, rows/icon buttons 5, widget cards 24, phone frame 26.

### 0.4 Type

- **Piazzolla** — headings only. 20 px screen title, 16 px card titles, 25 px verdict.
- **Instrument Sans** — UI labels, row text. 12–15 px.
- **IBM Plex Mono** — every number, time, and axis label. 9 / 11 / 12 / 13 / 14 / 25 px.

The 9 px annotation and 11 px micro steps are **mono only** — never set a sans
label below 12 px. Chart labels are all mono by definition.

### 0.5 Icons

Material Symbols, already vendored: `ic_wx_clear_day`, `ic_wx_clear_night`,
`ic_wx_partly_day`, `ic_wx_partly_night`, `ic_wx_cloudy`, `ic_wx_rain`,
`ic_wx_thunder`, `ic_refresh`, `ic_settings`, `ic_location`. Use the vendored
paths verbatim — do not hand-trim them.

**Derive weather glyphs from data, never from a per-day code.** Rank per hour:

```
th ≥ thunderAlert → thunder
rain ≥ 20%        → rain
rain ≥ 8%         → cloudy
rain > 0          → partly
else              → clear
```

A day's day/night glyph is the max rank over hours 6–19 / 20–5. This guarantees an
icon can never contradict the numbers beside it.

---

## A. Windows-score app screen — REDESIGN

**Keep exactly as it ships:** score bars drawn from zero, the day-card list,
the location row, hi/lo with day + night icons, the "N h to open windows" line,
the score model (`scoreAt` = product of four factor ramps, open at ≥ 60).

**Two changes only:** chrome moves onto the design system, and the
detail-expansion drawer is rebuilt.

### A.1 Remove

- **The Score / Values toggle.** There is now one drawer.
- **The factor-multiplier chart.** Five overlapping 0–100 lines with a legend.
  The score bars already carry the verdict and the drawer carries the readings,
  so it had nothing left to add.

### A.2 Screen frame

393 dp wide. 16 dp side padding, 9 dp gap between cards, 14 dp bottom padding.

1. Title row, 36 dp — "AC / Windows" (Piazzolla 20) + settings icon 17 dp.
2. Location row — sunken fill, radius 5, padding 7/10: location pin 13 dp,
   place name (Instrument Sans 13), a 6 dp status dot in the score colour,
   `6/6 · 11:42 AM` (mono 11), refresh icon 15 dp in a 24 dp target.
3. Expanded day card (the drawer).
4. Remaining day cards, collapsed.

### A.3 Day card — fixed column widths

**The label and sparkline columns are fixed so the bars line up across all cards
and text length can never shift them.** This was the alignment bug.

The card content box is **333** dp: 393 − 2×16 frame padding − 2×1 frame border
− 2×12 card padding − 2×1 card border. The columns must sum to exactly that.

```
[ label 78 ][ 6 ][ sparkline 132 ][ 6 ][ icons 40 ][ 6 ][ hi/lo flex, min 56 ]  = 333
```

- **label** — Instrument Sans 15, ellipsis, fixed **78** dp. Not 71: "Tomorrow"
  measures 72 dp at this size and truncates to `Tomorro…`.
- **sparkline** — 132 × 34. 24 bars, slot = 5.5, bar width = slot × 0.72,
  height = score/100 × 34, min 1 dp, radius 1. Open bars in the score colour;
  blocked bars per §0.1. On today only, a 1.2 dp now-line at
  `slot × (hour + min/60)` in heading ink.
- **icons** — 2 × 17 dp, centered in a fixed 40 dp box.
- **hi/lo** — mono 15 / 13, right-aligned, **`flex: 1` with `min-width: 56`**.
  It must absorb the row's remaining slack, not sit in a fixed box: a fixed 56
  leaves ~16 dp unclaimed at the row end, which insets the pair from the card
  edge and misaligns it against the spans row directly beneath. Label and
  sparkline stay `flex: none` ahead of it, so the sparkline x-origin is still
  identical in every card.

Second line: open-hours text (Instrument Sans 12, score colour, or faint when
zero) on the left; open spans (mono 11, muted, right-aligned, ellipsis) on the
right. On a zero-hour day the spans slot carries the limiting factor instead
(`DEW 70°`, mono 11 in that factor's hue) — the two are mutually exclusive, so
they share one slot.

Use the compact span form in rows: `12a–12p, 3p–11p`. The full form
(`12 AM – 12 PM`) overruns the row once a day has two or more runs.

### A.4 The drawer — this is the design work

The current drawer puts **US AQI, rain chance and rain amount on one chart with a
left 0–100 axis and a right inches axis**, and identifies every series in a
legend. Replace with four stacked blocks sharing one time axis at the bottom.

All four charts are authored **329** dp wide inside the 333 dp content box, with
plot gutter x0 = 30, x1 = 329. The 4 dp of right inset is intentional: it keeps a
stroked mark at the last hour off the card border. The drawer's text rows
(readings line, chart eyebrows) still span the full 333.

**Hairline `ink-rule` divider**, then:

**1. Readings line** — mono 11, values in their own colours, ` · ` separated:

```
11:42 AM · score 0 · 86°F · dew 69° · AQI 71
```

**Suppress the rain item when the chance is zero.** Six items measure 340 dp into
the 333 dp row and wrap, orphaning the last one and leaving a dangling separator.
`rain 0%` is the one reading that carries nothing at zero, and the rain chart
below already states "no rain in 24 h". A non-zero chance is always shown.

On a future day, swap for that day's extremes: `tomorrow · peak 80°F · dew 70° ·
AQI 58 · rain 72%`.

**2. Temperature + dew point — 329 × 104.** One chart: they share a unit, and the
dew point is always below the temperature, so **no series labels** — position and
the two established hues identify them.

- y range = min/max of both series ±3, including the 78° cutoff.
- 3 gridlines on round multiples inside the range, labelled in the left gutter
  (mono 11, faint). Exactly three — four is too dense at this height.
- 78° comfort cutoff as a dashed rule (`5 4`), labelled `cutoff 78°` at the right
  edge in mono 9.
- Temperature: 2.4 dp solid, monotone-x smoothed. Dew point: 2 dp,
  `stroke-dasharray 1 3.4`.
- Day/night bands per §0.3; now-line 1.2 dp in heading ink.

**3. US AQI — a single solid bar, 329 × 10.** No axis, no columns.
Eyebrow row: `US AQI` (mono 11, faint) left, `peak 71 moderate` right in the
AQI-category text colour. Then an 8 dp bar, radius 4, filled with the §0.2
gradient across the 24 hours, 0.75 dp outline. Same treatment as the forecast
widget so the two surfaces read alike.

**4. Rain — 329 × 64.** Its own chart, its own axis (`100%` / `0` in the gutter).

- **Height = chance, width = intensity.** Column width =
  `slot × (0.26 + 0.7 × min(1, in_per_hr / rainScale))`; height = `chance/100 × 48`,
  min 2 dp. A tall thin column is likely drizzle; a tall fat one is a downpour.
- Clamp the column **centre** to the plot box, never the width — width is the
  encoding.
- Storm hours (`th ≥ thunderAlert`) take the storm hue; the peak column carries
  `72%  .42″` (mono 11) with the thunder glyph inline to its **left**, on the same
  line. Do not stack the glyph above the value — column height then determines
  their clearance.
- Rain-chance cutoff (20%) as a dashed rule.
- When a day has no rain: `no rain in 24 h`, mono 11, faint, centred.

**5. Shared time axis — 329 × 14.** The current clock (`11:42 AM`, mono 11 semibold,
heading ink) sits 3 dp right of the now-line. Plain hour ticks at 12 AM / 6 AM /
12 PM / 6 PM (mono 11, faint). **Suppress any tick whose measured box would
collide with the clock** — do not assume the gap is wide enough. Future days show
the four ticks and no clock.

---

## B. Windows-score widget (4×2) — REDESIGN

Layout, encoding and content are correct. Re-skin only:

- Card ground → theme card surface, radius 24, 10 dp padding. Follow system
  light/dark.
- Score bars, threshold rule, midnight dividers, now-line: colours from §0.1.
- All numbers and axis labels → IBM Plex Mono; the headline stays sans.
- The "win"/"ac" axis end-labels and the `60` threshold label keep their
  positions.
- Verify every bar colour against the new card ground at 3:1.

No geometry changes.

---

## C. Forecast widget (4×2) — NEW IMPLEMENTATION

A new provider and its own layout, placed alongside the existing graphs widget
(which stacks two or three configurable chart panels with no series labels,
~63 dp each, and is left untouched).

One question: **what will the weather do in the next 24 hours.** One panel, four
layers, each in a different visual register so nothing competes for the same
shape.

### C.1 Frame

392 × 186 dp. Radius 24. Padding **10** dp. Content box is **370 × 166**
(392 − 2×10 padding − 2×1 border). Header 40 + gap 3 + chart 123.

### C.2 Header — 40 dp, all four factors

**The hero reading IS the temperature factor.** State the temperature once.

```
[icon 26]  72°  Clear · Ridgewood          87° 64°
           DEW 51° · AIR 42 · RAIN 0%      [storm chip]
```

- Hero temperature: mono 25, letter-spacing −0.03em.
- `Clear · Ridgewood`: Instrument Sans 12, muted, ellipsis.
- **Second line carries the other three factors only** — never temperature again.
  Each label in its own hue (mono 11 semibold), value in body ink, ` · ` separated.
- **The limiting factor gets a marker pass**: a flat band covering the bottom 42%
  of the line (`orange-200` light / `#493B31` dark) behind that label+value pair.
  When temperature is the limiter, the **hero** takes the marker and no chip is
  emitted.
- hi/lo: mono 14 heading / 14 faint, right.
- Storm chip (only when `th ≥ thunderAlert`): pill, danger-soft fill, 13 dp
  thunder glyph + `62% 7AM` in the storm text colour.

### C.3 Chart — 370 × 123

Plot gutter x0 = **30** (temperature axis labels), x1 = **370**.

Layers, in paint order:

1. **Day/night bands** (§0.3) and midnight dividers, with the date label
   (`WED 6/17`, mono 9, faint) 4 dp right of each divider.
2. **3 gridlines** on round multiples, labelled in the left gutter (mono 9).
   Exactly three.
3. **Rain columns** — same chance × intensity encoding as A.4, 28 dp full height.
   Storm columns in the storm hue; marginal-thunder hours (`th ≥ 15`) get a
   0.9 dp storm-hue outline. Peak column labelled `72%  .42″` with the bolt inline
   to its left.
4. **Temperature** — filled area at 14% + 2.4 dp line. Its `TEMP` tag (mono 9,
   semibold, +0.1em) sits at the line's left end, starting at
   `max(x0 + 2, nowX + 5)` so the now-rule cannot strike through it. `DEW` tags
   the same way 13 dp below its line. 2 dp dashed dew line (`1 3.4`).
5. **AQI strip** — 4 dp gradient bar at y = 104, full plot width, 0.75 dp outline.
   Named `AIR` in the AQI text colour, right-aligned in the left gutter, vertically
   centred on the band. The strip's identity must not depend on the ramp's hue,
   which under the EPA palette collides with the temperature line.
6. **Now rule** — 1.5 dp in the theme accent, at the **real clock time**
   (`x = xAt(minutes/60)`), not at the first forecast hour. The gap between the
   plot edge and the rule is visible staleness.
7. **Time axis** — the clock (`11:42 AM`, mono 11 semibold) 3 dp right of the rule,
   then hour ticks, suppressing any that would collide.

**No per-point hi/lo marks on the curve.** The header pair covers it; marks made
the chart cluttered.

### C.4 Refresh

13 dp `ic_refresh` centred in a **28 dp** target at the chart's bottom-right,
inset −7 dp into the card padding. Faint ink. In the app (D) the same control sits
in the header beside the location.

### C.5 Settings

C gets its own configuration activity, keyed by widget id. Three questions and a
live preview of the actual widget:

1. **How far ahead** — 12 / **24** / 36 hours. Always starts at the current hour.
2. **Detail** — Compact / **Standard** / Everything. Compact drops the dew line and
   the AQI strip.
3. **Theme** — **Auto** / Light / Dark.

Plus two switches: *Hide rain when unlikely* (below 20%, on) and *Tap opens the
app* (off = refresh in place).

The graphs widget keeps its own settings activity unchanged; the two configs do
not share storage.

---

## D. Forecast app screen — NEW IMPLEMENTATION

C's encoding at phone scale. A new screen, reachable from the forecast widget's
tap target; it does **not** replace the windows screen.

- **Header** — place name (Piazzolla 22), `6 of 6 sources · 11:42 AM` (mono 12),
  refresh + settings at 32 dp.
- **Hero card** — C.2's header at phone scale: 42 dp glyph, mono 46 temperature
  with the same marker rule, the three other factors on one line at mono 12,
  hi/lo at mono 20, storm chip.
- **Chart** — C.3's four-layer panel at 329 × 214, plot gutter x0 = 36,
  x1 = 329. Same three gridlines, same rain and AQI encodings, same now rule.
  Because the panel is taller, `AIR 71` sits on its own baseline below the strip,
  right-aligned, inset 30 dp to clear the refresh corner.
- **Day rows** — one card per following day: label, day+night glyphs, the
  limiting factor at that day's **worst hour** in its hue, rain percentage,
  hi/lo, and a 26 dp temperature sparkline with the open-window runs marked.

**Selecting the day's limiting factor:** scan `severity` across every hour **and**
every factor and take the maximum. Do **not** read the limiter at the
lowest-scoring hour — the score is a product of ramps and saturates at 0 as soon
as one factor bottoms out, so every blocked hour ties and you get the first one
rather than the worst.

```
severity(x, full, zero) =
  0                             if x ≤ full
  (x - full) / (zero - full)    if full < x < zero
  1 + (x - zero)/(zero - full)  if x ≥ zero
```

Thresholds are the existing ones: temp 76/78, dew 60/65, AQI 100/150,
rain 20/70.

---

## 5. Shared config

Three values reach both C and D and the app drawer:

| Key | Default | Range |
|-----|---------|-------|
| `aqiPalette` | `EPA` | `EPA`, `Ethan`, `Ink` |
| `rainScale` — in/hr that draws a full-width column | `0.50` | 0.15 – 0.60 |
| `thunderAlert` — storm treatment above | `50%` | 10 – 70 |

`Ethan` is an OkLCH ramp (jade → amber → hazard, shorter hue path, which passes
through orange and red on its own); `Ink` is a neutral density ramp for
colour-blind use.

---

## 6. File map

| File | Action |
|------|--------|
| `MainActivity.kt` | Rewrite the forecast list + drawer (A). Delete the Score/Values toggle and `FactorChart`. |
| `AppTheme.kt` | Add the §0.1 data-colour set and the §0.3 chrome tokens, both themes. Follow system light/dark. |
| `Graph.kt` | Re-colour + re-type only (B). |
| `AcWindowsProvider.kt`, `widget_ac_windows.xml` | Re-skin (B). |
| `PanelGraph.kt`, `widget_graphs.xml`, `GraphsWidgetProvider.kt`, `GraphsWidgetConfig.kt`, `GraphsWidgetSettingsActivity.kt` | **Unchanged.** The graphs widget keeps shipping as-is. |
| new: `ForecastWidgetProvider.kt`, `ForecastPanel.kt`, `widget_forecast.xml`, `res/xml/forecast_widget_info.xml` | C. Register a second `<receiver>` in the manifest; share `AcWindowsProvider`'s alarm tick as the graphs widget does. |
| new: `ForecastWidgetConfig.kt`, `ForecastWidgetSettingsActivity.kt` | §C.5 three-question config, its own SharedPreferences keys. |
| `HourlyRow.kt` | Delete if unreferenced after A removes the Values charts. |
| new: forecast screen | D. |
| `ic_wx_thunder.xml` | Now used for storm hours — wire it up; it is currently vendored but unreachable. |

## 7. Known traps

1. **Content-box arithmetic** — subtract padding *and* the 1 dp border on each
   side. 392 − 20 − 2 = 370, not 372.
2. **`xAt` is a point scale** — hours 0 and n−1 land on the plot edges, so a
   centred mark loses half its width there. Clamp centres, not widths.
3. **The 6% band tint inverts between themes** — tint the night half in light and
   the day half in dark.
4. **Never plot the raw score below its threshold** — it saturates at 0. Use
   severity anywhere magnitude matters below the cutoff.
5. **Measure label boxes before placing them.** Every collision this design hit
   came from assuming a gap was wide enough at a scale where it wasn't.
6. **Two widgets now draw from the same cache.** C must not mutate anything
   `GraphsWidgetProvider` reads, and its config keys must not collide with the
   graphs widget's.
