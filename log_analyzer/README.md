# MCAW Log Analyzer

Robustní webové „kukátko“ pro MCAW logy s tolerancí na odlišná schémata, celologovým KPI dashboardem a segmentovou analýzou podle objectId.

## Co umí
- načítat libovolné `.txt/.csv/.log` soubory (bez omezení na konkrétní datum),
- detekovat známé i neznámé struktury řádků,
- při nekompatibilitě pokračovat částečným načtením a zobrazit warnings,
- zobrazit `extraFields` (sloupce navíc mimo známý kontrakt),
- Data quality panel (`valid`, `partial`, `rejected` + důvody),
- closable detail události (tlačítko, ESC, klik mimo),
- KPI dashboard:
  - REL quality (invalid ratio + breakdown důvodů po bitech),
  - false-red rate when bottom touch=true (before/after split),
  - switch beneficial rate (TTC variance before/after 2s),
  - standing suppressor missed-critical near stop (z manuálních tagů),
- explicitní rozlišení tracker lock ID vs finální target ID ve výstupech,
- Object Segments panel s grafy (TTC/REL/dist/speed) a markery (orange/red/switch/bottom-touch/suppress).

## Definice KPI
- **REL invalid ratio** = `invalid_rel_samples / relevant_rel_samples`.
- **false-red** = `level=RED & bottomTouch=true & !(ttc_decreasing || dist_decreasing) v následujícím 2s okně`.
- **switch beneficial** = po switchi je variace TTC ve +2s okně menší než v -2s okně.
- **missed-critical near stop** = tag `near_stop_critical` + standing suppress aktivní + v okně nevznikl RED.

## Manuální tagy scén
Podporovaný import:
- JSON: pole objektů `{ id, tsStart, tsEnd, kind, note }`
- CSV: hlavička `id,tsStart,tsEnd,kind,note`

`kind` použijte `near_stop_critical` pro KPI standing suppressor.

## Start
```bash
cd log_analyzer
npm install
npm run dev
```

## Testy
```bash
npm run test
```
