# Methodology: the Open-Window Score

The app answers one question for each hour of the forecast: is the air outside better
than what the air conditioner would give you? This page is the reasoning behind the
number. Every threshold named here is a setting; the defaults are in brackets.

## The score

```
score = 100 × f_temp × f_humid × f_aqi × f_rain
```

Each factor is 1.0 inside its comfortable band and falls in a straight line to 0.0 at its
cutoff. An hour is worth opening the windows for when the score clears the open
threshold [60].

| Factor | Full score | Zero | Why |
|---|---|---|---|
| Temperature | from "worth opening down to" [55°F] up to "comfortable up to" [76°F] | at or above the cooling limit [78°F], at or below "too cold" [40°F] | Ventilation cannot cool a house below the outdoor temperature, and below the cold floor an open window only dumps heat. |
| Humidity (dew point) | from [45°F] up to [60°F] | at or above "too muggy" [65°F], at or below "too dry" [40°F] | Opening up imports moisture the AC would have removed, or air dry enough to pull the house below a comfortable humidity. |
| Air quality | AQI up to [100] | AQI at or above [150] | Smoke and smog make ventilation a bad idea on an otherwise perfect evening. |
| Rain | chance up to [20%] | chance at or above [70%] | Don't invite rain in. |

## Why the factors multiply

Smoke, rain, mugginess and "it is hotter outside than in" can each rule out an open
window alone. A weighted sum would let a perfect temperature compensate for hazardous
air and still report a decent score, which is exactly the wrong call. Multiplying makes
every factor a gate, and leaves nothing to tune between them.

## Why dew point, not relative humidity

Relative humidity moves with temperature: 70% at dawn and 35% at noon can be the same
air. Dew point measures the moisture itself, so it tracks how the air will feel.

| Dew point | Feel |
|---|---|
| 55°F or lower | dry, pleasant |
| about 60°F | getting sticky |
| 65°F and up | oppressive |

## The economizer analogy

Commercial HVAC uses a differential-enthalpy economizer: pull in outside air for free
cooling exactly when its total heat content is below the indoor air's, because warm
moist air is expensive to cool ([Telker, *Dry Bulb or Enthalpy Economizer?*][econ]).
Enthalpy folds temperature and humidity into one quantity. The temperature and humidity
factors together are an interpretable stand-in for that comparison.

## Cold air is a lockout, not a heating mode

An open window moves indoor air toward the outdoor air, so it warms a house only when
outside is already the warmer side. Solar gain works the other way: sunlight passes the
glass, and the heat it becomes stays inside only while the window is shut
([YourHome, *Passive heating*][heat]; [NREL, *Passive Solar Design*][nrel]).

The cold floor is not a heating setpoint. Night flushing is useful because outdoor air
is cooler than the room you want to wake up to, so a full score runs down through the
mid-50s, and a 50°F hour still clears the default threshold.

## What was left out

| Signal | Decision | Why |
|---|---|---|
| Sun and cloud cover | shown, not scored | Solar load depends on window area, orientation and glazing, which the app does not know, and the temperature forecast already includes the sun's effect on the air. An earlier solar term turned out to be a noisy proxy for temperature. |
| Wind | shown, not scored | A calm, cool night is still a good night to open up; a wind factor would punish it. |
| Apparent temperature | shown, not scored | It blends temperature and humidity, which the score keeps separate on purpose. |
| Adaptive comfort ([ASHRAE 55][a55]) | not built | The comfort band would slide with the past week's weather. It changes what a fixed setpoint means, so it would have to be opt-in. |
| Indoor sensors | not built | A real indoor temperature would sharpen every call and make solar modelling worthwhile. |
| Pollen | not built | Would be one more gate for allergy season. |

The value of cool overnight hours follows the night-flush literature: cooling the
building's thermal mass at night pays off the next day ([Artmann et al.][ccp];
[Givoni][mass]; [WindowMaster, *Night flushing*][flush]).

## Combining sources

Each source reports independently and failures are isolated: one that errors or times
out is listed with its error and left out. The consensus for each field is a weighted
median, which a single outlier cannot move, and the confidence shown beside it falls
when the sources disagree.

[econ]: https://www.linkedin.com/pulse/dry-bulb-enthalpy-economizer-brad-telker
[flush]: https://www.windowmaster.com/expertise/natural-ventilation-and-mixed-mode-ventilation/night-flushing/
[mass]: https://www.sciencedirect.com/science/article/abs/pii/S0960148101000271
[ccp]: https://www.sciencedirect.com/science/article/abs/pii/S0306261906000766
[a55]: https://comfort.cbe.berkeley.edu/
[heat]: https://www.yourhome.gov.au/passive-design/passive-heating
[nrel]: https://www.nrel.gov/docs/fy01osti/27954.pdf
