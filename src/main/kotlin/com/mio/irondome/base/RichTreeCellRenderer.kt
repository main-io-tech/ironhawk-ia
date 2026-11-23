package com.mio.irondome.base

import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.panels.VerticalLayout
import com.intellij.ui.render.RenderingUtil
import com.intellij.ui.scale.JBUIScale
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.FlowLayout
import java.awt.Font
import java.awt.font.TextAttribute
import javax.swing.Icon
import javax.swing.JPanel
import javax.swing.JTree
import javax.swing.UIManager
import javax.swing.event.TreeModelEvent
import javax.swing.event.TreeModelListener
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.TreeCellRenderer
import javax.swing.tree.TreeModel

/**
 * Linha de árvore montada com componentes: ícone, textos, etiquetas arredondadas e barras na primeira linha e,
 * opcionalmente, um texto de apoio embaixo. As partes passam para a linha de baixo e os textos quebram na
 * largura da aba, sem rolagem lateral. A árvore precisa de `rowHeight = 0` para as linhas terem a altura do conteúdo.
 *
 * A árvore pede a mesma linha muitas vezes (para medir, para desenhar, para o tooltip, a cada passagem do mouse).
 * Quem declara [cacheStamp] tem a linha montada uma vez só e reaproveitada até a árvore mudar.
 */
abstract class RichTreeCellRenderer : TreeCellRenderer {

    protected abstract fun describe(row: Row, value: Any?)

    /**
     * Nulo, o padrão, monta a linha a cada pedido. Um valor liga o reaproveitamento: a linha de um nó vale enquanto o
     * modelo da árvore não mudar e este valor for o mesmo objeto. Só serve para quem desenha a linha apenas com o que
     * está no nó, ou com algo que troca de objeto quando muda; quem lê um estado de fora que muda sem refazer a árvore
     * deixa nulo.
     */
    protected open fun cacheStamp(): Any? = null

    private class Key(val value: Any?, val selected: Boolean, val width: Int, val foreground: Int) {
        override fun equals(other: Any?) =
            other is Key && other.value === value && other.selected == selected && other.width == width && other.foreground == foreground

        override fun hashCode() = 31 * (31 * (31 * System.identityHashCode(value) + selected.hashCode()) + width) + foreground
    }

    private val cache = HashMap<Key, JPanel>()
    private var model: TreeModel? = null
    private var stamp: Any? = null
    private var look: List<Any?> = emptyList()
    private val listener = object : TreeModelListener {
        override fun treeNodesChanged(e: TreeModelEvent) = cache.clear()
        override fun treeNodesInserted(e: TreeModelEvent) = cache.clear()
        override fun treeNodesRemoved(e: TreeModelEvent) = cache.clear()
        override fun treeStructureChanged(e: TreeModelEvent) = cache.clear()
    }

    override fun getTreeCellRendererComponent(
        tree: JTree,
        value: Any?,
        selected: Boolean,
        expanded: Boolean,
        leaf: Boolean,
        row: Int,
        hasFocus: Boolean,
    ): Component {
        val depth = (value as? DefaultMutableTreeNode)?.level ?: 1
        val width = ((tree as? MechanismTree)?.rowWidth(depth) ?: tree.width).coerceAtLeast(JBUI.scale(MIN_WIDTH))
        val foreground = RenderingUtil.getForeground(tree, selected)
        val stamp = cacheStamp() ?: return build(value, width, foreground, selected)

        if (tree.model !== model) {
            model?.removeTreeModelListener(listener)
            model = tree.model
            model?.addTreeModelListener(listener)
            cache.clear()
        }
        // Tema, fonte, zoom ou o visual leve mudaram: as linhas guardadas foram montadas com o visual antigo.
        val look = listOf(UIManager.getLookAndFeel(), UIUtil.getTreeFont(), JBUIScale.scale(1f), IronHawkVisual.lite)
        if (stamp !== this.stamp || look != this.look || cache.size > MAX_CACHED) {
            this.stamp = stamp
            this.look = look
            cache.clear()
        }
        return cache.getOrPut(Key(value, selected, width, foreground.rgb)) { build(value, width, foreground, selected) }
    }

