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
        emptyText.apply {
            text = "Não foi possível abrir a aba $title"
            appendLine(TabFailures.describe(error), SimpleTextAttributes.GRAYED_ATTRIBUTES, null)
            appendLine("O erro foi registrado no idea.log.", SimpleTextAttributes.GRAYED_ATTRIBUTES, null)
            appendLine("Ver Logs de Erro", SimpleTextAttributes.LINK_PLAIN_ATTRIBUTES) { runAction(this@TabErrorPanel, ERROR_LOG_ACTION) }
            appendLine("Verificar Compatibilidade", SimpleTextAttributes.LINK_PLAIN_ATTRIBUTES) { runAction(this@TabErrorPanel, COMPATIBILITY_ACTION) }
            appendLine("Reportar Bug", SimpleTextAttributes.LINK_PLAIN_ATTRIBUTES) { runAction(this@TabErrorPanel, REPORT_BUG_ACTION) }
        }
    }

    companion object {
        const val COMPATIBILITY_ACTION = "IronDome.CheckCompatibility"
        const val REPORT_BUG_ACTION = "IronDome.ReportBug"
        const val ERROR_LOG_ACTION = "IronDome.ShowErrorLog"
    }
}

private fun runAction(component: JBPanelWithEmptyText, actionId: String) {
    val action = ActionManager.getInstance().getAction(actionId) ?: return
    ActionManager.getInstance().tryToExecute(action, null, component, ActionPlaces.TOOLWINDOW_CONTENT, true)
}
