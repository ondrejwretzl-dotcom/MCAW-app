# Analýza změn kódu od commitu `d013cff4892d55520d46c81c19390531ac282e09`

## 1) Co bylo analyzováno

Rozsah analýzy pokrývá commity od `d013cff` (exclusive) po aktuální HEAD (`81b11a8`), tedy:

1. `7cde65d` – Harden standing gate stability and align egoOffset normalization to 0..1 (#73)
2. `c8de07c` – Stabilize rel derivative inputs and gate invalid rel display (#74)
3. `d9ec595` – Logy z testovani 24 a 25 unor
4. `f33ec45` – Stabilize target identity logging and overlay label behavior (#75)
5. `81b11a8` – Fix tracker switch hysteresis to keep high-score switches reachable (#76)

---

## 2) End-to-end flow: od detekce objektu po metriky a alert

Níže je aktuální reálný tok dat v aplikaci (Android část), včetně míst, kde se do něj promítly změny od `d013cff`.

### Krok A — Vstup snímku, ROI a detekce

1. Přijde frame z kamery.
2. Spočítá se ROI trapéz (`roiTrapezoidPx`) a z něj se vezme **spodní hrana ROI**.
3. Do detektoru jde ořez (`roiRect`) jen do spodní hranice ROI (dashboard/kapota se odřízne).
4. Detekce se post-processují (`DetectionPostProcessor`) a trackují (`TemporalTracker`).

**Důležité:** ROI zde funguje hlavně jako „oblast zájmu“ a výkonnostní filtr. Boxy se dál neřežou natvrdo na celý ROI trapéz; kritická je hlavně spodní hrana (bottom). To je zásadní kvůli jevu „objekt se dotýká ROI bottom“. 

### Krok B — Výběr cílového objektu (target lock)

1. Tracker drží `lockedTrackId`.
2. Pokud se objeví lepší kandidát, nepřepne se hned — používá se hystereze (`switchMargin`, `switchConfirmFrames`, lock age).
3. Od #76 se hystereze škáluje podle kvality aktuálně zamčeného tracku (`effectiveExtra = extra * (1 - lockedScore)`), aby se systém nezasekl na starém locku, když oba skóry naráží na strop 1.0.

### Krok C — Ošetření ROI-bottom dotyku / okluze

Po výběru targetu se počítá, zda spodní část boxu „leží“ na spodním ořezu ROI (`bottomOccluded`).

- Pokud ano, metriky založené na spodku boxu (hlavně ground-plane distance a TTC z růstu boxu) mohou být zkreslené.
- Proto se:
  - ground-plane distance vypíná,
  - místo toho se používá konzervativní limit `distCropBound` (minimální realistická vzdálenost na úrovni spodního ořezu),
  - invalidují se derivace REL při změně stavu okluze.

To je přesně mechanismus, který brání falešným výkyvům při „dotyku objektu ROI bottom“.

### Krok D — Výpočet vzdálenosti (DIST)

1. Spočte se `distFromHeight` (z výšky bboxu).
2. Pokud není okluze, spočte se i `distFromGround` (geometrie přes spodní hranu boxu + mount/pitch kamery).
3. `distanceRaw` je blend obou metod podle toho, jak nízko ve frame objekt je (`wGround`).
4. Aplikuje se kalibrační škála (`distanceScale`).
5. Stabilizace vstupu (`stabilizeDistanceInput`) hlídá skoky a rychlost změny.
6. Nakonec EMA vyhlazení `smoothDistance` => `distanceM`.

### Krok E — Výpočet REL (relativní rychlost přibližování)

1. REL je derivace vzdálenosti v čase: `(prevDistance - distanceNow) / dt`.
2. Derivace je validní jen pokud jsou splněné guardy (`relDerivValid`), jinak se maskuje (`relInvalidReasonMask`).
3. Důvody invalidace (#74):
   - switch ID,
   - ztráta/reacquire tracku,
   - změna okluze,
   - skok distance,
   - změna dostupnosti rider speed.
4. Validní REL jde přes EMA (`relSignedEmaMps`) a kladná část do `approachEmaMps`.

### Krok F — Výpočet TTC (v zadání „TCC“)

Aplikace používá TTC ze dvou zdrojů:

1. `ttcFromHeights` — z růstu výšky bboxu (když není bottom occlusion).
2. `ttcFromDist` — `distance / approachSpeed`.

Pak se zdroje blendují (85 % height + 15 % dist), následně vyhladí (`smoothTtc`) a dopočte se i trend TTC (`ttcSlopeSecPerSec`).

### Krok G — RiskEngine (finální skóre a level)

Do RiskEngine vstupují hlavní signály:

- TTC (+ trend),
- DIST,
- REL,
- ROI containment + ego offset,
- cut-in, brake cue, IMU braking/lean,
- suppressory (adjacent overtaking, receding object, standing state),
- quality weight.

Výstup:

- `riskScore` (0..1),
- `level` (0/1/2),
- `reasonBits` (auditovatelný důvod).

### Krok H — UI, logování, telemetry

- Broadcastuje se sada metrik do MainActivity a Preview overlay.
- Od #74 UI schovává REL, pokud derivace není validní (zobrazí „—“ místo falešných čísel).
- Od #75 je striktněji oddělené „vybraný target pro risk“ vs. interní tracker lock v logu.

---

## 3) Přehledná tabulka commitů, parametrů a vlivu

| Commit | Zkrácený název | Co mění ve výpočtu | Parametry / metriky | Dopad na přesnost a chování | Riziko změny |
|---|---|---|---|---|---|
| `7cde65d` | standing gate + egoOffset 0..1 | Zpevnění standing suppressoru; sjednocení normalizace boční odchylky v ROI | `egoOffsetN`, `standingState`, `K_STABLE`, speed EMA/delta | Lepší potlačení falešných alertů při stání/crawlu; konzistentnější vstup do RiskEngine | Riziko „přílišného umlčení“ v hraničních pomalých jízdách |
| `c8de07c` | stabilizace REL derivace | Zavedení validity rámce pro REL + reason mask; guard proti distance glitchům; UI gating REL | `relDerivValid`, `relInvalidReasonMask`, `DIST_JUMP_GUARD_M`, `REL_MAX_RATE_MPS` | Výrazné snížení šumu a „fantom“ REL při switch/reacquire/occlusion | Krátkodobě může být REL častěji nedostupné („—“) |
| `d9ec595` | test logy | Přidány pouze datové logy z testů | žádná runtime metrika | Bez přímého vlivu na výpočet | žádné technické, jen objem repozitáře |
| `f33ec45` | stabilizace identity targetu | Lepší vazba mezi vybraným targetem, overlay a audit logem; stabilnější mapování labelu | `target_track_id`, `target_group_label`, `locked_id`, `tracker_locked_id` | Lepší auditovatelnost, méně „blikání identity“ v UI | Riziko nepochopení, pokud analytika stále čte staré pole |
| `81b11a8` | hystereze přepínání trackeru | Úprava přepínací podmínky při vysokých score (strop 1.0) | `switchMargin`, `lockedAgeFrames`, `effectiveExtra` | Méně zaseknutí na špatném locku, přitom zachovaná anti-flip ochrana | V hustém provozu může přepínat o něco dříve |

---

## 4) Metriky: co se počítá teď a jak se to od `d013cff` změnilo

### 4.1 TTC (TCC)
- **Co je to lidsky:** „Za jak dlouho bych při současném trendu dojel do překážky.“
- **Technicky:** blend TTC z růstu bboxu + distance/REL, následně smooth + slope.
- **Změny v rozsahu:** nepřibyl nový vzorec TTC, ale TTC je nepřímo stabilnější díky lepší stabilitě target identity a REL validity.

### 4.2 REL
- **Co je to lidsky:** „Jak rychle se mezera mezi jezdcem a objektem zmenšuje.“
- **Technicky:** derivace distance přes čas + EMA.
- **Změna #74:** přidané guardy, invalid reason mask, UI nezobrazuje REL pokud je derivace nespolehlivá.
- **Přínos:** menší počet falešných špiček (false approach).

### 4.3 DIST
- **Lidsky:** „Odhad skutečné vzdálenosti.“
- **Technicky:** blend monocular height + ground plane, s okluzním fallbackem a filtry.
- **Vliv změn:** #74 posiluje stabilitu vstupu do DIST derivací (ochrana proti skokům), #73/+ ROI normalizace zlepšuje navazující risk interpretaci.

### 4.4 ROI-related metriky
- `roiContainment` — jak moc box leží v ROI trapézu.
- `egoOffsetN` — jak daleko od středu „jízdní osy“ je target.
- `roiBottomTouch/bottomOccluded` — objekt dosáhl spodní hrany ROI (možná okluze spodku boxu).

**Klíčový business efekt:** když je objekt „u spodku obrazu“, systém méně věří metrikám, které z této části obrazu vycházejí, aby nevyrobil falešnou paniku.

### 4.5 Další analytické metriky, které doporučuji sledovat

1. **REL invalid rate** = podíl framů s `relDerivValid=false`.
2. **Invalid reason breakdown** = četnost bitů masky (`ID_SWITCH`, `TRACK_LOST`, `OCCL_CHANGE`, ...).
3. **Target switch rate** = počet změn `target_track_id` / minuta.
4. **Bottom-touch exposure** = podíl času s `roiBottomTouch=true`.
5. **Suppression activation rate** (`adjacent`, `receding`, `standing`) vs. skutečné alerty.
6. **Alert reversals** = rychlé změny levelu 2→0 do 1–2 s (indikátor nestability).

---

## 5) Fragmenty kódu a kde přesně probíhá výpočet

## 5.1 ROI bottom + distance fallback

**Soubor:** `app/src/main/java/com/mcaw/ai/DetectionAnalyzer.kt`

```kotlin
val bottomOccludedRaw = (cropBottomPx - bestBox.y2) <= bottomOcclEpsPx
val bottomOccluded = updateBottomOcclusionState(bottomOccludedRaw)

val distFromGround = if (!bottomOccluded) {
    DetectionPhysics.estimateDistanceGroundPlaneMeters(...)
} else null

val distanceInputRaw = if (bottomOccluded) {
    DetectionPhysics.minFinite(distanceCandidate, distCropBound) ?: ...
} else {
    distanceCandidate ?: Float.NaN
}
```

**Lidsky:** Když auto před námi už „naráží“ na spodní hranici vyhodnocovaného obrazu, systém přestane slepě věřit geometrii spodní hrany boxu a použije bezpečnější odhad.

## 5.2 REL validita + maska důvodů

**Soubor:** `app/src/main/java/com/mcaw/ai/DetectionAnalyzer.kt`

```kotlin
relDerivValid = invalidDerivFramesLeft == 0 && distanceM.isFinite() && prevDistanceForDerivM.isFinite() && dtValidForDeriv

val relSpeedSignedSample = if (relDerivValid && dtSecForDeriv.isFinite()) {
    (prevDistanceForDerivM - distanceM) / dtSecForDeriv
} else {
    0f
}
```

**Lidsky:** REL se spočítá jen když máme navazující a důvěryhodná data. Jinak se REL dočasně „umlčí“.

## 5.3 TTC blending

**Soubor:** `app/src/main/java/com/mcaw/ai/DetectionAnalyzer.kt`

```kotlin
val ttcRaw = when {
    ttcFromHeightsHeld != null && ttcFromHeightsHeld.isFinite() && ttcFromDist.isFinite() ->
        (ttcFromHeightsHeld * 0.85f) + (ttcFromDist * 0.15f)
    ttcFromHeightsHeld != null && ttcFromHeightsHeld.isFinite() -> ttcFromHeightsHeld
    else -> ttcFromDist
}
```

**Lidsky:** TTC není „jedno číslo z jedné rovnice“, ale sloučený odhad z více pohledů, aby byl stabilnější.

## 5.4 Potlačení alertů ve standing/receding/adjacent situacích

**Soubor:** `app/src/main/java/com/mcaw/ai/DetectionAnalyzer.kt`

```kotlin
val risk = riskEngine.evaluate(
    ...
    suppressAdjacentOvertake = adjacentStable && !cutInEvidence,
    suppressRecedingObject = recedingStable,
    suppressStanding = standingState,
    disableTtcApproachWeight = recedingStable,
    ...
)
```

**Lidsky:** Systém rozpozná scénáře, kde by varování byla spíš falešně pozitivní, a proto je vědomě tlumí.

## 5.5 Tracker switch hysteresis (úprava #76)

**Soubor:** `app/src/main/java/com/mcaw/ai/TemporalTracker.kt`

```kotlin
val extra = (lockedAgeFrames.coerceIn(0, 20) * 0.003f)
val effectiveExtra = extra * (1f - lockedScore)
val canSwitch =
    lockedAgeFrames >= minLockAgeFramesBeforeSwitch &&
    bestScore >= (lockedScore + switchMargin + effectiveExtra) &&
    !graceActive(lockedMissFrames, tsMs)
```

**Lidsky:** Čím je aktuálně sledovaný objekt jistější, tím opatrněji se přepíná. Ale když už je skóre na maximu, přepnutí se úplně nezablokuje.

## 5.6 UI gating REL (aby uživatel neviděl „špatná“ čísla)

**Soubory:** `OverlayView.kt`, `MainActivity.kt`, `PreviewActivity.kt`

- REL v HUD se ukáže jen když je `relDerivValid=true`.
- Jinak se zobrazuje „—“.

**Lidsky:** Raději žádné číslo než špatné číslo.

---

## 6) Hodnocení: zlepšení vs. rizika

### Co se objektivně zlepšilo

1. **Stabilita REL a odvozeného TTC** v nestabilních framech.
2. **Lepší auditovatelnost targetu** (méně nejasností „na co se alert vztahoval“).
3. **Odolnost proti ROI-bottom artefaktům** (dotek spodku ROI už tolik nerozbíjí výpočet).
4. **Lepší switchování trackeru** v high-score scénářích.

### Co může bolet / na co dát pozor

1. **Více stavů „REL nedostupné“** může působit, že app „nic nepočítá“, i když je to záměr ochrany kvality.
2. **Konzervativní suppressory** mohou v hraničních situacích oddálit alert.
3. **Analytika musí číst nová pole správně** (`target_track_id` vs. `tracker_locked_id`, `relDerivValid`, reason mask).

---

## 7) Doporučení pro další analytiku a regresní kontrolu

1. Přidat dashboard „**REL quality**“ (invalid ratio + důvody po bitech).
2. Kontrolovat KPI „**false-red rate when bottom touch=true**“ před/po #74.
3. Separátně sledovat „**switch beneficial rate**“ (switch vedl k menší varianci TTC během 2 s).
4. U standing suppressoru měřit „**missed-critical near stop**“ (manuálně tagované scény).
5. Ve všech reportech rozlišovat:
   - technický lock trackeru,
   - finálně vybraný target použitý pro risk.

---

## 8) Stručný závěr pro business vlastníka

Od `d013cff` se výpočtová pipeline posunula hlavně směrem ke **stabilitě a důvěryhodnosti**:

- méně „náhodných“ skoků REL,
- lepší chování při dotyku objektu se spodkem ROI,
- přesnější konzistence v tom, který objekt je skutečně vyhodnocován,
- menší riziko zaseknutí trackeru na špatném objektu.

Nejde o „agresivnější varování“, ale o **kvalitnější rozhodování**: když data nejsou spolehlivá, systém to přizná (REL „—“) a raději počká na validní trend.
