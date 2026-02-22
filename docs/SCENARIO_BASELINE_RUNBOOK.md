# MCAW Scenario Baseline Runbook

Tento návod popisuje **co spouštět, kdy, s jakými vstupy** a jak číst výstupy pro scenario baseline/diff workflow.

---

## 1) Co se generuje

Každý běh scénářů generuje do `build/reports/mcaw_scenarios/<timestamp>/`:

- `INDEX.md` – čitelný markdown přehled scénářů
- `index.html` – klikatelný dashboard
- `summary.json` – strojový snapshot metrik (vstup pro baseline diff)
- `diff_summary.json` – porovnání proti baseline (pokud baseline byla předána)
- `<SCENARIO_ID>.md` – detail scénáře
- `<SCENARIO_ID>.jsonl` – strukturované eventy
- `baseline_update_decision.txt` – rozhodnutí quality gate (pokud je update baseline zapnutý)
- `RUNBOOK.md` – kopie tohoto návodu pro pohodlné čtení v artefaktu

---

## 2) Ruční vstupy (co zadáváš ty)

### Povinné vstupy pro compare
- `mcaw.baselineSummary=/path/to/approved/baseline_summary.json`

### Volitelné vstupy pro prahy diffu
- `mcaw.diff.hardLatencySec` (default `0.60`)
- `mcaw.diff.softLatencySec` (default `0.25`)
- `mcaw.diff.hardTransitionsInc` (default `2`)
- `mcaw.diff.softTransitionsInc` (default `1`)

### Volitelné vstupy pro fail build
- `mcaw.failOnHardRegression=true|false`
- `mcaw.failOnScenario=true|false`

### Volitelné vstupy pro baseline update gate
- `mcaw.baseline.updateEnabled=true|false`
- `mcaw.baseline.candidateOut=/path/to/candidate/baseline_summary.json`
- `mcaw.baseline.requireAllPass=true|false` (doporučeno `true`)
- `mcaw.baseline.maxSoftRegressions=<int>` (doporučeno `0`)
- `mcaw.baseline.minImproved=<int>`

---

## 3) Doporučený provozní režim

## Fáze A – první baseline (jednorázově)
1. Spusť scénáře bez baseline (`mcaw.baselineSummary` nevyplňuj).
2. Zapni baseline update gate:
   - `mcaw.baseline.updateEnabled=true`
   - `mcaw.baseline.candidateOut=<immutable_path>/baseline_summary.json`
3. Zkontroluj `baseline_update_decision.txt`.
4. Pokud `shouldUpdate=true`, candidate baseline schval a nastav jako `approved`.

## Fáze B – běžný commit do main
1. Spouštěj scénáře proti `approved` baseline (`mcaw.baselineSummary=...`).
2. Zapni `mcaw.failOnHardRegression=true`.
3. Pokud je hard regrese, build padá.
4. Pokud není hard regrese, build projde a sleduj soft drift.

## Fáze C – promítnutí zlepšení do baseline
1. Pouze po ověření, že zlepšení je žádoucí (ne jen tuning drift).
2. Spusť baseline gate s `mcaw.baseline.updateEnabled=true`.
3. Candidate baseline použij až po review výsledků (`index.html`, `diff_summary.json`).

---

## 4) Interpretace `baseline_update_decision.txt`

- `shouldUpdate=true` → gate podmínky splněny, candidate baseline byl/je možné zapsat.
- `shouldUpdate=false` → baseline se nesmí posunout; důvody jsou v odrážkách.

Typické důvody blokace:
- Not all scenarios passed
- Hard regressions present
- Soft regressions exceed limit
- Improvements below gate

---

## 5) Doporučení pro immutable baseline

Ukládej baseline pod cestu se SHA/časem, např.:

`baselines/<catalog_version>/<git_sha>/baseline_summary.json`

A zvlášť drž pointer na approved latest, např.:

