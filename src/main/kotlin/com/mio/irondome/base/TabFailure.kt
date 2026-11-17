package com.mio.irondome.base

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBPanelWithEmptyText
import java.util.concurrent.ConcurrentHashMap

/** Abas que não abriram nesta sessão da IDE, pelo título, com a mensagem do erro. */
object TabFailures {

    private val failures = ConcurrentHashMap<String, String>()

    val all: Map<String, String> get() = failures.toMap()

    fun record(title: String, error: Throwable) {
        failures[title] = describe(error)
    }

    fun clear(title: String) {
        failures.remove(title)
    }

    fun describe(error: Throwable): String {
        val message = error.message?.lines()?.firstOrNull()?.trim().orEmpty()
        val text = if (message.isEmpty()) error.javaClass.simpleName else "${error.javaClass.simpleName}: $message"
        return if (text.length > MAX_LENGTH) text.take(MAX_LENGTH - 1) + "…" else text
    }

    private const val MAX_LENGTH = 200
}

/** Ocupa o lugar de uma aba que não abriu, com o erro e os caminhos para entender e reportar. */
internal class TabErrorPanel(title: String, error: Throwable) : JBPanelWithEmptyText() {

    init {
        // O Diagnóstico aberto daqui vai direto para os logs, onde está a pilha do erro.
        putClientProperty(DIAGNOSTICS_TAB_PROPERTY, ERROR_LOG_TAB)
        emptyText.apply {
            text = "Não foi possível abrir a aba $title"
            appendLine(TabFailures.describe(error), SimpleTextAttributes.GRAYED_ATTRIBUTES, null)
            appendLine("O erro foi registrado no idea.log.", SimpleTextAttributes.GRAYED_ATTRIBUTES, null)
            appendLine("Ver no Diagnóstico", SimpleTextAttributes.LINK_PLAIN_ATTRIBUTES) { runAction(this@TabErrorPanel, DIAGNOSTICS_ACTION) }
            appendLine("Reportar Bug", SimpleTextAttributes.LINK_PLAIN_ATTRIBUTES) { runAction(this@TabErrorPanel, REPORT_BUG_ACTION) }
        }
    }

    companion object {
        /** IronHawk IA: Diagnóstico, com as abas Compatibilidade e Logs de erro. */
        const val DIAGNOSTICS_ACTION = "IronHawk.Diagnostics"
        const val REPORT_BUG_ACTION = "IronDome.ReportBug"

        /** Propriedade de um componente que escolhe a aba do Diagnóstico aberto a partir dele, pelo nome da aba. */
        const val DIAGNOSTICS_TAB_PROPERTY = "IronHawk.Diagnostics.Tab"
        const val ERROR_LOG_TAB = "ERROR_LOG"
    }
}

private fun runAction(component: JBPanelWithEmptyText, actionId: String) {
    val action = ActionManager.getInstance().getAction(actionId) ?: return
    ActionManager.getInstance().tryToExecute(action, null, component, ActionPlaces.TOOLWINDOW_CONTENT, true)
}
