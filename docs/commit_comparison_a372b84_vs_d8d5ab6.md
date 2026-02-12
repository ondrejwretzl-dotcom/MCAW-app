# Analýza rozdílů: `a372b84` (starý) vs `d8d5ab6` (aktuální)

## Kontext a cíl
Analýza je zaměřena na změny s dopadem na:
- **kvalitu detekce** (false positives/false negatives, stabilita tracků),
- **výpočet rizikových parametrů** (zejména TTC a výsledný alert level),
- **výkon aplikace** (CPU/Broadcast load, latence, jitter),
- **použitelnost v reálné jízdě** (stabilita alertů, laditelnost v Settings).

Porovnání vychází z diffu mezi commity:
- starý: `a372b8448818dfc1ff802487f0b7710dc195438d`
- aktuální: `d8d5ab6d938e080f638d648d4d942f2de98b2f64`

---

## 1) Kvalita detekce a robustnost alertingu

## 1.1 ROI containment: hard-coded 0.80 -> přepínatelné 0.65/0.80
### Co se změnilo
V aktuální verzi už není containment práh natvrdo `0.80`, ale čte se z `AppPreferences.roiContainmentThreshold()`. Současně přibyl přepínač `roiStrictContainment`.

- `strict=true`  => `0.80`
- `strict=false` => `0.65` (default)

### Dopad
**Aktuální verze (`d8d5ab6`) je lepší pro praktickou detekci**, protože:
- výrazně snižuje riziko hraničních FN (objekt částečně v ROI) při zatáčení/perspektivních deformacích,
- dává operátorovi tuning bez změny kódu,
- umožňuje provozní kompromis: „precision mode“ (0.80) vs „recall mode“ (0.65).

**Riziko:** 0.65 může pustit více objektů blízko hran ROI => potenciálně vyšší FP mimo ego-pruh.

**Verdikt:** lepší je **aktuální verze**, protože je adaptivní a provozně řiditelná.

---

## 1.2 TemporalTracker: minConsecutiveForAlert 3 -> 2
### Co se změnilo
Tracker i analyzer používají `minConsecutiveForAlert = 2` místo 3.

### Dopad
- **Pozitivní:** rychlejší náběh potvrzeného tracku/alertu (nižší latence varování), lepší pro rychlé closing scénáře.
- **Negativní:** vyšší citlivost na krátké artefakty (potenciálně více krátkých FP), zejména v horším světle.

**Verdikt:**
- pro bezpečnostně orientovaný systém je **aktuální verze lepší** (nižší time-to-alert),
- pro maximální klid UI by byla stará verze konzervativnější.

---

## 1.3 TTC hysteréze (nově)
### Co se změnilo
TTC složka alertingu dostala hysterézi (`ttcLevelWithHysteresis`):
- oddělené „on/off“ prahy pro red/orange,
- paměť posledního TTC levelu (`lastTtcLevel`).

### Dopad
To je **zásadní kvalitativní zlepšení**:
- výrazně menší „cvakání“ mezi ORANGE/RED při TTC kolem prahu,
- stabilnější UX i audio/TTS,
- nižší kognitivní zátěž uživatele bez ztráty citlivosti na skutečné zhoršení.

**Verdikt:** jednoznačně lepší **aktuální verze**.

---

## 1.4 Gating při nízké/unknown rychlosti jezdce
### Co se změnilo
Dřívější explicitní validace zdroje rychlosti (`UNKNOWN`, confidence, stáří vzorku) byla odstraněna z gatingu. Nově se alerty vypínají jen při `riderStanding` definovaném čistě přes numerickou rychlost `<2 km/h` (pokud je finite).

### Dopad
- **Pozitivní:** systém je méně náchylný na „silent failure“ při výpadku speed source; když rychlost není known/finite, alerty mohou běžet v degradovaném módu podle TTC/distance/approach.
- **Negativní:** ztrácí se explicitní kvalita-vstupu (confidence/stáří) v rozhodování o stationarity, což může být v některých edge-case méně deterministické.

**Verdikt:** pro bezpečnost v provozu je obvykle lepší **aktuální verze** (fail-operational chování), ale doporučuji doplnit diagnostický indikátor kvality rychlosti do debug metrik.

---

## 2) Výkon a runtime efektivita

## 2.1 Throttling broadcastů (metrics + overlay)
### Co se změnilo
Aktuální verze zavádí časové throttlingy:
- metrics cca 12.5 Hz (`80 ms`) + okamžité odeslání při změně levelu,
- debug overlay cca 12.5 Hz + okamžité odeslání při změně levelu,
- clear zprávy jsou `force=true`.

### Dopad
- menší IPC/Broadcast zatížení,
- menší šance na backlog/lag UI vrstvy,
- stabilnější frame processing při zapnutém debug overlay.

**Verdikt:** výrazně lepší **aktuální verze**.

---

## 2.2 Log sampling (debug)
### Co se změnilo
`flog()` loguje jen každý N-tý frame (`logEveryNFrames = 10`) pokud není `force`.

### Dopad
- nižší I/O overhead,
- menší riziko, že debug logging degraduje FPS analyzátoru.

**Verdikt:** lepší **aktuální verze** (hlavně při delších jízdách se zapnutým debugem).

---

## 3) Použitelnost a konfigurace

## 3.1 Nový settings přepínač „Přísné držení v ROI“
### Co se změnilo
Do Settings UI přibyl switch pro ROI strict containment a navázání na `AppPreferences`.

### Dopad
- výrazně lepší field tuning bez rebuildu,
- lepší servisovatelnost při odlišných kamerách/FOV,
- srozumitelný trade-off precision vs recall pro testera.

**Verdikt:** lepší **aktuální verze**.

---

## 4) Celkové porovnání „co je lepší kde a proč"

## Lepší v `d8d5ab6` (aktuální)
1. **Stabilita alert levelů** díky TTC hysterézi (méně oscilací kolem prahu).
2. **Nižší runtime overhead** díky throttlingu metrics/overlay + sampling logů.
3. **Rychlejší reakce** díky `minConsecutiveForAlert=2`.
4. **Vyšší provozní použitelnost** díky ROI strict switchi (0.65/0.80).
5. **Fail-operational alerting** i při neideální dostupnosti rychlosti.

## Potenciálně lepší v `a372b84` (starý)
1. Konzervativnější potvrzení tracku (`3` po sobě) = méně krátkých FP artefaktů.
2. Přísnější/explicitnější validace speed source (confidence + stáří) v gatingu stationarity.

---

## Senior doporučení (analytik + Android detekce)
1. **Jako baseline ponechat `d8d5ab6`** – přínos TTC hysteréze a výkonových optimalizací je vyšší než rizika.
2. **Doplnit metriku kvality rychlosti** (source/confidence/age) do debug streamu, aby fail-operational režim byl auditovatelný.
3. **A/B validace ROI 0.65 vs 0.80** na den/noc/déšť:
   - měřit FP/min, FN na lead vozidlo, time-to-alert, stabilitu levelu.
4. Pokud se v terénu objeví „nervózní“ alerty, zvážit adaptivní `minConsecutiveForAlert` podle confidence tracku.

### Finální doporučení
Pro produkčnější chování je **lepší aktuální commit `d8d5ab6`**, protože kombinuje:
- lepší stabilitu rozhodování (TTC hysteréze),
- lepší výkon při stejném detekčním jádru,
- lepší nastavitelnost v UI.

Starý `a372b84` dává smysl jen pokud je prioritou absolutně konzervativní anti-FP chování bez důrazu na rychlost reakce.
