package com.mio.irondome.escudo

import com.intellij.execution.testframework.sm.runner.SMTestProxy
import java.text.Collator
import java.util.Locale

enum class TestOutcome(val label: String) {
    RUNNING("Executando"),
    FAILED("Falhou"),
    INTERRUPTED("Interrompido"),
    IGNORED("Ignorado"),
    PASSED("Passou"),
    NOT_RUN("Não executado"),
}

enum class TestKind { TAG, CLASS, GROUP, TEST }

/**
 * Nó da árvore do Escudo. [label] é o `@DisplayName` escrito no código, se houver, ou o nome que o JUnit
 * reportou. [codeName] é o nome da classe ou do método, nulo quando igual a [label]. [TAG][TestKind.TAG]
 * reúne as classes com os testes de uma `@Tag`; [GROUP][TestKind.GROUP], as execuções de um teste
 * parametrizado ou uma suíte sem classe própria.
 */
class TestNode(
    val kind: TestKind,
    val key: String,
    val label: String,
    val codeName: String?,
    val locationUrl: String?,
    val durationMillis: Long?,
    val errorMessage: String?,
    val stacktrace: String?,
    val children: List<TestNode>,
    leafOutcome: TestOutcome,
    /** Nos testes, as `@Tag` que valem para eles; nos grupos de tag, a tag. */
    val tags: List<String> = emptyList(),
    /** Uma classe de teste de topo, fora de outra classe. */
    val topLevel: Boolean = false,
) {
    private val ownOutcome = leafOutcome

    /** Os testes deste nó: ele próprio, se for um teste, ou todos os de baixo. */
    val tests: List<TestNode> = if (kind == TestKind.TEST) listOf(this) else children.flatMap { it.tests }

    val outcome: TestOutcome = if (kind == TestKind.TEST) leafOutcome else outcomeOf(tests, leafOutcome)

    /** Classes de teste de topo deste nó: ele próprio ou as do grupo de tag. */
    val classes: List<TestNode>
        get() = when (kind) {
            TestKind.TAG -> children.flatMap { it.classes }
            TestKind.CLASS -> if (topLevel) listOf(this) else emptyList()
            else -> emptyList()
        }

    fun count(outcome: TestOutcome) = tests.count { it.outcome == outcome }

    /** O identificador do teste no código, como `loja.FreteTest#calcula`; o mesmo em todas as execuções de um parametrizado. */
    val testId: String? get() = locationUrl?.let(TestSource::idOf)

    /** Classe e método no código, como `loja.FreteTest#calcula`. */
    val codeReference: String? get() = locationUrl?.substringAfter("://")?.replace('/', '#')

    /**
     * Uma cópia só com os testes que passam em [keep], com [prefix] na chave para não colidir com o original
     * na árvore; nula se não sobrar teste.
     */
    fun pruned(prefix: String, keep: (TestNode) -> Boolean): TestNode? {
        if (kind == TestKind.TEST) return if (keep(this)) copy(prefix, children) else null
        val kept = children.mapNotNull { it.pruned(prefix, keep) }
        if (kept.isEmpty()) return null
        val whole = kept.size == children.size && kept.zip(children).all { (a, b) -> a.tests.size == b.tests.size }
        return copy(prefix, kept, if (whole) durationMillis else kept.mapNotNull { it.durationMillis }.takeIf { it.isNotEmpty() }?.sum())
    }

    private fun copy(prefix: String, children: List<TestNode>, duration: Long? = durationMillis) = TestNode(
        kind, prefix + key, label, codeName, locationUrl, duration, errorMessage, stacktrace, children,
        if (kind == TestKind.TEST) outcome else ownOutcome, tags, topLevel,
    )

    private companion object {
        fun outcomeOf(tests: List<TestNode>, own: TestOutcome): TestOutcome {
            val outcomes = tests.map { it.outcome }.toSet()
            return when {
                TestOutcome.FAILED in outcomes -> TestOutcome.FAILED
                TestOutcome.RUNNING in outcomes -> TestOutcome.RUNNING
                TestOutcome.INTERRUPTED in outcomes -> TestOutcome.INTERRUPTED
                TestOutcome.PASSED in outcomes -> TestOutcome.PASSED
                TestOutcome.IGNORED in outcomes -> TestOutcome.IGNORED
                else -> own
            }
        }
    }
}

object TestResults {

    const val NO_TAG = "Sem tag"

    /** Sem nenhuma `@Tag` no projeto, o grupo "Sem tag" é um nível a mais que não diz nada: ficam as classes. */
    fun byTagOrClasses(classes: List<TestNode>): List<TestNode> =
        if (classes.flatMap { it.tests }.all { it.tags.isEmpty() }) classes else byTag(classes)

    private const val SUITE_PROTOCOL = "java:suite://"
    private val byLabel = compareBy<TestNode, String>(Collator.getInstance(Locale.of("pt", "BR"))) { it.label }

    /**
     * As classes de teste sob [root], em ordem alfabética. Classes `@Nested` ficam dentro da classe que as contém;
     * suítes sem classe, como a do módulo ou a do motor do JUnit, são achatadas. [displayNames] dá o `@DisplayName`
     * do código para a URL de uma classe ou de um método; [tagsOf], as `@Tag`.
     */
    fun build(root: SMTestProxy, displayNames: (String) -> String?, tagsOf: (String) -> List<String> = { emptyList() }): List<TestNode> =
        topLevel(root, Lookup(displayNames, tagsOf)).sortedWith(byLabel)

