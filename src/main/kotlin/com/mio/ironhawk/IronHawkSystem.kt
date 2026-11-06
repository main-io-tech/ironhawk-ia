package com.mio.ironhawk

import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.actionSystem.AnActionEvent
import com.mio.irondome.base.ReviewToolWindowFactory
import com.mio.tomahawk.base.TomahawkToolWindowFactory

/** Os dois sistemas do IronHawk IA, que o usuário liga e desliga em Settings > Tools > IronHawk IA. */
enum class IronHawkSystem(val title: String, val description: String, val toolWindowId: String, private val key: String) {
    DEFENSE(
        "Defesa (Iron Dome)",
        "Revisão do código com a IA: Radar, Centro de Comando, Raio de Impacto, Sentinela, Escudo, Patrulha e Inteligência.",
        ReviewToolWindowFactory.ID,
        "Defesa",
    ),
    ATTACK(
        "Ataque (Tomahawk)",
        "Desenvolvimento guiado pela spec com o Spec Kit: Lançador, Extração, Rádio, Avaliação e Telemetria.",
        TomahawkToolWindowFactory.ID,
        "Ataque",
    );

    var enabled: Boolean
        get() = PropertiesComponent.getInstance().getBoolean(PREFIX + key, true)
        set(value) = PropertiesComponent.getInstance().setValue(PREFIX + key, value, true)

    /** No update das ações: com o sistema desligado, a ação some. Devolve se o sistema está ligado. */
    fun showIn(e: AnActionEvent): Boolean {
        if (enabled) return true
        e.presentation.isEnabledAndVisible = false
        return false
    }

    private companion object {
        const val PREFIX = "IronHawk.Sistema."
    }
}
