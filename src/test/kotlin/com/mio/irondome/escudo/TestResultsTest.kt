package com.mio.irondome.escudo

import com.intellij.execution.testframework.sm.runner.SMTestProxy
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase

class TestResultsTest : LightJavaCodeInsightFixtureTestCase() {

    private lateinit var names: JUnitDisplayNames

    override fun setUp() {
        super.setUp()
        myFixture.addClass("package org.junit.jupiter.api; public @interface DisplayName { String value(); }")
        myFixture.addClass(
            """
            package loja;
            import org.junit.jupiter.api.DisplayName;
            @DisplayName("[Pedido] Cálculo de frete")
            public class FreteTest {
                @DisplayName("cobra frete grátis acima de 200") void gratis() { }
                void semNome() { }
                @DisplayName("Quando o CEP é inválido")
                class CepInvalido {
                    @DisplayName("recusa o pedido") void recusa() { }
                }
            }
            """.trimIndent(),
        )
        myFixture.addClass(
            "package loja; @org.junit.jupiter.api.DisplayName(\"[pedido] Cupom de desconto\") public class CupomTest { void aplica() { } }",
        )
        myFixture.addClass(
            "package loja; @org.junit.jupiter.api.DisplayName(\"[Estoque] Reserva de itens\") public class ReservaTest { void reserva() { } }",
        )
        myFixture.addClass(
            "package loja; @org.junit.jupiter.api.DisplayName(\"Nota fiscal\") public class NotaTest { void emite() { } }",
        )
        myFixture.addClass("package loja; public class AbcTest { void um() { } void parametros(int n) { } }")
        names = JUnitDisplayNames(project)
    }

    fun testClassesComeInAlphabeticalOrderWithTheirDisplayNameAsWritten() {
        val root = SMTestProxy.SMRootTestProxy()
        val engine = root.child(suite("JUnit Jupiter", null))
        engine.child(testClass("CupomTest", passed("aplica()", "loja.CupomTest/aplica")))
        val frete = engine.child(suite("FreteTest", "java:suite://loja.FreteTest"))
        frete.child(passed("gratis()", "loja.FreteTest/gratis"))
        frete.child(failed("semNome()", "loja.FreteTest/semNome", "esperado 0, veio 15"))
        val nested = frete.child(suite("CepInvalido", "java:suite://loja.FreteTest\$CepInvalido"))
        nested.child(passed("recusa()", "loja.FreteTest\$CepInvalido/recusa"))
        engine.child(testClass("ReservaTest", passed("reserva()", "loja.ReservaTest/reserva")))
        engine.child(testClass("NotaTest", passed("emite()", "loja.NotaTest/emite")))
        engine.child(testClass("AbcTest", passed("um()", "loja.AbcTest/um")))

        val classes = TestResults.build(root, names::of)

        assertEquals(
            listOf("[Estoque] Reserva de itens", "[Pedido] Cálculo de frete", "[pedido] Cupom de desconto", "AbcTest", "Nota fiscal"),
            classes.map { it.label },
        )
        assertTrue(classes.all { it.kind == TestKind.CLASS && it.topLevel })
        assertEquals("sem tag nenhuma, o grupo Sem tag não entra: ficam as classes", classes.map { it.label }, TestResults.byTagOrClasses(classes).map { it.label })
        assertNull(classes.single { it.label == "AbcTest" }.codeName)

        val freteNode = classes.single { it.codeName == "FreteTest" }
        assertEquals(TestOutcome.FAILED, freteNode.outcome)
        assertEquals(
            listOf("cobra frete grátis acima de 200", "semNome()", "Quando o CEP é inválido"),
            freteNode.children.map { it.label },
        )
        assertEquals("gratis()", freteNode.children[0].codeName)
        assertNull(freteNode.children[1].codeName)
        assertEquals(TestKind.CLASS, freteNode.children[2].kind)
        assertFalse("classe aninhada não é de topo", freteNode.children[2].topLevel)
        assertEquals("só as classes de topo contam no cabeçalho", 1, freteNode.classes.size)
        assertEquals("recusa o pedido", freteNode.children[2].children.single().label)
        assertEquals(2, freteNode.count(TestOutcome.PASSED))
        assertEquals(1, freteNode.count(TestOutcome.FAILED))
        assertEquals("esperado 0, veio 15", freteNode.children[1].errorMessage)
    }

    fun testParameterizedInvocationsKeepTheNameGivenByJUnit() {
        val root = SMTestProxy.SMRootTestProxy()
        val abc = root.child(suite("AbcTest", "java:suite://loja.AbcTest"))
        val method = abc.child(suite("parametros(int)", "java:test://loja.AbcTest/parametros"))
        method.child(passed("[1] n=1", "loja.AbcTest/parametros"))
        method.child(failed("[2] n=2", "loja.AbcTest/parametros", "falhou"))

        val group = TestResults.build(root, names::of).single().children.single()

        assertEquals(TestKind.GROUP, group.kind)
        assertEquals(listOf("[1] n=1", "[2] n=2"), group.children.map { it.label })
        assertEquals(TestOutcome.FAILED, group.outcome)
        assertEquals(2, group.tests.size)
    }

