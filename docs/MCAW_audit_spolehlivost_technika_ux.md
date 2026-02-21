# MCAW – praktický plán: porovnání scénářů mezi commity + přehledné HTML reporty

Datum: 2026-02-21  
Cíl: navázat na už existující risk/scenario testy a připravit jasný, „lidský“ plán co přesně uděláme dál.

---

## 1) Co už dnes funguje dobře (a necháme to jako základ)

Máte správně postavený základ a není potřeba ho přepisovat:

- **Scénáře + očekávání** už pokrývají město/tunel/dálnici/okresku i moto varianty.
- **Pravidla chování** už odpovídají KPI logice (must enter, must not enter, max transitions, invalid TTC guard).
- **Výstupy** už dnes vznikají dvojmo: čitelný `.md` report + strukturovaný `.jsonl`.
- **Index report** už agreguje celou sadu scénářů.

To znamená, že další krok není „dělat testy znovu“, ale přidat **compare vrstvu** a **lepší prezentaci výsledků**.

---

## 2) Co přesně chybí (abychom viděli zlepšení/zhoršení po commitu)

Chybí 3 věci:

1. **Baseline vs Current porovnání** (strojově, ne ručně).
2. **Jednoznačný verdikt změny**: IMPROVED / REGRESSED / UNCHANGED.
3. **Klikatelné HTML** (index + detail + diff), aby to bylo přehledné na 1 klik.

---

## 3) Návrh cílového workflow (jednoduchý a praktický)

### Krok A: Vygenerovat „snapshot“ metrik z každého běhu
Z existujících JSONL udělat kompaktní `summary.json` (1 záznam na scénář), např.:

- `scenarioId`
- `pass` (true/false)
- `maxLevel`
- `firstOrangeSec`
- `firstRedSec`
- `transitionsTotal`
- `maxTransitionsWindow`
- `orangeCount`
- `redCount`
- `topReasonIds` (např. top 5)

### Krok B: Porovnat dva snapshoty
Vstupy:
- `baseline_summary.json` (typicky z `main`)
- `current_summary.json` (z právě testovaného commitu)

Výstup:
- `diff_summary.json` s delta metrikami a statusem per scénář.

### Krok C: Vygenerovat klikatelný HTML report
- `index.html` – celkové skóre + tabulka scénářů + filtry.
- `scenario/<id>.html` – detail aktuálního běhu.
- `diff/<id>.html` – baseline vs current (co se zlepšilo/zhoršilo).

---

## 4) Jak budeme rozhodovat „zlepšení / zhoršení“

Aby to bylo stabilní, rozdělíme pravidla na tvrdá a měkká:

### Tvrdá regrese (CI FAIL)
- PASS -> FAIL
- výrazné zhoršení latence varování nad dohodnutou toleranci
- překročení anti-blink limitu (`maxTransitionsWindow`)

### Měkká regrese (CI WARNING)
- menší zhoršení (např. +0.1 až +0.3 s) bez porušení hard limitů
- změna reason mixu bez funkčního selhání

### Zlepšení
- FAIL -> PASS
- rychlejší náběh ORANGE/RED při zachování stability
- méně přechodů (méně „cvakání“)

---

## 5) HTML podoba reportu (aby byl „lidský“)

## 5.1 Index (`index.html`)
- velké karty: `PASS`, `FAIL`, `REGRESSED`, `IMPROVED`
- tabulka scénářů:
  - scénář
  - baseline status
  - current status
  - delta (čas varování, transitions)
  - badge: 🟢 / 🟡 / 🔴
  - odkaz na detail diff
- filtry: domain (CITY/HIGHWAY/...), vehicle (CAR/MOTO), status

## 5.2 Detail diff scénáře (`diff/<id>.html`)
- nahoře „Verdikt scénáře“ (improved/regressed/unchanged)
- tabulka metrik baseline vs current vs delta
- timeline klíčových přechodů alertů
- top reason IDs a jejich změny
- stručný „human summary“ (2–3 věty)

---

