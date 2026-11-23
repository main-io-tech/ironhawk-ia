package com.mio.irondome.escudo

import com.intellij.openapi.application.ApplicationManager
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase

class EvidenceReaderTest : LightJavaCodeInsightFixtureTestCase() {

    override fun setUp() {
        super.setUp()
        myFixture.addClass("package org.junit.jupiter.api; public @interface DisplayName { String value(); }")
        myFixture.addClass("package org.junit.jupiter.api; public @interface Disabled { String value() default \"\"; }")
        myFixture.addClass(
            """
            package loja;
            import org.junit.jupiter.api.DisplayName;
            import org.junit.jupiter.api.Disabled;
            public class FreteTest {
                @DisplayName("Dado um pedido de 250, quando calcula, então o frete é `GRATIS`")
                void gratis() {
                    String frete = calcula(250);
                    confere(frete);
                }
                @Disabled
                void semProva() { calcula(10); }
                @Disabled("aguardando a regra nova do frete")
                void parado() { assertEquals("GRATIS", calcula(250)); }
                void direto() { assertEquals("GRATIS", calcula(250)); }
                private void confere(String frete) { assertEquals("GRATIS", frete); }
                private String calcula(int valor) { return "GRATIS"; }
                static void assertEquals(Object a, Object b) { }
            }
            """.trimIndent(),
        )
    }

    private fun read(vararg urls: String): Map<String, TestEvidence> =
        ApplicationManager.getApplication().executeOnPooledThread<Map<String, TestEvidence>> { EvidenceReader.read(project, urls.toList()) }.get()

    fun testReadsWhatEachTestChecksFromTheCodeIncludingTheHelpersOfTheClass() {
        val evidence = read("java:test://loja.FreteTest/gratis", "java:test://loja.FreteTest/semProva", "java:test://loja.FreteTest/sumiu")

        assertEquals(setOf("loja.FreteTest#gratis", "loja.FreteTest#semProva"), evidence.keys)
        val gratis = evidence.getValue("loja.FreteTest#gratis")
        assertEquals("Dado um pedido de 250, quando calcula, então o frete é `GRATIS`", gratis.name)
        assertEquals(listOf("confere(frete)"), gratis.checks)

        val empty = evidence.getValue("loja.FreteTest#semProva")
        assertEquals("sem @DisplayName, vale o nome do método", "semProva", empty.name)
        assertTrue(empty.checks.isEmpty())
    }

    fun testTheChecksComeInPlainWordsAndTheStepOfTheClassShowsWhatItAsserts() {
        val evidence = read("java:test://loja.FreteTest/direto", "java:test://loja.FreteTest/gratis")

        val direct = evidence.getValue("loja.FreteTest#direto").proof.single()
        assertEquals("a asserção é dita em português, como a ficha mostra", "calcula(250) é igual a \"GRATIS\"", direct.text)
        assertFalse(direct.code || direct.step)

        val step = evidence.getValue("loja.FreteTest#gratis").proof.single()
        assertTrue("a chamada a um método da classe que verifica é um passo", step.step)
        assertEquals("confere: frete", step.text)
        assertEquals(listOf("frete é igual a \"GRATIS\""), step.inner.map { it.text })
    }

    fun testFollowsTheActorOfAnAcceptanceTestIntoTheSupportClasses() {
        myFixture.addClass(
            """
            package loja.suporte;
            import static org.assertj.Assertions.assertThat;
            public class AnaNoRoteiro {
                String ultimoCodigo;
                public void criaRoteiro() { }
                public void conferirRecusa(String codigo) { assertThat(ultimoCodigo).isEqualTo(codigo); }
                public void anotaSaldo() { ultimoCodigo = "x"; }
            }
            """.trimIndent(),
        )
        myFixture.addClass(
            """
            package loja.suporte;
            public class AnaNaAtividade {
                private final AnaNoRoteiro roteiro;
                public AnaNaAtividade(AnaNoRoteiro roteiro) { this.roteiro = roteiro; }
                public void tentaMoverParaDiaAusente() { }
                public void confereDestinoAusente() { roteiro.conferirRecusa("DIA_NAO_ENCONTRADO"); }
                public void confereNada() { roteiro.anotaSaldo(); }
            }
            """.trimIndent(),
        )
        myFixture.addClass("package org.assertj; public class Assertions { public static Assertions assertThat(Object o) { return null; } public void isEqualTo(Object o) { } }")
        myFixture.addClass(
            """
            package loja;
            import org.junit.jupiter.api.DisplayName;
            import loja.suporte.*;
            public class MoverAceitacaoTest {
                AnaNoRoteiro ana = new AnaNoRoteiro();
                AnaNaAtividade anaNaAtividade = new AnaNaAtividade(ana);
                @DisplayName("DADO uma visita, QUANDO Ana tenta mover para um dia ausente, ENTÃO recusa com DIA_NAO_ENCONTRADO")
                void recusa() {
                    ana.criaRoteiro();
                    anaNaAtividade.tentaMoverParaDiaAusente();
                    anaNaAtividade.confereDestinoAusente();
                }
                void vazio() {
                    anaNaAtividade.confereNada();
                }
            }
            """.trimIndent(),
        )

        val evidence = read("java:test://loja.MoverAceitacaoTest/recusa", "java:test://loja.MoverAceitacaoTest/vazio")

        val recusa = evidence.getValue("loja.MoverAceitacaoTest#recusa")
        val step = recusa.proof.single()
        assertEquals("confere destino ausente", step.text)
        assertEquals("a asserção está dois métodos abaixo, em outra classe", listOf("ultimoCodigo é igual a \"DIA_NAO_ENCONTRADO\""), step.inner.map { it.text })

        val vazio = evidence.getValue("loja.MoverAceitacaoTest#vazio")
        assertTrue("um passo sem asserção dentro não é verificação", vazio.checks.isEmpty())
    }

    fun testTheExecutionsOfAParameterizedTestShareOneEvidence() {
        val evidence = read("java:test://loja.FreteTest/gratis", "java:test://loja.FreteTest/gratis(int)[2]")

        assertEquals(setOf("loja.FreteTest#gratis"), evidence.keys)
    }
}