    /**
     * As [classes] por `@Tag`: cada tag com as classes, só com os testes dela, em ordem alfabética, e por último
     * os testes sem tag. Um teste com duas tags aparece nas duas.
     */
    fun byTag(classes: List<TestNode>): List<TestNode> {
        val tests = classes.flatMap { it.tests }
        val names = tests.flatMap { it.tags }.groupBy { it.lowercase() }.values.map { spellingOf(it) }
            .sortedWith(compareBy(Collator.getInstance(Locale.of("pt", "BR"))) { it })
        val tagged = names.mapNotNull { tag ->
            tagGroup(tag, classes.mapNotNull { node -> node.pruned("tag:${tag.lowercase()}|") { test -> test.tags.any { it.equals(tag, ignoreCase = true) } } })
        }
        val untagged = tagGroup(null, classes.mapNotNull { it.pruned("tag:|") { test -> test.tags.isEmpty() } })
        return tagged + listOfNotNull(untagged)
    }

    private fun tagGroup(tag: String?, children: List<TestNode>): TestNode? {
        if (children.isEmpty()) return null
        return TestNode(
            kind = TestKind.TAG,
            key = "tag:${tag?.lowercase().orEmpty()}",
            label = tag ?: NO_TAG,
            codeName = null,
            locationUrl = null,
            durationMillis = children.mapNotNull { it.durationMillis }.takeIf { it.isNotEmpty() }?.sum(),
            errorMessage = null,
            stacktrace = null,
            children = children,
            leafOutcome = TestOutcome.NOT_RUN,
            tags = listOfNotNull(tag),
        )
    }

    /** O que vem do código para cada URL: o `@DisplayName` e as `@Tag`. */
    private class Lookup(val displayNames: (String) -> String?, val tagsOf: (String) -> List<String>)

    /** A tag não diferencia maiúsculas: vale a grafia mais usada e, no empate, a com maiúscula. */
    private fun spellingOf(names: List<String>): String =
        names.groupingBy { it }.eachCount().entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .first().key

    private fun topLevel(proxy: SMTestProxy, lookup: Lookup): List<TestNode> =
        proxy.children.flatMap { child ->
            if (child.isSuite && !isClass(child)) topLevel(child, lookup)
            else listOf(node(child, "", useCodeName = true, topLevel = true, lookup))
        }

    /** Execuções de um teste parametrizado repetem a URL do método: ficam com o nome que o JUnit deu. */
    private fun node(
        proxy: SMTestProxy,
        parentKey: String,
        useCodeName: Boolean,
        topLevel: Boolean,
        lookup: Lookup,
    ): TestNode {
        val url = proxy.locationUrl
        val kind = when {
            isClass(proxy) -> TestKind.CLASS
            proxy.isSuite -> TestKind.GROUP
            else -> TestKind.TEST
        }
        val reported = proxy.presentableName.ifBlank { proxy.name }
        val written = url?.takeIf { useCodeName }?.let(lookup.displayNames)
        val codeName = url?.let(::codeNameOf)
        val label = written ?: reported
        val key = "$parentKey/${url.orEmpty()}|$label"
        return TestNode(
            kind = kind,
            key = key,
            label = label,
            codeName = codeName?.takeIf { useCodeName && it.removeSuffix("()") != label.removeSuffix("()") },
            locationUrl = url,
            durationMillis = proxy.duration,
            errorMessage = proxy.errorMessage?.takeIf { it.isNotBlank() },
            stacktrace = proxy.stacktrace?.takeIf { it.isNotBlank() },
            children = proxy.children.map { node(it, key, useCodeName = kind == TestKind.CLASS, topLevel = false, lookup) },
            leafOutcome = outcomeOf(proxy),
            tags = url?.let(lookup.tagsOf).orEmpty(),
            topLevel = topLevel && kind == TestKind.CLASS,
        )
    }

    private fun isClass(proxy: SMTestProxy): Boolean {
        val url = proxy.locationUrl ?: return false
        return url.startsWith(SUITE_PROTOCOL) && '/' !in url.removePrefix(SUITE_PROTOCOL)
    }

    private fun codeNameOf(url: String): String {
        val path = url.substringAfter("://")
        val method = path.substringAfter('/', "")
        return if (method.isNotEmpty()) "${method.substringBefore('(')}()"
        else path.substringAfterLast('.').substringAfterLast('$')
    }

    private fun outcomeOf(proxy: SMTestProxy): TestOutcome = when {
        proxy.isInProgress -> TestOutcome.RUNNING
        proxy.isInterrupted -> TestOutcome.INTERRUPTED
        proxy.isIgnored -> TestOutcome.IGNORED
        proxy.isDefect -> TestOutcome.FAILED
        proxy.isPassed -> TestOutcome.PASSED
        else -> TestOutcome.NOT_RUN
    }

    fun formatDuration(millis: Long?): String? = when {
        millis == null -> null
        millis < 1000 -> "$millis ms"
        else -> String.format(Locale.of("pt", "BR"), "%.1f s", millis / 1000.0)
    }
}
