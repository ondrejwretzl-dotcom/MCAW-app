# Refactor notes (App.tsx split)

This patch splits the previous monolithic `sim/src/ui/App.tsx` into small components and `lib/*` helpers.

## What to do

1) Replace `sim/src/ui/App.tsx` with the new version.
2) Add the new files under:
   - `sim/src/ui/components/*`
   - `sim/src/ui/lib/*`
3) Keep your existing folders:
   - `sim/src/io/*`
   - `sim/src/engine/*`
   - `sim/src/charts/*`

## Validate locally

Run:

- `npm run dev`

Optional (recommended):

- `npm run build`
- `npm run preview`

If you want linting, add ESLint and run:

- `npx eslint src --ext .ts,.tsx`

## Features preserved / included

- Upload `.frames.jsonl` (multi) + optional `.md` notes, scenario switch
- What-if overlay (dynamic distance time-gap + clamps)
- Plots: risk + optional input plot
- Table with show-only-diffs for what-if
- Builder: profiles (hold/linear/accel speed), templates, scenario JSON import/export, scenario MD export
- Warnings panel (guardrails)
