package com.mio.irondome.escudo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class CucumberResultsTest {

    private val feature = """
        # language: pt
        @roteiro
        Funcionalidade: Mover atividade para outro dia do roteiro

          Contexto:
            Dado um roteiro

          @criterio:ROT-03 @nivel:aceitacao
          Cenário: Mover a visita para um dia livre
            Dado que o primeiro dia tem uma visita

          Cenário: Reposicionar uma atividade
            Dado que o primeiro dia tem um jantar
    """.trimIndent()

    private val xml = """
        <?xml version="1.0" encoding="UTF-8"?>
        <testsuite name="Cucumber" tests="3">
          <testcase classname="Mover atividade para outro dia do roteiro" name="Mover a visita para um dia livre" time="0.5"/>
          <testcase classname="Mover atividade para outro dia do roteiro" name="Reposicionar uma atividade" time="1.0">
            <failure message="esperado 1, veio 2">AssertionError</failure>
          </testcase>
          <testcase classname="Outra" name="Sem feature conhecida" time="0"><skipped/></testcase>
        </testsuite>
    """.trimIndent()

    @Test
    fun theScenarioTagsIncludeTheFeatureTags() {
        val file = CucumberResults.parseFeature(feature)!!

        assertEquals("Mover atividade para outro dia do roteiro", file.name)
        assertEquals(listOf("roteiro", "criterio:ROT-03", "nivel:aceitacao"), file.scenarios[0].tags)
        assertEquals(listOf("roteiro"), file.scenarios[1].tags)
    }

    @Test
    fun theXmlBecomesScenariosWithOutcomesAndTags() {
        val group = CucumberResults.build(listOf(xml), listOf(CucumberResults.parseFeature(feature)!!))!!

        assertEquals(TestKind.KEY, group.kind)
        val moved = group.children.first { it.label.startsWith("Mover") }
        assertEquals(Convention.OK, moved.convention)
        assertEquals(listOf(TestOutcome.PASSED, TestOutcome.FAILED), moved.children.map { it.outcome })
        assertEquals("esperado 1, veio 2", moved.children[1].errorMessage)
        assertEquals(500L, moved.children[0].durationMillis)
        assertEquals(listOf("roteiro", "criterio:ROT-03", "nivel:aceitacao"), moved.children[0].tags)
        assertEquals(TestOutcome.IGNORED, group.children.first { it.label == "Outra" }.children.single().outcome)
        assertEquals(emptyList<String>(), group.children.first { it.label == "Outra" }.children.single().tags)
    }

    @Test
    fun aScenarioTaggedWithACriterionCountsAsCoverage() {
        val group = CucumberResults.build(listOf(xml), listOf(CucumberResults.parseFeature(feature)!!))!!
        val catalog = CriteriaCatalogParser.parse("roteiro.yaml", "- id: ROT-03\n  titulo: Mover para um dia livre\n")

        val report = Criteria.report(catalog, group.tests)

        assertEquals(CriterionStatus.MET, report.coverages.single().status)
    }

    @Test
    fun withoutScenariosThereIsNothingToShow() {
        assertNull(CucumberResults.build(listOf("<testsuite/>"), emptyList()))
        assertNull(CucumberResults.build(listOf("não é xml"), emptyList()))
        assertNotNull(CucumberResults.parseFeature("Feature: x\n  Scenario: y"))
    }
}
