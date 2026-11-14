package com.mio.ironhawk

import com.intellij.ui.TitledSeparator
import java.awt.Component
import java.awt.Container
import javax.swing.AbstractButton
import javax.swing.JComboBox
import javax.swing.JLabel
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
