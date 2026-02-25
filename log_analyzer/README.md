# MCAW Log Analyzer (first version)

Webové „kukátko“ na logy z telefonu pro analytické čtení MCAW logů.

## Co umí v této verzi
- načíst více log souborů (`.txt`, `.csv`) přes upload,
- **zpracovat pouze soubory s datem `2026-02-25`** v názvu,
- parsovat řádky typu `S` (service), datové risk řádky a `M,...,METRICS`,
- seskupit související události do časových oken,
- zobrazit timeline s doporučením (co řešit prioritně),
- vykreslit trend risk score,
- zobrazit tabulku se sticky hlavičkou (scroll uvnitř tabulky),
- zobrazit detail události včetně „lidského překladu“ reason bits a klíčových výpočtů.

## Start

```bash
cd log_analyzer
npm install
npm run dev
```

## Poznámka
Jde o první iteraci určenou pro rychlou orientaci v logu. Další krok může být přímé napojení na konkrétní MCAW datové kontrakty (verzování reason bits, mapování reason_id, validace vůči RiskEngine ref).
