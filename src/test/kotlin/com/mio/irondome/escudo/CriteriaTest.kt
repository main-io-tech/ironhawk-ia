package com.mio.irondome.escudo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CriteriaTest {

    private fun parse(text: String, file: String = "roteiro.yaml") = CriteriaCatalogParser.parse(file, text.trimIndent())

    private fun test(key: String, outcome: TestOutcome, vararg tags: String) = TestNode(
        TestKind.TEST, key, key, null, null, null, null, null, emptyList(), outcome, tags = tags.toList(),
    )

    private fun catalog(vararg criteria: Criterion) = CriteriaCatalog(criteria.toList(), emptyList())

    private fun criterion(id: String, vararg requires: Level) = Criterion(id, "Título $id", null, null, requires.toSet(), "roteiro.yaml", 1)

    @Test
    fun readsTheCatalogWithOptionalFields() {
        val result = parse(
            """
            # critérios do roteiro
            - id: ROT-03
              titulo: Mover para um dia que não existe é recusado
              grupo: Roteiro          # agrupa na aba
              origem: MAI-184
              exige: [aceitacao, unidade]

            - id: ROT-04
              titulo: "Reposicionar: mantém a ordem"
            """,
        )

        assertTrue(result.errors.toString(), result.errors.isEmpty())
        val first = result.criteria[0]
        assertEquals("ROT-03", first.id)
        assertEquals("Roteiro", first.group)
        assertEquals("MAI-184", first.origin)
        assertEquals(setOf(Level.ACCEPTANCE, Level.UNIT), first.requires)
        assertEquals(2, result.criteria.size)
        assertEquals("Reposicionar: mantém a ordem", result.criteria[1].title)
        assertNull(result.criteria[1].group)
        assertEquals("Roteiro", result.criteria[1].groupName)
        assertTrue(result.criteria[1].requires.isEmpty())
    }

    @Test
    fun reportsContractErrorsWithTheLine() {
        val result = parse(
            """
            - id: ROT-01
              titulo: ok
              cor: azul
            - id: com espaço
              titulo: x
            - titulo: sem id
            - id: ROT-02
              titulo: y
              exige: [e2e]
            - id: nenhum
              titulo: z
            - id: ROT-05
              titulo: "sem fechar
            """,
        )

        val messages = result.errors.map { "${it.line}: ${it.message}" }
        assertTrue(messages.toString(), messages.any { it.startsWith("3: campo desconhecido") })
        assertTrue(messages.toString(), messages.any { it.startsWith("4: id inválido") })
        assertTrue(messages.toString(), messages.any { it.startsWith("6: critério sem id") })
        assertTrue(messages.toString(), messages.any { it.startsWith("9: nível desconhecido") })
        assertTrue(messages.toString(), messages.any { it.contains("reservado") })
        assertTrue(messages.toString(), messages.any { it.startsWith("13: valor inválido em titulo") })
        assertEquals(listOf("ROT-01"), result.criteria.map { it.id })
    }

    @Test
    fun repeatedIdsAcrossFilesAreContractErrors() {
        val merged = CriteriaCatalog.merge(
            listOf(
                parse("- id: ROT-01\n  titulo: a", "roteiro.yaml"),
                parse("- id: rot-01\n  titulo: b", "acesso.yaml"),
            ),
        )

        assertEquals(listOf("roteiro.yaml"), merged.criteria.map { it.file })
        assertEquals(1, merged.errors.size)
        assertTrue(merged.errors.single().message.contains("repetido"))
        assertEquals("ROT-01", merged.find("rot-01")?.id)
    }

    @Test
    fun readsTheCriterionAndLevelTagsAndTheNoneMarker() {
        val tags = CriterionTags.of(listOf("criterio:ROT-03", "Critério:ROT-04", "criterio:rot-03", "nivel:integração", "lento"))
        assertEquals(listOf("ROT-03", "ROT-04"), tags.ids)
        assertEquals(Level.INTEGRATION, tags.level)
        assertFalse(tags.notApplicable)

        val none = CriterionTags.of(listOf("criterio:nenhum"))
        assertTrue(none.ids.isEmpty() && none.notApplicable)
        assertNull(CriterionTags.of(listOf("lento")).level)
    }

    @Test
    fun findsCriterionReferencesInJavaKotlinAndGherkin() {
        val ids = CriterionTags.idsIn(
            """
            @Tag("criterio:ROT-03") @Tag("nivel:unidade")
            @Tag("criterio:nenhum")
            @criterio:ROT-04 @nivel:aceitacao
            nada criterio:  e Xcriterio:ZZ
            """.trimIndent(),
        )

        assertEquals(setOf("ROT-03", "ROT-04"), ids)
    }

    @Test
    fun statusFollowsTheTestsAndTheRequiredLevels() {
        val catalog = catalog(
            criterion("A"), criterion("B"), criterion("C", Level.ACCEPTANCE), criterion("D"), criterion("E"), criterion("F"),
        )
        val tests = listOf(
            test("t1", TestOutcome.PASSED, "criterio:A"),
            test("t2", TestOutcome.PASSED, "criterio:B"), test("t3", TestOutcome.FAILED, "criterio:B"),
            test("t4", TestOutcome.PASSED, "criterio:C", "nivel:unidade"),
            test("t5", TestOutcome.NOT_RUN, "criterio:D"),
            test("t6", TestOutcome.IGNORED, "criterio:F"),
        )

        val report = Criteria.report(catalog, tests, sourceReferences = setOf("e"))
        val status = report.coverages.associate { it.criterion.id to it.status }

        assertEquals(CriterionStatus.MET, status["A"])
        assertEquals(CriterionStatus.FAILING, status["B"])
        assertEquals(CriterionStatus.PARTIAL, status["C"])
        assertEquals(setOf(Level.ACCEPTANCE), report.coverages.single { it.criterion.id == "C" }.missing)
        assertEquals(CriterionStatus.NOT_RUN, status["D"])
        assertEquals("citado no código, mas não rodou", CriterionStatus.NOT_RUN, status["E"])
        assertEquals("ignorado não conta como cobertura", CriterionStatus.NOT_RUN, status["F"])
    }

    @Test
    fun aCriterionWithoutAnyTestIsUntestedAndTheRequiredLevelCompletesIt() {
        val catalog = catalog(criterion("A"), criterion("C", Level.ACCEPTANCE, Level.UNIT))
        val tests = listOf(
            test("t1", TestOutcome.PASSED, "criterio:C", "nivel:aceitacao"),
            test("t2", TestOutcome.PASSED, "criterio:C", "nivel:unidade"),
        )

        val report = Criteria.report(catalog, tests)

        assertEquals(CriterionStatus.UNTESTED, report.coverages[0].status)
        assertEquals(CriterionStatus.MET, report.coverages[1].status)
        assertEquals(setOf(Level.ACCEPTANCE, Level.UNIT), report.coverages[1].levels)
        assertEquals(1, report.count(CriterionStatus.UNTESTED))
    }

    @Test
    fun separatesOrphanTagsTestsWithoutCriterionAndNotApplicableOnes() {
        val catalog = catalog(criterion("A"))
        val tests = listOf(
            test("t1", TestOutcome.PASSED, "criterio:A", "criterio:Z-9"),
            test("t2", TestOutcome.PASSED),
            test("t3", TestOutcome.PASSED, "criterio:nenhum"),
            test("t4", TestOutcome.PASSED, "criterio:z-9"),
        )

        val report = Criteria.report(catalog, tests)

        assertEquals(listOf("Z-9"), report.orphans.map { it.id })
        assertEquals(listOf("t1", "t4"), report.orphans.single().tests.map { it.key })
        assertEquals(listOf("t2"), report.withoutCriterion.map { it.key })
        assertEquals(listOf("t3"), report.notApplicable.map { it.key })
    }

    @Test
    fun theCriteriaViewGroupsBySubjectAndRepeatsATestThatCoversTwoCriteria() {
        val catalog = CriteriaCatalog(
            listOf(
                Criterion("A", "Primeiro", "Roteiro", null, emptySet(), "roteiro.yaml", 1),
                Criterion("B", "Segundo", "Roteiro", null, emptySet(), "roteiro.yaml", 5),
                Criterion("C", "Terceiro", "Acesso", null, emptySet(), "acesso.yaml", 1),
            ),
            emptyList(),
        )
        val shared = test("t1", TestOutcome.PASSED, "criterio:A", "criterio:B")

        val view = CriteriaView.build(Criteria.report(catalog, listOf(shared)), emptyList())

        assertEquals(listOf("Acesso", "Roteiro"), view.map { it.label })
        val roteiro = view.last()
        assertEquals(listOf("A", "B"), roteiro.children.map { it.classKey })
        assertEquals(listOf("crit:a|t1", "crit:b|t1"), roteiro.children.flatMap { it.tests }.map { it.key })
        assertEquals(CriterionStatus.MET, roteiro.children.first().coverage?.status)
        assertEquals(CriterionStatus.UNTESTED, view.first().children.single().coverage?.status)
    }
}
