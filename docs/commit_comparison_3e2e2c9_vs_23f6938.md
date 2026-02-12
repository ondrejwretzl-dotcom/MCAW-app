# Analýza rozdílů: `3e2e2c9` (stray) vs `23f6938` (aktuální)

## Kontekst a rozsah
Tato analýza je zaměřena primárně na změny, které mají dopad na:
- **kvalitu detekce** (precision/recall v jízdním pruhu),
- **výpočet rizikových parametrů** (zejména TTC, vzdálenostní/speed prahy),
- **stabilitu varování** (false positives / false negatives v alertingu).

Mimo scope jsou čistě UI změny, pokud nemají nepřímý dopad na detekční pipeline.

---

## 1) Největší změna v detekční pipeline: obdélníkové ROI -> symetrický trapezoid ROI

### Co se změnilo
V aktuálním commitu (`23f6938`) je původní obdélníkové ROI nahrazeno **trapezoidem**:
- detektor se stále spouští na **cropu bounding rectu** ROI,
- ale výsledné boxy jsou následně tvrdě gateované podle **poměru průniku boxu s trapezoidem** (`>= 0.80`).

Zavedeno v `DetectionAnalyzer.kt`:
- `roiTrapezoidPx(...)` a výpočet trapezoid bodů + bounds pro crop,
- `containmentRatioInTrapezoid(...)` (polygon clipping) místo `insideRatio(...)` proti rect ROI,
- gate: `containmentRatioInTrapezoid(d.box, roiTrap.pts) >= 0.80f`.

### Dopad na kvalitu detekce
**Pozitivní (většinově):**
- Lepší geometrická shoda s perspektivou jízdního pruhu (užší nahoře, širší dole).
- Menší počet objektů z vedlejších pruhů / krajnice v horní části obrazu.
- Typicky nižší false-positive rate mimo ego-pruh.

**Rizika / trade-off:**
- Tvrdý práh 0.80 může odfiltrovat relevantní objekt na hraně ROI (např. při zatáčení, parciální překryv).
- Polygon clipping je výpočetně dražší než průnik s rectem (ale u nízkého počtu boxů je dopad v praxi obvykle malý).

### Hodnocení
Pro ADAS-like use-case je tato změna **architektonicky správná** a je pravděpodobně lepší než původní rect ROI, pokud je ROI správně naladěné na FOV/kameru.

---

## 2) Změna výchozích risk prahů (TTC / distance)

V aktuální verzi se mění fallback/default alert prahy:
- `TTC red`: **1.2 -> 1.5 s**,
- `Dist orange`: **15 -> 16 m**,
- `Dist red`: **6 -> 9 m**.

A stejné defaulty jsou promítnuty i do `AppPreferences` (`userTtcRed`, `userDistOrange`, `userDistRed`).

### Dopad na výpočet rizika
- S vyšším `TTC red` a vyšším `Dist red` bude systém přecházet do red alertu **dříve** (konzervativněji).
- Prakticky to zvyšuje bezpečnostní rezervu, ale může zvýšit četnost red alertů při městské jízdě.

### Hodnocení
- Pokud je cílem **vyšší bezpečnostní konzervativnost**, aktuální verze je lepší.
- Pokud je cílem minimalizace alert fatigue, starší verze může být subjektivně klidnější.

---

## 3) Brake cue heuristika: rozšíření analyzované oblasti v boxu

V `DetectionAnalyzer.kt` došlo ke změně ROI uvnitř detekovaného vozidla pro analýzu brzdových světel:
- horní hranice analyzované části boxu: z cca **spodních 30 %** na **spodních 60 %** boxu (`0.30 -> 0.60`).

### Dopad
**Pozitivní:**
- vyšší robustnost na variabilní polohu lamp (zejm. SUV/hatchback),
- menší riziko, že lampy vypadnou z analyzované sub-ROI.

**Negativní:**
- větší oblast = víc „šumu“ (odlesky, světla okolí),
- potenciálně vyšší false positives brake cue, pokud není dostatečně přísná barevná/intenziční filtrace.

### Hodnocení
Je to potenciálně lepší recall brake cue, ale doporučeno ověřit na nočních datech a dešti.

---

## 4) Výchozí behavior alertingu (audio/voice) – nepřímý dopad na validaci detekce

Aktuální verze přidává per-level routing (orange/red) pro zvuk i TTS a mění default:
- `voice` default z `true` na `false`,
- granularita `soundOrange/soundRed`, `voiceOrange/voiceRed`, custom TTS texty.

### Dopad na detekční kvalitu
Přímo neovlivňuje detektor/TTC, ale:
- může ovlivnit subjektivní vnímání kvality (uživatel slyší méně / jiné alerty),
- může ztížit field debugging, pokud je voice defaultně OFF.

---

## 5) Shrnutí „co je lepší kde“

## Lepší v `23f6938` (aktuální)
1. **ROI modelování pruhu** (trapezoid + polygon gate) – lepší geometrická relevance pro ego-pruh.
2. **Konzervativnější red risk profil** (TTC/dist) – dřívější kritická varování.
3. **Brake cue coverage** (větší spodní oblast boxu) – vyšší šance zachytit brzdová světla.

## Potenciálně lepší ve `3e2e2c9` (stray)
1. **Jednodušší ROI gating** (rect intersection) – menší riziko hraničních false negatives.
2. **Méně konzervativní red prahy** – menší alert fatigue v hustém provozu.
3. **Nižší výpočetní složitost ROI průniku**.

---

## Doporučení senior analytika / Android detekce

### A) Co bych ponechal z aktuální verze
- Ponechat **trapezoid ROI** jako nový baseline.
- Ponechat možnost editace ROI (důležité pro kalibraci na různá zařízení/FOV).

### B) Co bych upravil před mergem
1. **A/B přepínač gate prahu** pro trapezoid containment (např. 0.65 / 0.80), logovat metriky.
2. **Fallback režim**: při nízké důvěře/track instabilitě dočasně snížit containment práh.
3. **Brake cue validace**: ověřit FPR v noci/dešti; případně adaptivně škálovat analyzovanou vertikální oblast dle velikosti boxu.
4. **Risk profil**: ponechat nové konzervativní defaulty, ale nabídnout profil „city“ vs „highway“.

### C) Praktický závěr pro merge rozhodnutí
- **Primární doporučení:** jako základ vzít `23f6938` (aktuální), protože má lepší strukturální změnu ROI, která je pro kvalitu detekce klíčová.
- **Podmínka:** doplnit jemné ladění gate prahu + validační jízdy (day/night/rain) pro potvrzení, že se nezvyšují hraniční false negatives.

