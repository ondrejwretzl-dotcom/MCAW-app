# MCAW Baselines

- `approved_latest.txt` obsahuje cestu na aktuálně schválený baseline `summary.json`.
- Schválené baseline ukládejte immutable, ideálně pod `/<catalog>/<id>/summary.json`.
- Doporučený `id` je commit SHA nebo timestamp.

Promote (lokálně/CI):

```bash
scripts/mcaw/promote_baseline.sh <path_to_summary.json> <baseline_id> [catalog]
```
