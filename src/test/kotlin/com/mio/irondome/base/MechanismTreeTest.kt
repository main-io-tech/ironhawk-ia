package com.mio.irondome.base

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.tree.ui.DefaultTreeUI
import com.intellij.util.ui.UIUtil
import java.awt.Component
import java.awt.Container
import javax.swing.UIManager
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel

class MechanismTreeTest : BasePlatformTestCase() {

    private class TextRenderer : RichTreeCellRenderer() {
        override fun cacheStamp(): Any = Unit

        override fun describe(row: Row, value: Any?) {
            ((value as DefaultMutableTreeNode).userObject as String).split('|').forEach { row.pill(it, Tone.BLUE) }
        }
    }

    /**
     * O tema da IDE define `Tree.rowHeight` e a troca de UI o reaplica. Com a altura fixa, a parte da linha que passa
     * para baixo era cortada: os testes do Escudo apareciam sem o ENTÃO.
     */
    fun testARowThatWrapsIsAsTallAsItsContentEvenWhenTheThemeSetsARowHeight() {
        val before = UIManager.get("Tree.rowHeight")
        UIManager.put("Tree.rowHeight", 24)
        try {
            val long = (1..12).joinToString("|") { "uma etapa comprida do teste número $it" }
            val root = DefaultMutableTreeNode().apply {
                add(DefaultMutableTreeNode("curta"))
                add(DefaultMutableTreeNode(long))
            }
            val tree = MechanismTree(DefaultTreeModel(root), IronDomeIcons.escudo, Tone.GREEN)
            tree.cellRenderer = TextRenderer()
            // A IDE troca a UI depois de a árvore existir: ao aplicar o tema, a fonte ou o zoom.
            tree.setUI(DefaultTreeUI())
            val scroll = JBScrollPane(tree)
            scroll.addNotify()
            scroll.setSize(600, 400)
            layout(scroll)
            Thread.sleep(150)
            UIUtil.dispatchAllInvocationEvents()
            layout(scroll)

            assertEquals("a linha tem a altura do conteúdo, não a do tema", 0, tree.rowHeight)
            val short = tree.getRowBounds(0).height
            val wrapped = tree.getRowBounds(1).height
            assertTrue("a linha que quebra ($wrapped) é mais alta que a de uma linha só ($short)", wrapped >= short * 2)
        } finally {
            UIManager.put("Tree.rowHeight", before)
        }
    }

    private fun layout(component: Component) {
        component.doLayout()
        if (component is Container) component.components.forEach(::layout)
    }
}
