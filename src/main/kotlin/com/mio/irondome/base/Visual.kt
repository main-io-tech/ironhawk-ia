package com.mio.irondome.base

import com.intellij.ide.util.PropertiesComponent
import java.awt.Window

/**
 * O visual leve, para máquinas mais fracas: sem animação nas barras de andamento, sem as barras de proporção nas
 * linhas das árvores e sem os destaques coloridos dentro dos nomes. A informação é a mesma; some o que só enfeita e
 * custa desenho. Vale para todas as abas, na Defesa e no Ataque.
 */
object IronHawkVisual {

    private const val KEY = "IronHawk.Visual.Lite"

    @Volatile
    private var cached: Boolean? = null

    var lite: Boolean
        get() = cached ?: PropertiesComponent.getInstance().getBoolean(KEY, false).also { cached = it }
        set(value) {
            if (value == lite) return
            PropertiesComponent.getInstance().setValue(KEY, value, false)
            cached = value
            // As linhas das árvores conferem o modo a cada desenho: basta pedir que tudo se desenhe de novo.
            Window.getWindows().forEach { it.repaint() }
        }
}