## 6) CI/CD plán bez velkého rizika

### Fáze 1 (rychlá, tento týden)
- generovat `summary.json` + `diff_summary.json`
- zatím jen artifacty (bez failování buildu)

### Fáze 2 (po ověření)
- zapnout hard fail na jasné regrese
- soft regrese jen warning

### Fáze 3
- přidat trend přes více buildů (mini historie)

---

## 7) Co je realisticky hotové v krátkém čase (1.5h mindset)

Pokud máme omezený čas, nejlepší je připravit teď **specifikaci a datový kontrakt**:

1. potvrdit seznam metrik v `summary.json`
2. potvrdit pravidla hard/soft regrese
3. potvrdit layout `index.html` (co přesně tam chceme)

Tím bude implementace v dalším kroku přímočará a bez přepisování logiky.

---

## 8) Shrnutí jednou větou

Risk test stack už máte velmi dobrý; teď potřebujeme hlavně **automatické porovnání dvou běhů a čitelné HTML diff reporty**, aby byl po každém commitu jasný dopad změn.

---

## 9) Praktické prahy v1 (doporučený start pro CI)

Na základě aktuálního katalogu scénářů a cíle minimalizovat falešné pády CI navrhuji v1 defaulty:

- `mcaw.diff.hardLatencySec=0.60`
- `mcaw.diff.softLatencySec=0.25`
- `mcaw.diff.hardTransitionsInc=2`
- `mcaw.diff.softTransitionsInc=1`

Interpretace:
- Hard regrese: výrazné zpomalení reakce varování nebo výrazný nárůst cvakání.
- Soft regrese: časná signalizace driftu (warning), bez okamžitého failu.

## 10) Mechanismus bezpečné aktualizace baseline (aby se na to nezapomnělo)

Baseline update se nyní řeší jako **řízený gate**, ne automaticky vždy.

Doporučené vlastnosti:
- baseline se aktualizuje jen když projdou quality gate podmínky,
- baseline candidate se zapisuje na explicitní cestu,
- do reportu se uloží rozhodnutí (`baseline_update_decision.txt`) proč ano/ne.

Doporučené CI přepínače:

- `mcaw.baseline.updateEnabled=true|false`
- `mcaw.baseline.candidateOut=/path/to/new/baseline_summary.json`
- `mcaw.baseline.requireAllPass=true`
- `mcaw.baseline.maxSoftRegressions=0`
- `mcaw.baseline.minImproved=0` (pro první baseline),
  později např. `1` pro „jen když je reálné zlepšení“.

## 11) Další kroky po tomto PR

1. **První baseline vytvořit vědomě** (s `mcaw.baseline.updateEnabled=true`, bez existující baseline).
2. Baseline uložit immutable (ideálně SHA/časová cesta) + mít pointer na „approved latest“.
3. V CI zapnout:
   - `mcaw.failOnHardRegression=true`
   - baseline compare přes `mcaw.baselineSummary=...`
4. Build summary/artefakt publikovat s odkazem na `index.html`.
5. Po 1–2 týdnech doladit prahy podle reálných trendů.

## 12) Runbook pro ruční kroky

Detailní návod je v samostatném souboru:

- `docs/SCENARIO_BASELINE_RUNBOOK.md`

A při běhu scénářů se jeho kopie exportuje i do report artifactu jako `RUNBOOK.md` (odkaz z `index.html`).

## 13) CI automatizace baseline lifecycle

Přidány workflow:

- `.github/workflows/scenario-regression.yml` (compare + artifact + summary)
- `.github/workflows/scenario-baseline-promote.yml` (ruční promote z artifact runu)

Tím je pokryto:
- automatické hlídání hard regresí v běžném běhu,
- řízený a auditovatelný promote baseline bez ručního kopírování souborů.

## 14) Dostupnost výstupu bez artifact downloadu

`scenario-regression.yml` publikuje report i do GitHub Pages a do Job Summary zapisuje `page_url`.

Tím je možné otevřít `index.html` přímo v browseru bez stahování artifactu.
