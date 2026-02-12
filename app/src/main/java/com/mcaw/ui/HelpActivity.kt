package com.mcaw.ui

import android.os.Bundle
import android.widget.TextView
import androidx.activity.ComponentActivity
import com.mcaw.app.R

class HelpActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_help)

        findViewById<TextView>(R.id.txtHelpTitle).text = "Návod"
        findViewById<TextView>(R.id.txtHelpContent).text = buildHelpText()
    }

    private fun buildHelpText(): String {
        return """MCAW je pomocník pro včasné varování. Aby byl užitek co největší a zároveň to zbytečně nerušilo, doporučuju postup:

1) Základní nastavení (doporučené)
• Filtrovat objekty v ROI: ZAP
  Ignoruje cíle mimo směr jízdy (boky, protisměr, parkovaná auta). Ve městě výrazně sníží falešné poplachy.
• Šířka pruhu (tolerance do stran): Střední
  Úzký = přesnější, méně rušení. Široký = lépe chytá cut‑in, ale může brát i bokové cíle.
• Omezit falešné alarmy v noci/rozmazání: ZAP
  Když je obraz moc tmavý nebo rozmazaný, aplikace zpřísní varování.

2) Kalibrace vzdálenosti
• Kalibrace vzdálenosti: OK
  Pokud aplikace systematicky „přehání“ blízkost (hlásí kratší vzdálenost), dej „Více“.
  Pokud naopak hlásí příliš dlouhé vzdálenosti, dej „Méně“.
Tip: Kalibruj na rovné silnici za sucha a stabilního držáku.

3) Ochrana proti cut‑in
• Ochrana proti cut‑in: ZAP
  Pomáhá zachytit auta rychle najíždějící do tvé dráhy i když jsou ještě bokem.

4) Varování (Zvuk / Vibrace / Hlas)
• Globální přepínače zapínají výstupy jako celek.
• Rozdělení pro oranžovou a červenou ti dovolí mít např.:
  – Oranžová: jen zvuk (krátké upozornění)
  – Červená: zvuk + vibrace + hlas (kritické)
• Text TTS si můžeš přizpůsobit (např. „Pozor!“ / „Brzdi!“).

5) Režim (Město / Sport / Uživatel)
• Město: konzervativní, míří na minimum rušení.
• Sport: počítá s vyšší rychlostí a většími odstupy.
• Uživatel: nastav si vlastní prahy (TTC / vzdálenost / rychlost přiblížení).

Pokud si nejsi jistý:
Použij „Reset na doporučené“ a pak dolaď jen 1–2 věci (nejčastěji Šířku pruhu a Kalibraci vzdálenosti).
""".trimIndent()
    }
}
