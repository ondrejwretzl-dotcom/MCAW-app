package com.mcaw.ui

import android.os.Bundle
import android.widget.TextView
import androidx.activity.ComponentActivity
import com.mcaw.app.R

class LegalActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_legal)

        findViewById<TextView>(R.id.txtLegalTitle).text = "Upozornění a podmínky"
        findViewById<TextView>(R.id.txtLegalContent).text = buildLegalText()
    }

    private fun buildLegalText(): String {
        return """MCAW je pouze asistenční nástroj
Aplikace MCAW (Motorcycle / Mobile Collision Assist Warning) slouží jako doplňková vizuální a zvuková pomůcka.
Nenahrazuje pozornost, úsudek ani odpovědnost řidiče.

Řidič je vždy plně odpovědný za:
• sledování provozu
• přizpůsobení rychlosti
• dodržování předpisů
• bezpečné ovládání vozidla

Omezení detekce
Aplikace využívá kameru telefonu a AI detekci objektů v obrazu, odhad vzdálenosti a výpočet TTC.
Tyto metody mají přirozená omezení:
• může dojít k falešnému varování
• může dojít k opožděnému varování
• objekt nemusí být detekován vůbec
• odhad vzdálenosti může být nepřesný
• výpočet TTC může být zkreslen vibracemi, špatným světlem nebo deštěm

Systém může selhat zejména při:
• nízkém světle / oslnění
• dešti, mlze, sněhu
• špatném uchycení telefonu
• silných vibracích (např. motocykl)

Nejde o bezpečnostní systém vozidla
MCAW:
• není homologovaný bezpečnostní systém
• není asistenční systém typu ADAS
• není náhradou brzdového systému
• není systém autonomního řízení

Právní upozornění
Používáním aplikace uživatel bere na vědomí, že:
• aplikace je poskytována „tak jak je“
• autor nenese odpovědnost za škody na zdraví, majetku ani jiné následky vzniklé používáním aplikace
• veškeré rozhodování při řízení zůstává výhradně na řidiči

Doporučení
• Telefon musí být pevně uchycen
• Kamera musí mít čistý výhled
• Nenastavujte aplikaci během jízdy
• Sledujte provoz – ne displej""".trimIndent()
    }
}
