package com.mio.irondome.escudo

import com.intellij.execution.testframework.sm.runner.SMTestProxy
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import com.mio.irondome.base.MechanismTree
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.TreePath

class TestsPanelTest : LightJavaCodeInsightFixtureTestCase() {

    override fun setUp() {
        super.setUp()
        myFixture.addClass("package org.junit.jupiter.api; public @interface DisplayName { String value(); }")
        myFixture.addClass("package org.junit.jupiter.api; public @interface Tag { String value(); }")
        myFixture.addClass(
            """
            package loja;
            import org.junit.jupiter.api.DisplayName;
            import org.junit.jupiter.api.Tag;
            @DisplayName("Frete")
            public class FreteTest {
                @Tag("frete") @DisplayName("DADO um pedido de 250, QUANDO calcula o frete, ENTÃO o frete é grátis")
                void gratis() { }
                @Tag("frete") @DisplayName("cobra 10 num pedido de 50")
                void cobra() { }
                @Tag("pagamento") @DisplayName("pedido pago fica pago")
                void paga() { }
                @Tag("entrega") void despacha() { }
                void semTag() { }
            }
            """.trimIndent(),
        )
    }

    /** A execução de `FreteTest`, com [failing] falhando. */
    private fun execution(vararg failing: String): SMTestProxy.SMRootTestProxy {
        val root = SMTestProxy.SMRootTestProxy()
        val suite = SMTestProxy("FreteTest", true, "java:suite://loja.FreteTest")
        root.addChild(suite)
        root.setStarted()
        suite.setStarted()
        for (method in listOf("gratis", "cobra", "paga", "despacha", "semTag")) {
            val test = SMTestProxy(method, false, "java:test://loja.FreteTest/$method")
            suite.addChild(test)
            test.setStarted()
            if (method in failing) test.setTestFailed("esperava 10, veio 0", "pilha", false) else test.setFinished()
        }
        suite.setFinished()
        root.setFinished()
        return root
    }

    private fun panel(root: SMTestProxy.SMRootTestProxy): TestsPanel =
        TestsPanel(project).also { Disposer.register(testRootDisposable, it) }.also { it.preview(root, emptyMap()) }

    private fun groups(tree: MechanismTree) = (tree.model.root as DefaultMutableTreeNode).children().toList().map { it as DefaultMutableTreeNode }

    private fun label(node: DefaultMutableTreeNode) = (node.userObject as TestNode).label

    private fun leaves(node: DefaultMutableTreeNode) =
        node.preorderEnumeration().toList().map { it as DefaultMutableTreeNode }.filter { it.isLeaf }.map(::label)

    fun testTheTreeGroupsByTagWithTheDisplayNameAsWritten() {
        val groups = groups(panel(execution()).tree())

        assertEquals(listOf("entrega", "frete", "pagamento", TestResults.NO_TAG), groups.map(::label))
        assertEquals(
            "o @DisplayName aparece como está escrito, sem ser quebrado em Dado/Quando/Então",
            listOf("DADO um pedido de 250, QUANDO calcula o frete, ENTÃO o frete é grátis", "cobra 10 num pedido de 50"),
            leaves(groups[1]),
        )
        assertEquals("sem @DisplayName, vale o nome que o JUnit deu", listOf("semTag"), leaves(groups[3]))
    }

    fun testWithManyGroupsOnlyTheOneWithAFailureOpensAndTheFailureIsSelected() {
        val tree = panel(execution("cobra")).tree()
        val groups = groups(tree)

        assertEquals(listOf("frete"), groups.filter { tree.isExpanded(TreePath(it.path)) }.map(::label))
        assertEquals("cobra 10 num pedido de 50", EscudoTree.itemOf(tree.lastSelectedPathComponent)?.label)
    }

    fun testWithEveryTestPassingNothingIsSelected() {
        assertNull(panel(execution()).tree().lastSelectedPathComponent)
    }
}
