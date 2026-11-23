package com.mio.irondome.escudo

import com.intellij.ui.ScrollPaneFactory
import com.mio.irondome.base.IronDomeIcons
import com.mio.irondome.base.MechanismTree
import com.mio.irondome.base.Tone
import javax.swing.JComponent
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath

/** A árvore do Escudo. Guarda o que está aberto e a linha escolhida entre uma execução e outra. */
internal class EscudoTree(lookup: () -> EscudoLookup) {

    private val model = DefaultTreeModel(DefaultMutableTreeNode())
    private val renderer = EscudoRenderer(lookup)
    val tree = MechanismTree(model, IronDomeIcons.escudo, Tone.GREEN).apply { cellRenderer = renderer }
    val component: JComponent = ScrollPaneFactory.createScrollPane(tree, true)

    /** Nós já abertos sozinhos uma vez: se o usuário os recolher, ficam recolhidos. */
    private val autoExpanded = mutableSetOf<String>()

    fun selected(): TestNode? = itemOf(tree.lastSelectedPathComponent)

    /** Uma execução nova: o que abriu sozinho deixa de valer. */
    fun reset() = autoExpanded.clear()

    /**
     * Mostra [nodes], mantendo aberto e escolhido o que já estava. [opensAlone] diz, para um nó e o nível dele, se ele
     * abre sozinho na primeira vez que aparece.
     */
    fun show(nodes: List<TestNode>, opensAlone: (TestNode, Int) -> Boolean) {
        val expanded = tree.getExpandedDescendants(TreePath(model.root))?.toList().orEmpty()
            .mapNotNull { itemOf(it.lastPathComponent)?.key }
            .toSet()
        val selected = selected()?.key

        val root = DefaultMutableTreeNode()
        nodes.forEach { root.add(treeNode(it)) }
        model.setRoot(root)

        var selection: TreePath? = null
        for (node in root.preorderEnumeration().toList().drop(1).map { it as DefaultMutableTreeNode }) {
            val item = node.userObject as TestNode
            val path = TreePath(node.path)
            if (item.key == selected) selection = path
            if (node.isLeaf) continue
            if (item.key in expanded || (opensAlone(item, node.level) && autoExpanded.add(item.key))) tree.expandPath(path)
        }
        selection?.let { tree.selectionPath = it }
    }

    /** Escolhe e põe à vista a primeira linha que falhou; devolve se havia uma. */
    fun revealFailure(): Boolean {
        val root = model.root as DefaultMutableTreeNode
        val failed = root.preorderEnumeration().toList().map { it as DefaultMutableTreeNode }
            .firstOrNull { it.isLeaf && itemOf(it)?.outcome == TestOutcome.FAILED } ?: return false
        val path = TreePath(failed.path)
        tree.selectionPath = path
        tree.scrollPathToVisible(path)
        return true
    }

    private fun treeNode(item: TestNode): DefaultMutableTreeNode =
        DefaultMutableTreeNode(item).apply { item.children.forEach { add(treeNode(it)) } }

    companion object {
        fun itemOf(value: Any?) = (value as? DefaultMutableTreeNode)?.userObject as? TestNode
    }
}
