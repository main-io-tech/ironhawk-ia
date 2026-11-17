package com.mio.irondome.escudo

import java.text.Collator
import java.util.Locale

/**
 * A organização por critério do Escudo: `Grupo > Critério > testes que o cobrem`, com a tag órfã, os testes sem critério
 * e os que não se aplicam em grupos à parte, no fim. Um teste que cobre dois critérios aparece nos dois.
 */
object CriteriaView {

    const val ORPHANS = "Tags de critério desconhecido"
    const val WITHOUT = "Sem critério"
    const val NOT_APPLICABLE = "Critério não se aplica"

    /** [results] são as classes de teste (as de [TestResults.build] e as do Cucumber), de onde saem as dos testes sem critério. */
    fun build(report: CriteriaReport, results: List<TestNode>): List<TestNode> {
        val collator = Collator.getInstance(Locale.of("pt", "BR"))
        val subjects = report.coverages.groupBy { it.criterion.groupName }
            .toSortedMap(compareBy(collator) { it })
            .map { (name, coverages) -> subject("subject:${name.lowercase()}", name, coverages.map(::criterion)) }
        val special = listOfNotNull(
            report.orphans.takeIf { it.isNotEmpty() }?.let { orphans ->
                subject("subject:orphans", ORPHANS, orphans.map { orphan ->
                    node(TestKind.CRITERION, "crit:orphan:${orphan.id.lowercase()}", orphan.id, copies("orphan:${orphan.id.lowercase()}", orphan.tests), criterionId = orphan.id)
                })
            },
            classesOf(report.withoutCriterion, results, "nocrit")?.let { subject("subject:without", WITHOUT, it) },
            classesOf(report.notApplicable, results, "nonap")?.let { subject("subject:not-applicable", NOT_APPLICABLE, it) },
        )
        return subjects + special
    }

    private fun criterion(coverage: CriterionCoverage): TestNode = node(
        TestKind.CRITERION,
        "crit:${coverage.criterion.id.lowercase()}",
        coverage.criterion.title,
        copies(coverage.criterion.id.lowercase(), coverage.tests),
        criterionId = coverage.criterion.id,
        coverage = coverage,
    )

    /** Cada teste com a chave prefixada, para o mesmo teste poder aparecer em mais de um critério. */
    private fun copies(prefix: String, tests: List<TestNode>): List<TestNode> =
        tests.mapNotNull { it.pruned("crit:$prefix|") { true } }

    /** As classes que têm [tests], só com eles. */
    private fun classesOf(tests: List<TestNode>, results: List<TestNode>, prefix: String): List<TestNode>? {
        if (tests.isEmpty()) return null
        val keys = tests.map { it.key }.toSet()
        val classes = results.mapNotNull { node -> node.pruned("$prefix|") { it.key in keys } }
        return classes.takeIf { it.isNotEmpty() }
    }

    private fun subject(key: String, label: String, children: List<TestNode>) =
        node(TestKind.SUBJECT, key, label, children)

    private fun node(
        kind: TestKind,
        key: String,
        label: String,
        children: List<TestNode>,
        criterionId: String? = null,
        coverage: CriterionCoverage? = null,
    ) = TestNode(
        kind = kind,
        key = key,
        label = label,
        codeName = null,
        locationUrl = null,
        durationMillis = children.mapNotNull { it.durationMillis }.takeIf { it.isNotEmpty() }?.sum(),
        errorMessage = null,
        stacktrace = null,
        children = children,
        leafOutcome = TestOutcome.NOT_RUN,
        criterionId = criterionId,
        coverage = coverage,
    )
}
