package com.mcaw.ui

import android.os.Bundle
import android.widget.TextView
import androidx.activity.ComponentActivity
import com.mcaw.app.R

class HelpActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_help)

        findViewById<TextView>(R.id.txtHelpTitle).text = "Jak MCAW funguje a jak jej správně nastavit"
        findViewById<TextView>(R.id.txtHelpContent).text = buildHelpText()
    }

    private fun buildHelpText(): String {
        return """MCAW sleduje objekty před tebou, jejich vzdálenost a rychlost přibližování.
Hlavní ukazatel je TTC (Time To Collision) – čas do možné srážky.
Čím menší TTC, tím vyšší riziko.

1) Co aplikace sleduje
• Objekty před tebou (auta, motorky, bus, chodec)
• Odhad vzdálenosti
• Rychlost přibližování
• TTC (čas do možné srážky)

2) „Omezit detekci na jízdní dráhu“
Když je zapnuto:
• bere jen objekty v jízdní dráze (trapezoid ROI)
• méně falešných alarmů (boky, protisměr, parkovaná auta)

Když je vypnuto:
• reaguje i na objekty mimo jízdní dráhu
• může častěji varovat

Doporučení:
• Město → zapnuto
• Dálnice → zapnuto
• Testování → podle potřeby

3) Šířka pruhu (tolerance do stran)
Určuje, jak daleko od středu ROI může být objekt a pořád se bere jako „před tebou“.
• Úzký: méně rušení, ale může přehlédnout cut‑in
• Široký: lépe zachytí cut‑in, ale může víc varovat

4) Ochrana proti náhlému najetí (cut‑in)
Pomáhá zachytit situaci, kdy auto rychle najede do tvé dráhy.
Doporučeno: zapnuto.

5) Kalibrace vzdálenosti
Když aplikace:
• hlásí systematicky menší vzdálenost → zvyš kalibraci („Více“)
• hlásí systematicky větší vzdálenost → sniž kalibraci („Méně“)

Záleží na telefonu, uchycení a výšce kamery.

5a) Kalibrace kamery (výška + sklon)
Tyto hodnoty pomáhají zpřesnit odhad vzdálenosti z obrazu:
• Výška kamery (m): výška čočky nad zemí. Auto typicky 1.1–1.5 m, moto často 0.9–1.3 m.
• Sklon kamery dolů (°): 0° = horizont, kladné hodnoty = dolů (typicky 3–10°).

Pokud aplikace hlásí vzdálenost systematicky špatně i po „Kalibraci vzdálenosti“, doladit tyto parametry.

6) Kvalita obrazu (tma / vibrace / rozmazání)
Když je zapnuto, aplikace je méně agresivní v horších podmínkách a méně ruší.
Doporučeno: zapnuto.

7) Jak správně nastavit telefon
• Telefon musí být pevně uchycený a stabilní
• Kamera musí mít čistý výhled
• Telefon nesmí mířit příliš nahoru
• ROI nastav podle svého jízdního pruhu""".trimIndent()
    }
}
