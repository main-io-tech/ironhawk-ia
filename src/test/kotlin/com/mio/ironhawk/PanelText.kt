package com.mio.ironhawk

import com.intellij.ui.TitledSeparator
import java.awt.Component
import java.awt.Container
import javax.swing.AbstractButton
import javax.swing.JComboBox
import javax.swing.JLabel
import javax.swing.JTabbedPane
import javax.swing.text.JTextComponent

/** Os textos à vista num painel: rótulos, botões, campos, separadores e os itens das listas de escolha. */
fun panelText(root: Component): String = buildString {
    fun walk(component: Component) {
        if (!component.isVisible) return
        when (component) {
            is TitledSeparator -> appendLine(component.text)
            is JLabel -> appendLine(component.text.orEmpty())
            is AbstractButton -> appendLine(component.text.orEmpty())
            is JTextComponent -> appendLine(component.text.orEmpty())
            is JComboBox<*> -> (0 until component.itemCount).forEach { index ->
                val item = component.getItemAt(index)
                appendLine(item?.let { runCatching { it.javaClass.getMethod("getTitle").invoke(it) }.getOrNull() ?: it }?.toString().orEmpty())
            }
        }
        if (component is Container && component !is TitledSeparator) component.components.forEach(::walk)
    }
    walk(root)
}

private fun findTabs(component: Component): JTabbedPane? {
    if (component is JTabbedPane) return component
    if (component is Container) component.components.forEach { child -> findTabs(child)?.let { return it } }
    return null
}

/** Os títulos das abas internas de um painel, na ordem; vazio se ele não tem abas. */
fun tabTitles(root: Component): List<String> = findTabs(root)?.let { tabs -> (0 until tabs.tabCount).map(tabs::getTitleAt) }.orEmpty()

/** O título da aba aberta agora num painel com abas internas. */
fun selectedTab(root: Component): String? = findTabs(root)?.let { tabs -> tabs.getTitleAt(tabs.selectedIndex) }

/** O texto de todas as abas internas: só a aba aberta fica à vista, então abre uma por vez e volta para a que estava. */
fun allTabsText(root: Component): String {
    val tabs = findTabs(root) ?: return panelText(root)
    val original = tabs.selectedIndex
    return try {
        (0 until tabs.tabCount).joinToString("\n") { index ->
            tabs.selectedIndex = index
            panelText(root)
        }
    } finally {
        tabs.selectedIndex = original
    }
}
