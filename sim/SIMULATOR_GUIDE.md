# MCAW RiskEngine Simulator – Guide

This web tool is an **offline** simulator for MCAW’s RiskEngine behavior. It can:
- load `*.frames.jsonl` (per-frame traces from tests) and overlay baseline vs what‑if,
- build new scenarios in the **Scenario Builder**,
- export results as **CSV** for further analysis.

## Units and conventions

- **Speed in UI:** km/h  
- **Speed in engine:** m/s  
  The simulator converts internally: `m/s = km/h ÷ 3.6`.

- **Distance:** meters (m)
- **Relative speed / closing (`rel`):** meters per second (m/s)  
  Positive `rel` means **closing** (distance decreases). Negative `rel` means opening.
- **TTC:** seconds (s)

## Key inputs

### rider speed
Driver/ego speed. In dynamic-distance mode, distance thresholds depend on speed:
- `distOrange(t) = clamp(speed(t) * orangeGap, clampOrangeMin, clampOrangeMax)`
- `distRed(t) = clamp(speed(t) * redGap, clampRedMin, clampRedMax)`

**Typical values:**  
CITY 0–60 km/h, highway 80–140 km/h.  
**Noise:** real driving often fluctuates ±1–3 km/h; the simulator supports deterministic noise with a seed.

### distance (m)
Estimated distance to the leading object. In the RiskEngine, distance typically has **lower weight** than TTC/rel, but it is an important confirmation signal.

### rel (m/s)
Closing speed (approach speed). In RiskEngine this is a primary “closing evidence” input.

**Rules of thumb:**
- 0–1 m/s: gentle closing
- 3–5 m/s: notable closing (often ORANGE evidence)
- 6–10 m/s: strong closing (often pushes toward RED, if TTC also confirms)

### TTC
Time‑to‑collision. You can drive it in two ways:
- `explicit`: you set a TTC profile directly
- `derived(dist/rel)`: TTC is computed as `distance / rel`

**Important:** in derived mode, `rel` must not be near 0 (or TTC becomes huge/unstable).

### slope (TTC slope)
Some scenarios include TTC trend (slope). If present in frames, it helps confirm rapid deterioration.

### ROI containment (0..1)
How much of the object is inside ROI. Treated as a weight factor, not a hard gate.

- 1.0 = fully in ROI
- 0.5 = partial / borderline

### qualityWeight (0.6..1.0)
Lower quality reduces confidence. In MCAW the minimum clamp is often around 0.60.

## Engine behavior concepts

### EMA (Exponential Moving Average)
Risk is smoothed to avoid jitter and “state flicker”.
- When risk rises, EMA follows with a “rise alpha”
- When risk falls, EMA decays with a “fall alpha”

This makes warnings **stable** and prevents rapid ORANGE/RED toggling.

### Hysteresis thresholds
Instead of a single threshold, there are ON/OFF thresholds:
- ORANGE: `orangeOn` / `orangeOff`
- RED: `redOn` / `redOff`

This reduces alert “chatter”.

## Scenario Builder

### Profiles
Many fields can be defined per segment as:
- `hold`: constant
- `linear`: from → to over the segment
- `accel` (speed only): start + accel (km/h per second)

### REL-driven distance (recommended)
In **REL-driven** mode, distance is integrated from rel:
- distance decreases by `rel * dt` each frame.
- Distance profile is used mainly for the initial value.

This matches how closing dynamics are reasoned about in MCAW.

### Templates
Use templates as starting points:
- C2 closing (city jam approach)
- Highway follow
- Cut-in
- Ego braking

## What-if mode

Enable **dynamic distance** and tune:
- `orangeGap` (default 2.0 s)
- `redGap` (default 1.2 s)
- clamp ranges in meters

The simulator overlays baseline vs what‑if in:
- risk curves
- alert levels
- first ORANGE/RED times

## CSV export
Use **Download CSV** to export per-frame data:
- inputs (speed, dist, rel, TTC, ROI, quality…)
- baseline outputs
- what‑if outputs
- derived thresholds for what‑if

This is the best artifact for discussing tuning decisions.

## Common pitfalls
- `derived TTC` with `rel ≈ 0` → TTC becomes huge, risk stays SAFE.
- unrealistic dist/rel combination (distance falling fast but rel small) → warnings will show mismatch.
- `orangeGap <= redGap` → invalid (orange must be less severe than red).
- clamp min >= max → invalid.