    fun testTheTagsOfTheMethodTheClassAndTheBaseClassAddUpAndEachOneGroups() {
        myFixture.addClass("package org.junit.jupiter.api; @java.lang.annotation.Repeatable(Tags.class) public @interface Tag { String value(); }")
        myFixture.addClass("package org.junit.jupiter.api; public @interface Tags { Tag[] value(); }")
        myFixture.addClass("package loja; @org.junit.jupiter.api.Tag(\"lento\") public @interface Lento { }")
        myFixture.addClass("package loja; @org.junit.jupiter.api.Tag(\"aceitacao\") public abstract class BaseAceitacao { }")
        myFixture.addClass(
            """
            package loja;
            import org.junit.jupiter.api.*;
            @DisplayName("Criar conta")
            @Tag("funcionalidade")
            public class ContaTest extends BaseAceitacao {
                void cria() { }
                @Tag("tecnico") void valida() { }
                @Lento void importa() { }
                @Tags({@Tag("tecnico"), @Tag("seguranca")}) void criptografa() { }
                class Aninhada { void herda() { } }
            }
            """.trimIndent(),
        )
        val root = SMTestProxy.SMRootTestProxy()
        val conta = root.child(suite("ContaTest", "java:suite://loja.ContaTest"))
        listOf("cria", "valida", "importa", "criptografa").forEach { conta.child(passed("$it()", "loja.ContaTest/$it")) }
        conta.child(suite("Aninhada", "java:suite://loja.ContaTest\$Aninhada")).child(passed("herda()", "loja.ContaTest\$Aninhada/herda"))
        root.child(testClass("AbcTest", passed("um()", "loja.AbcTest/um")))

        val tagged = TestResults.byTagOrClasses(TestResults.build(root, names::of, names::tagsOf))

        assertEquals(
            "como no JUnit: as do método, as da classe e as herdadas da classe base",
            listOf("tecnico", "funcionalidade", "aceitacao"),
            names.tagsOf("java:test://loja.ContaTest/valida"),
        )
        assertEquals(
            "a classe aninhada soma as da classe de fora",
            listOf("funcionalidade", "aceitacao"),
            names.tagsOf("java:test://loja.ContaTest\$Aninhada/herda"),
        )
        assertEquals(listOf("aceitacao", "funcionalidade", "lento", "seguranca", "tecnico", TestResults.NO_TAG), tagged.map { it.label })
        assertTrue(tagged.all { it.kind == TestKind.TAG })
        fun testsIn(tag: String) = tagged.single { it.label == tag }.tests.map { it.codeName ?: it.label }
        assertEquals(listOf("cria()", "valida()", "importa()", "criptografa()", "herda()"), testsIn("funcionalidade"))
        assertEquals("a tag da classe base vale para todos os testes da classe", testsIn("funcionalidade"), testsIn("aceitacao"))
        assertEquals(listOf("criptografa()"), testsIn("seguranca"))
        assertEquals(listOf("valida()", "criptografa()"), testsIn("tecnico"))
        assertEquals(listOf("importa()"), testsIn("lento"))
        assertEquals(listOf("um()"), testsIn(TestResults.NO_TAG))
        val tecnico = tagged.single { it.label == "tecnico" }
        assertEquals(TestKind.CLASS, tecnico.children.single().kind)
        assertEquals("Criar conta", tecnico.children.single().label)
        assertEquals(1, tecnico.classes.size)
        val funcionalidade = tagged.single { it.label == "funcionalidade" }
        assertFalse(
            "a mesma classe em duas tags tem chaves diferentes na árvore",
            tecnico.children.single().key == funcionalidade.children.single().key,
        )
    }

    private fun SMTestProxy.child(proxy: SMTestProxy): SMTestProxy = proxy.also(::addChild)

    private fun testClass(name: String, vararg tests: SMTestProxy) =
        suite(name, "java:suite://loja.$name").apply { tests.forEach(::addChild) }

    private fun suite(name: String, url: String?) = SMTestProxy(name, true, url).apply { setSuiteStarted() }

    private fun passed(name: String, path: String) = SMTestProxy(name, false, "java:test://$path").apply {
        setStarted()
        setFinished()
    }

    private fun failed(name: String, path: String, message: String) = SMTestProxy(name, false, "java:test://$path").apply {
        setStarted()
        setTestFailed(message, "at loja.FreteTest.semNome(FreteTest.java:7)", false)
    }
}