    private fun build(value: Any?, width: Int, foreground: Color, selected: Boolean): JPanel {
        val panel = JPanel(VerticalLayout(JBUI.scale(1))).apply {
            isOpaque = false
            border = JBUI.Borders.empty(3, 0)
        }
        val content = Row(FlowRow(width), width, foreground, selected)
        describe(content, value)
        panel.add(content.line)
        content.details.forEach(panel::add)
        panel.toolTipText = content.tooltip
        return panel
    }

    class Row internal constructor(
        internal val line: FlowRow,
        private val width: Int,
        private val foreground: Color,
        private val selected: Boolean,
    ) {
        internal val details = mutableListOf<JPanel>()
        internal var tooltip: String? = null
        private var hasIcon = false

        fun icon(icon: Icon?) {
            if (icon == null) return
            line.add(JBLabel(icon))
            // Só o primeiro ícone, o do começo da linha, recua as linhas de baixo.
            if (!hasIcon && line.componentCount == 1) line.hangingIndent = icon.iconWidth + JBUI.scale(6)
            hasIcon = true
        }

        fun text(text: String, attributes: SimpleTextAttributes = SimpleTextAttributes.REGULAR_ATTRIBUTES) {
            if (text.isEmpty()) return
            line.add(WrapText(text, font(attributes), color(attributes)))
        }

        fun pill(text: String, tone: Tone, bold: Boolean = false) {
            line.add(Pill(text, tone, bold))
        }

        /**
         * A proporção entre os [segments] e, com [label], a contagem ao lado, como "3/6": os dois andam juntos, para a
         * contagem não ficar sozinha na linha de baixo. No visual leve a contagem já diz o mesmo, e a barra não é desenhada.
         */
        fun bar(segments: List<Segment>, width: Int = 56, label: String? = null, attributes: SimpleTextAttributes = SimpleTextAttributes.GRAYED_ATTRIBUTES) {
            val bar = if (IronHawkVisual.lite) null else SegmentBar(segments, width, barHeight = 5)
            val text = label?.takeIf { it.isNotEmpty() }?.let { JBLabel(it).apply { font = font(attributes); foreground = color(attributes) } }
            when {
                bar != null && text != null -> line.add(
                    JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0)).apply {
                        isOpaque = false
                        border = JBUI.Borders.emptyLeft(-JBUI.scale(6))
                        add(bar)
                        add(text)
                    },
                )
                bar != null -> line.add(bar)
                text != null -> line.add(text)
            }
        }

        /**
         * Texto de apoio embaixo, alinhado com o texto da primeira linha; cada chamada acrescenta uma linha. Com
         * [small] falso, o texto fica do tamanho do da árvore, para quando ele é parte do que se lê, e não uma nota.
         */
        fun detail(text: String, attributes: SimpleTextAttributes = SimpleTextAttributes.GRAYED_ATTRIBUTES, small: Boolean = true) {
            val indent = if (hasIcon) JBUI.scale(16) + JBUI.scale(6) else 0
            // Fora do `apply`: lá dentro, `width` seria a largura do painel recém-criado, que é zero.
            val available = width - indent
            val base = if (small) JBFont.small() else UIUtil.getTreeFont()
            details += JPanel(BorderLayout()).apply {
                isOpaque = false
                border = JBUI.Borders.emptyLeft(indent)
                add(WrapText(text, font(attributes, base), color(attributes)).fit(available), BorderLayout.WEST)
            }
        }

        fun tooltip(text: String?) {
            tooltip = text
        }

        private fun font(attributes: SimpleTextAttributes, base: Font = if (attributes.isSmaller) JBFont.small() else UIUtil.getTreeFont()): Font {
            val bold = attributes.style and SimpleTextAttributes.STYLE_BOLD != 0
            val italic = attributes.style and SimpleTextAttributes.STYLE_ITALIC != 0
            val styled = base.deriveFont((if (bold) Font.BOLD else 0) or (if (italic) Font.ITALIC else 0))
            return if (attributes.isStrikeout) styled.deriveFont(mapOf(TextAttribute.STRIKETHROUGH to TextAttribute.STRIKETHROUGH_ON)) else styled
        }

        private fun color(attributes: SimpleTextAttributes): Color = if (selected) foreground else attributes.fgColor ?: foreground
    }

    private companion object {
        const val MIN_WIDTH = 120
        /** Bem acima das linhas visíveis de uma árvore; passou disso, a largura mudou muitas vezes e o cache recomeça. */
        const val MAX_CACHED = 1500
    }
}
