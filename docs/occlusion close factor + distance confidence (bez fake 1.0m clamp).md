# MCAW 2.0 – Varianta 3 (occlusion close + distance confidence)

Datum: 2026-03-01

## Cíl
- Odstranit riziko „fake“ distance fixace (typicky ~1.001 m) při bottom okluzi.
- Zamezit tomu, aby nevalidní/nejistá distance (při okluzi) nesprávně ovlivňovala risk (steady-gap suppress / receding) a zároveň **neztratit** možnost včas varovat (ORANGE/RED) v near-crash situacích.
- Zachovat filozofii MCAW 2.0: plynulé váhy, auditovatelný reason kontrakt, O(1) bez alokací.

## Změněné soubory
1) `app/src/main/java/com/mcaw/risk/RiskEngine.kt`
2) `app/src/main/java/com/mcaw/ai/DetectionAnalyzer.kt`
3) `sim/src/engine/RiskEngine.ts`

## Co se změnilo
### 1) RiskEngine (Android)
- Přidán parametr `distanceConfidence` (0..1) do `evaluate()` – default 1.0 (zpětná kompatibilita pro existující volání).
- `distScore` se nyní váží `distanceConfidence` (žádný hard gate).
- Implementován `occlusionBoost` z dvojice vstupů `occlusionCloseFactor` + `occlusionCloseEligible`.
  - Boost je konzervativní a omezený (max ~0.14), aby nebyl dominantní.
  - Pro RED combo guard přidán `strongOcclClose` (jen při eligible a vysokém faktoru).
- Reason kontrakt: využit existující bit `BIT_BOTTOM_OCCLUDED_CLOSE` (nastaví se při eligible a `occlF>=0.60`).

### 2) DetectionAnalyzer (Android)
- Při bottom okluzi:
  - Krátké okno extrapolace (do 800 ms) zůstává, ale **bez clampu na 1.0 m** (jen technická podlaha 0.30 m).
  - Po okně extrapolace se místo „hold + decay“ preferuje aktuální aproximace `distanceCandidate` nebo `distCropBound`.
  - Přidány interní `DIST_SOURCE_OCCL_EXTRAP` / `DIST_SOURCE_OCCL_BOUND` pro debug/log.
  - `distConf` se při occlusion fallbacku stahuje dolů (0.25 / 0.35), aby RiskEngine mohl distance správně down-weightnout.
- Dopočítán `occlusionCloseFactor` z konzervativního minima: `min(distCropBound, distGroundPred, distanceCandidate)`.
- Definováno `occlusionCloseEligible` (stabilní okluze + close>0 + není hard suppress + je nějaká indikace closing: approach/brake/egoBrake).
- Předáno do `riskEngine.evaluate()`:
  - `distanceConfidence = distConf`
  - `occlusionCloseFactor` + `occlusionCloseEligible`

### 3) Simulator (TS)
- Zrcadlení změn z Androidu pro konzistenci:
  - Přidán `distanceConfidence?: number` do `EvaluateInput`.
  - `distScore` vážen `distanceConfidence`.
  - Implementován `occlusionBoost`, `strongOcclClose` a reason bit `BIT_BOTTOM_OCCLUDED_CLOSE`.

## Poznámky k dopadu
- **Preview/UI:** žádné úpravy.
- **Log_analyzer:** schéma `METRICS2` se nemění (používá se stávající `distConf`).
- **Regresní testy:** RiskEngine API je zpětně kompatibilní díky default `distanceConfidence=1f`. Chování bez okluze by mělo zůstat stejné.

## Rizika / co hlídat při validaci
- Pokud `distCropBound` nebo `distGroundPred` mají chybnou kalibraci (pitch/height), může se `occlusionCloseFactor` chovat agresivně.
  - `occlusionCloseEligible` je proto gated i indikací closing (approach / brake / egoBrake) a vypíná se při hard-suppress stavech.
- Ověřit na videích, že:
  - mizí „zamrznutí“ na ~1.0 m
  - ORANGE/RED se neobjevuje v koloně (steady-gap hard suppress)
  - near-crash případy při okluzi dostanou včas ORANGE/RED
