package com.mio.irondome.base

import com.intellij.icons.AllIcons
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Cursor
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * Uma seção que abre e fecha pelo título, com a seta ao lado: o conteúdo fica em [host], embaixo do título. Faz o
 * papel do `HideableDecorator` da plataforma, que saiu de uso. [onToggle] recebe o estado novo quando o usuário clica.
 */
class Collapsible(
    private val host: JPanel,
    title: String,
    expanded: Boolean,
    private val onToggle: (Boolean) -> Unit = {},
) {

    private var content: JComponent? = null
    private var on = expanded

    private val header = JBLabel(title).apply {
        font = JBFont.label().asBold()
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        border = JBUI.Borders.empty(2, 0)
        addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                setOn(!on)
                onToggle(on)
            }
        })
    }

    init {
        host.layout = BorderLayout()
        host.add(header, BorderLayout.NORTH)
        refresh()
    }

    fun setContentComponent(component: JComponent) {
        content?.let(host::remove)
        content = component
        host.add(component, BorderLayout.CENTER)
        refresh()
    }

    fun setOn(expanded: Boolean) {
        on = expanded
        refresh()
    }

    fun isExpanded(): Boolean = on

    fun setTitle(title: String) {
        header.text = title
    }

    private fun refresh() {
        header.icon = if (on) AllIcons.General.ArrowDown else AllIcons.General.ArrowRight
        header.toolTipText = if (on) "Recolher" else "Expandir"
        content?.isVisible = on
        host.revalidate()
        host.repaint()
    }
}