`baselines/<catalog_version>/approved_latest.txt` (obsahuje SHA nebo cestu).

---

## 6) Co kontrolovat při incidentu

1. `index.html` (nejrychlejší přehled)
2. `diff_summary.json` (status/regrese)
3. konkrétní `<SCENARIO_ID>.md`
4. konkrétní `<SCENARIO_ID>.jsonl` (hluboké ladění reasonId/eventů)


---

## 7) GitHub Actions workflow map (doporučeno)

Repo obsahuje 2 workflow:

1. `.github/workflows/scenario-regression.yml`
   - běží compare a publikuje artifact `mcaw-scenario-report`
   - v artifactu otevři `index.html`

2. `.github/workflows/scenario-baseline-promote.yml`
   - ručně (workflow_dispatch) vezme artifact z vybraného `run_id`
   - vyzvedne `summary.json`
   - promuje baseline do `.ci/baselines/<catalog>/<baseline_id>/summary.json`
   - přepíše pointer `.ci/baselines/approved_latest.txt`

### Jaké ruční vstupy zadat při promote

- `run_id` = ID běhu, kde je ověřený report
- `artifact_name` = typicky `mcaw-scenario-report`
- `baseline_id` = doporučeně commit SHA nebo datum
- `catalog` = namespace baseline (např. `default`)


### Kde přesně najdu hodnoty na obrazovce GitHub Actions (klikací návod)

#### `run_id` (povinné)
1. GitHub → **Actions** → otevři workflow **Scenario Regression**.
2. Klikni na konkrétní run, který chceš promotovat.
3. Pod názvem runu uvidíš URL ve tvaru:
   `.../actions/runs/1234567890`
4. Číslo za `/runs/` je přesně `run_id` (zde `1234567890`).

Alternativně: vpravo nahoře v detailu runu bývá vidět i text **Run ID: 1234567890**.

#### `artifact_name` (většinou `mcaw-scenario-report`)
1. Ve stejném detailu runu sjeď dolů na sekci **Artifacts** (pravý panel nebo spodní část stránky).
2. Název artefaktu v seznamu je hodnota pro `artifact_name`.
3. V našem repo je standardně `mcaw-scenario-report`.

#### `baseline_id`
Doporučení:
- použij krátký commit SHA runu (např. `b144964`), nebo
- datum+čas (např. `2026-02-21_1`).

`baseline_id` je tvůj label; workflow ho použije jako složku baseline.

#### `catalog`
- Pro první promo nech `default`, pokud tým nemá víc katalogů.

### Jak spustit první promo krok za krokem

1. Otevři **Actions** → workflow **Scenario Baseline Promote**.
2. Klikni **Run workflow**.
3. Vyplň:
   - `run_id` = ID z předchozího `Scenario Regression` runu
   - `artifact_name` = `mcaw-scenario-report`
   - `baseline_id` = např. krátký SHA
   - `catalog` = `default`
4. Potvrď **Run workflow**.
5. Po doběhu zkontroluj v diffu/commitu, že vzniklo:
   - `.ci/baselines/<catalog>/<baseline_id>/summary.json`
   - `.ci/baselines/approved_latest.txt` (ukazuje na nový baseline)

### Nejčastější chyba při prvním promu

- Zadáš `run_id` z workflow **Scenario Baseline Promote** místo z **Scenario Regression**.
  - Správně: `run_id` musí vždy odkazovat na run, kde vznikl artifact reportu (`mcaw-scenario-report`).


---

## 8) Kam koukat bez stahování artifactu

Po běhu workflow `Scenario Regression`:

1. Otevři detail běhu v GitHub Actions.
2. V **Job summary** je uveden odkaz **GitHub Pages** (`page_url`).
3. Otevři tento URL a rovnou uvidíš `index.html` + odkazy na report soubory.

Pozn.: workflow je nastavené tak, aby se report publikoval i když compare krok skončí non-zero (abys měl co debugovat).
