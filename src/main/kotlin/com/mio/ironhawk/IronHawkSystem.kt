package com.mio.ironhawk

import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.util.messages.Topic

/** Os dois sistemas do IronHawk IA, que o usuário liga e desliga em Settings > Tools > IronHawk IA. */
enum class IronHawkSystem(val title: String, val label: String, val description: String, private val key: String) {
    DEFENSE(
        "Defesa (Iron Dome)",
        "Defesa",
        "Revisão do código com a IA: Radar, Centro de Comando, Raio de Impacto, Sentinela, Escudo, Patrulha e Inteligência.",
        "Defesa",
    ),
    ATTACK(
        "Ataque (Tomahawk)",
        "Ataque",
        "Desenvolvimento guiado pela spec com o Spec Kit: Lançador, Extração, Rádio, Avaliação e Telemetria.",
        "Ataque",
    );

    var enabled: Boolean
        get() = PropertiesComponent.getInstance().getBoolean(PREFIX + key, true)
        set(value) {
            PropertiesComponent.getInstance().setValue(PREFIX + key, value, true)
            ApplicationManager.getApplication().messageBus.syncPublisher(TOPIC).changed()
        }

    /** No update das ações: com o sistema desligado, a ação some. Devolve se o sistema está ligado. */
    fun showIn(e: AnActionEvent): Boolean {
        if (enabled) return true
        e.presentation.isEnabledAndVisible = false
        return false
    }

    /** Um sistema foi ligado ou desligado. */
    fun interface Listener {
        fun changed()
    }

    companion object {
        private const val PREFIX = "IronHawk.Sistema."

        @Topic.AppLevel
        val TOPIC = Topic(Listener::class.java, Topic.BroadcastDirection.NONE)

        val enabledSystems: List<IronHawkSystem> get() = entries.filter { it.enabled }
    }
}
