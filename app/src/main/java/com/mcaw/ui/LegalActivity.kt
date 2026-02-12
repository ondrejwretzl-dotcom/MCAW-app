package com.mcaw.ui

import android.os.Bundle
import android.widget.TextView
import androidx.activity.ComponentActivity
import com.mcaw.app.R

class LegalActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_legal)

        findViewById<TextView>(R.id.txtLegalTitle).text = "Zodpovědnost a limity"
        findViewById<TextView>(R.id.txtLegalContent).text = buildLegalText()
    }

    private fun buildLegalText(): String {
        return """MCAW je pouze pomůcka. Nenahrazuje řidiče ani jeho povinnosti.

DŮLEŽITÉ:
• Ty jsi vždy plně zodpovědný za řízení, situaci na silnici a bezpečné rozhodnutí.
• Aplikace může chybovat: může něco nevidět, špatně vyhodnotit, nebo varovat pozdě či zbytečně.
• Výsledky ovlivňuje telefon, uchycení, zorné pole, stabilita obrazu, světlo, déšť, tma, odlesky, špína na skle, vibrace, kopce, provoz a mnoho dalších faktorů.

CO APLIKACE DĚLÁ:
• Zpracovává obraz z kamery a odhaduje relativní situaci před tebou.
• V některých situacích může varování pomoci včas zpozornět.

CO APLIKACE NEZARUČUJE:
• Nezaručuje správnost detekce, odhadu vzdálenosti ani TTC.
• Nezaručuje, že zabrání nehodě nebo škodě.
• Nejde o schválený bezpečnostní systém vozidla.

POUŽÍVÁNÍ JE NA VLASTNÍ RIZIKO:
Používáním aplikace bereš na vědomí, že:
• aplikace je poskytována „tak jak je“ bez záruk,
• autor aplikace nenese žádnou odpovědnost za jakékoliv škody, ztráty, úrazy nebo následky vzniklé používáním nebo nemožností používat aplikaci,
• veškerá právní odpovědnost za jízdu, rozhodnutí a následky je na uživateli.

Pokud s tímto nesouhlasíš, aplikaci nepoužívej.
""".trimIndent()
    }
}
