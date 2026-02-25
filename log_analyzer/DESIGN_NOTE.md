# Log Analyzer design note

## Data model
- `ParsedLogData` drží odděleně `serviceRows`, `riskRows`, `metricsRows`, `unknownRows`.
- Každý parsovaný řádek nese `source`, `rawColumns` a `extraFields` pro audit.
- `EventGroup` je časové okno s agregací risk/metrics/service dat.
- KPI se počítají nad celým logem (`kpi/metrics.ts`) a segmenty v `kpi/segments.ts`.

## Assumptions
- Log je časově monotónní nebo blízko monotónní; agregace se dělá podle `ts`.
- Pokud #74 split není zřejmý, je možné ručně zadat split timestamp nebo file prefix.
- U části KPI jsou použity transparentní heuristiky (viz README), protože log nemusí nést kompletní ground-truth.

## Limitace
- Bez externího ground truth nelze false-red určit absolutně přesně; používá se konzistentní interní proxy.
- Některé model-specific bity mohou mít odlišný význam mezi verzemi aplikace.
- Segmentace objectId je best-effort podle lock/final target polí dostupných v logu.
