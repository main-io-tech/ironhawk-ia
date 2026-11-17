package com.mio.irondome.escudo

import java.time.Instant
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
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
    fun theXmlBecomesFeaturesWithScenariosOutcomesAndTags() {
        val features = CucumberResults.build(listOf(xml), listOf(CucumberResults.parseFeature(feature)!!))

        assertTrue(features.all { it.kind == TestKind.CLASS && it.cucumber })
        val moved = features.first { it.label.startsWith("Mover") }
        assertEquals(Convention.OK, moved.convention)
        assertEquals(listOf(TestOutcome.PASSED, TestOutcome.FAILED), moved.children.map { it.outcome })
        assertTrue(moved.children.all { it.cucumber })
        assertEquals("esperado 1, veio 2", moved.children[1].errorMessage)
        assertEquals(500L, moved.children[0].durationMillis)
        assertEquals(listOf("roteiro", "criterio:ROT-03", "nivel:aceitacao"), moved.children[0].tags)
        assertEquals(TestOutcome.IGNORED, features.first { it.label == "Outra" }.children.single().outcome)
        assertEquals(emptyList<String>(), features.first { it.label == "Outra" }.children.single().tags)
    }

    @Test
    fun theScenariosEnterTheTagGroupsWithTheFeatureTagsAndTheUntaggedOnesGoToNoTag() {
        val features = CucumberResults.build(listOf(xml), listOf(CucumberResults.parseFeature(feature)!!))

        val byTag = TestResults.byTag(features)

        assertEquals(listOf("criterio:ROT-03", "nivel:aceitacao", "roteiro", TestResults.NO_TAG), byTag.map { it.label })
        assertEquals(2, byTag.single { it.label == "roteiro" }.tests.size)
        assertEquals(listOf("Sem feature conhecida"), byTag.single { it.label == TestResults.NO_TAG }.tests.map { it.label })
    }

    @Test
    fun aScenarioTaggedWithACriterionCountsAsCoverage() {
        val features = CucumberResults.build(listOf(xml), listOf(CucumberResults.parseFeature(feature)!!))
        val catalog = CriteriaCatalogParser.parse("roteiro.yaml", "- id: ROT-03\n  titulo: Mover para um dia livre\n")

        val report = Criteria.report(catalog, features.flatMap { it.tests })

        assertEquals(CriterionStatus.MET, report.coverages.single().status)
    }

    @Test
    fun aScenarioRepeatedInAnOlderXmlCountsOnlyOnceWithTheNewestResult() {
        val newer = """
            <testsuite>
              <testcase classname="Mover atividade para outro dia do roteiro" name="Reposicionar uma atividade" time="1"/>
              <testcase classname="Esquema" name="Linha" time="1"/>
              <testcase classname="Esquema" name="Linha" time="1"/>
            </testsuite>
        """.trimIndent()

        val features = CucumberResults.build(listOf(newer, xml), listOf(CucumberResults.parseFeature(feature)!!))
        val moved = features.first { it.label.startsWith("Mover") }

        assertEquals(listOf("Reposicionar uma atividade", "Mover a visita para um dia livre"), moved.children.map { it.label })
        assertEquals("vale o resultado do XML mais novo", TestOutcome.PASSED, moved.children[0].outcome)
        assertEquals("as linhas de um esquema no mesmo XML ficam todas", 2, features.first { it.label == "Esquema" }.tests.size)
        assertEquals(1, features.first { it.label == "Outra" }.tests.size)
    }

    @Test
    fun theAgeOfTheXmlIsShownInMinutesHoursOrDate() {
        val written = Instant.parse("2026-11-12T14:32:00Z")
        fun age(minutes: Long) = CucumberResults.age(written, written.plusSeconds(minutes * 60), ZoneOffset.UTC)

        assertEquals("há menos de 1 min", age(0))
        assertEquals("há 5 min", age(5))
        assertEquals("há 3 h", age(3 * 60 + 10))
        assertEquals("em 12/11 14:32", age(26 * 60))
    }

    @Test
    fun withoutScenariosThereIsNothingToShow() {
        assertTrue(CucumberResults.build(listOf("<testsuite/>"), emptyList()).isEmpty())
        assertTrue(CucumberResults.build(listOf("não é xml"), emptyList()).isEmpty())
        assertNotNull(CucumberResults.parseFeature("Feature: x\n  Scenario: y"))
    }
}
