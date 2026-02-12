# Reakce na review: změny detekce (ROI/TTC/tracker/perf)

## Stav podkladů
V dodaném exportu review nejsou k dispozici konkrétní inline komentáře na jednotlivé řádky diffu.
Proto je níže připraven **strukturovaný remediation plán** k nejrizikovějším částem předchozího PR, aby bylo možné změny bezpečně přijmout na branch `docs` a následně zavést do kódu.

---

## 1) Rizika z předchozí změny a doporučené korekce

## 1.1 Tracker citlivost (`minConsecutiveForAlert = 2`)
**Riziko:** rychlejší alert, ale vyšší krátké FP pulzy.

**Doporučení (code-level):**
- zavést adaptivní pravidlo:
  - `2` potvrzení pro track s vysokou stabilitou (IOU trend + confidence),
  - `3` potvrzení při nestabilním tracku nebo při nízké confidence.
- přidat metriku `time_to_first_alert_ms` a `fp_pulses_per_min` do debug logu.

**Proč:** zachová rychlou reakci v dobrých podmínkách a omezí „nervózní“ alerting v šumu.

---

## 1.2 ROI containment 0.65/0.80
**Riziko:** 0.65 zvyšuje recall, ale může vpouštět okrajové objekty mimo ego-pruh.

**Doporučení (code-level):**
- ponechat uživatelský přepínač, ale doplnit:
  - "Auto" režim podle rychlosti + velikosti objektu,
  - při vyšší rychlosti preferovat strict,
  - při nízké rychlosti a malých objektech povolit relaxed.

**Proč:** v městském provozu zlepší zachytávání relevantních objektů, na vyšších rychlostech sníží falešné zásahy z okolních pruhů.

---

## 1.3 TTC hysteréze
**Riziko:** fixní hysteréze nemusí sedět napříč scénami (město vs dálnice).

**Doporučení (code-level):**
- ponechat hysterézi jako baseline,
- udělat prahy konfigurovatelné interně (remote/local config),
- logovat přechody `0->1`, `1->2`, `2->1`, `1->0` s TTC hodnotami.

**Proč:** hysteréze je správný směr; audit přechodů umožní rychlé dolaďování bez regresí.

---

## 1.4 Fail-operational při unknown speed
**Riziko:** bezpečnější než silent-off, ale bez explicitního quality gate může být chování hůř predikovatelné.

**Doporučení (code-level):**
- přidat do metrik pole: `speed_source`, `speed_confidence`, `speed_age_ms`, `speed_valid`.
- zachovat fail-operational alerting, ale vynutit "degraded" flag v UI/debug overlay.

**Proč:** operátor uvidí, že varování běží v degradovaném režimu a může správně interpretovat citlivost systému.

---

## 1.5 Throttling metrics/overlay/logging
**Riziko:** příliš agresivní throttling může skrývat krátké oscilace nebo micro-events důležité pro analýzu.

**Doporučení (code-level):**
- ponechat `80 ms` runtime default,
- při debug session umožnit přepnutí na `40 ms`,
- zachovat force-send při změně alert levelu (již správně).

**Proč:** ve výrobním režimu šetří výkon, v debug režimu dá detailnější telemetrii.

---

## 2) Návrh validačního protokolu před nasazením

## Dataset/scénáře
- den / noc / déšť,
- město (30–50 km/h), okreska (70–90), dálnice (110+),
- lead vehicle: stabilní jízda, brzdění, cut-in, zatáčka.

## KPI
- `FP/min`,
- `FN` na lead vehicle,
- `time_to_alert_ms`,
- počet oscilací alert levelu za minutu,
- frame processing jitter (p95/p99).

## Akceptační hranice (doporučené)
- TTC hysteréze sníží oscilace levelu min. o 30 %,
- time-to-alert nesmí být horší o více než 100 ms proti baseline,
- FP/min nesmí narůst o více než 10 % v highway scénáři.

---

## 3) Doporučení pro rozhodnutí "co je lepší"

## Lepší v aktuální verzi (d8d5ab6 / 2e40ce3)
- TTC hysteréze (stabilita rozhodování),
- výkonové odlehčení (throttling + sampling),
- provozní nastavitelnost (ROI strict toggle),
- fail-operational alerting při výpadku validní rychlosti.

## Kde být opatrný
- tracker `2` potvrzení bez adaptace může zvyšovat krátké FP,
- relaxed ROI může zvýšit citlivost na okrajové objekty,
- chybějící speed quality vizualizace komplikuje diagnostiku.

## Finální stanovisko
Aktuální verzi doporučuji jako baseline, ale před plným nasazením doplnit:
1. adaptivní tracker gate,
2. speed-quality telemetrii,
3. řízenou A/B validaci podle výše uvedených KPI.

Tím se zachová přínos změn (stabilita + výkon + použitelnost) a minimalizují se regresní rizika.
