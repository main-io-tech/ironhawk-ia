package com.mio.irondome.escudo

import com.intellij.openapi.util.text.StringUtil
import com.intellij.ui.ColorUtil
import com.intellij.util.ui.UIUtil
import com.mio.irondome.base.Tone
import com.mio.irondome.base.plural
import java.awt.Color

/** O que a tela do Escudo sabe além da árvore: a prova de cada teste, lida do código. */
internal class EscudoLookup(val evidence: (String) -> TestEvidence?) {

    /** O que pede uma olhada e abre sozinho na árvore: o que falhou ou não terminou. */
    fun attention(node: TestNode): Boolean = when (node.kind) {
        TestKind.TEST -> node.outcome == TestOutcome.FAILED || node.outcome == TestOutcome.INTERRUPTED
        else -> node.tests.any(::attention)
    }
}

/** As cores, os símbolos e os trechos de HTML que a ficha do Escudo usa. */
internal object EscudoHtml {

    fun toneOf(outcome: TestOutcome): Tone = when (outcome) {
        TestOutcome.PASSED -> Tone.GREEN
        TestOutcome.FAILED -> Tone.RED
        TestOutcome.IGNORED -> Tone.YELLOW
        TestOutcome.INTERRUPTED -> Tone.ORANGE
        TestOutcome.RUNNING -> Tone.BLUE
        TestOutcome.NOT_RUN -> Tone.GRAY
    }

    fun symbolOf(outcome: TestOutcome) = when (outcome) {
        TestOutcome.PASSED -> "✔"
        TestOutcome.FAILED -> "✖"
        TestOutcome.IGNORED -> "⊘"
        TestOutcome.INTERRUPTED -> "■"
        else -> "…"
    }

    fun escape(text: String): String = StringUtil.escapeXmlEntities(text)

    fun hex(color: Color) = "#${ColorUtil.toHex(color)}"

    fun color(color: Color, html: String) = "<span style='color:${hex(color)}'>$html</span>"

    fun gray(html: String) = color(UIUtil.getContextHelpForeground(), html)

    fun section(title: String, aside: String = "") =
        "<p style='margin-top: 14px; margin-bottom: 3px'><b>${escape(title)}</b>${if (aside.isEmpty()) "" else "&nbsp;&nbsp;$aside"}</p>"

    fun code(text: String) =
        "<pre style='background-color:${hex(Tone.GRAY.background)}; padding: 6px; margin: 4px 0'>${escape(text)}</pre>"

    fun html(body: String) = "<html><body>$body</body></html>"

    /** O resultado em uma palavra, na cor dele: é a única cor forte da ficha. */
    fun verdict(symbol: String, label: String, tone: Tone) = color(tone.foreground, "<b>$symbol ${escape(label)}</b>")

    /**
     * O que um teste verifica, uma verificação por linha: a frase em português quando há uma segura e, quando não há, o
     * código como está, em fonte de código sobre um fundo suave. Um passo (a chamada a um método que verifica, como o
     * do ator de um teste de aceitação) aparece em palavras, com quantas asserções tem dentro; elas ficam recolhidas
     * até [expanded] dizer que a linha de chave `[key]|índice` está aberta, o que o link da linha alterna. Os passos de
     * preparação e de ação que também têm asserção ficam numa linha só, no fim, que abre do mesmo jeito.
     */
    fun proof(lines: List<ProofLine>, key: String = "", passed: Boolean = true, expanded: (String) -> Boolean = { false }): String {
        if (lines.isEmpty()) return ""
        // O visto verde diz que a verificação passou: num teste que falhou, a linha só diz o que ele verifica.
        val tick = "<td width='16' valign='top'>${if (passed) color(Tone.GREEN.accent, "✓") else gray("•")}</td>"
        val blank = "<td width='16'></td>"
        fun inner(line: ProofLine) = line.inner.joinToString("") { inner ->
            val shown = if (inner.code) "<span style='font-family: monospace'>${escape(inner.text)}</span>" else gray(escape(inner.text))
            "<tr>$blank<td style='padding-left: 14px'${if (inner.code) " bgcolor='${hex(Tone.GRAY.background)}'" else ""}>$shown</td></tr>"
        }
        fun text(line: ProofLine) = if (line.code) "<span style='font-family: monospace'>${escape(line.text)}</span>" else escape(line.text)
        fun toggle(id: String, label: String) = "<a href='$STEP_LINK${escape(id)}'>$label ${if (expanded(id)) "▾" else "▸"}</a>"
        val main = lines.withIndex().filterNot { it.value.support }.joinToString("") { (index, line) ->
            val text = text(line)
            if (line.inner.isEmpty()) {
                if (line.code) "<tr>$blank<td bgcolor='${hex(Tone.GRAY.background)}'>$text</td></tr>" else "<tr>$tick<td>$text</td></tr>"
            } else {
                val id = "$key|$index"
                "<tr>$tick<td>$text&nbsp;&nbsp;${toggle(id, plural(line.inner.size, "asserção", "asserções"))}</td></tr>" + if (expanded(id)) inner(line) else ""
            }
        }
        val support = lines.filter { it.support }
        val rest = if (support.isEmpty()) "" else {
            val id = "$key|$SUPPORT_KEY"
            val count = plural(support.sumOf { it.inner.size }, "asserção", "asserções")
            "<tr>$blank<td>${toggle(id, "+ $count na preparação e na ação")}</td></tr>" +
                if (expanded(id)) support.joinToString("") { line -> "<tr>$blank<td>${text(line)}</td></tr>" + inner(line) } else ""
        }
        return "<table width='100%' cellpadding='2' cellspacing='0' style='margin: 1px 0'>$main$rest</table>"
    }

    private const val SUPPORT_KEY = "apoio"

    /** O começo do link que abre e fecha as asserções de um passo na ficha. */
    const val STEP_LINK = "passo:"

    /** O começo do link que abre um teste no editor; o resto é a URL dele no runner. */
    const val OPEN_LINK = "abrir:"

    fun openLink(url: String?, label: String = "Abrir o teste"): String? = url?.let { "<a href='$OPEN_LINK${escape(it)}'>$label</a>" }

    /** Um bloco com uma faixa à esquerda, que o separa dos vizinhos; a cor da faixa é a do que não passou. */
    fun card(tone: Tone, body: String) =
        "<table width='100%' cellpadding='0' cellspacing='0' style='margin-top: 8px'><tr>" +
            "<td width='3' bgcolor='${hex(if (tone === Tone.GRAY) Tone.GRAY.background else tone.accent)}'></td><td width='10'></td><td>$body</td></tr></table>"
}

/**
 * A ficha de um item do Escudo, em HTML: o resultado e o que os testes verificam de fato, lido do código, para decidir
 * sem abrir o código. O que só identifica (método, tags) fica no rodapé.
 */
internal class EscudoDetails(private val current: () -> EscudoLookup, private val canExplain: (TestNode) -> Boolean) {

    private val lookup: EscudoLookup get() = current()

    /** Os passos com as asserções abertas na ficha, pela chave da linha. */
    private val openSteps = HashSet<String>()

    /** Abre ou fecha as asserções do passo de [link]; devolve se o link era de um passo. */
    fun toggle(link: String): Boolean {
        if (!link.startsWith(EscudoHtml.STEP_LINK)) return false
        val key = link.removePrefix(EscudoHtml.STEP_LINK)
        if (!openSteps.remove(key)) openSteps += key
        return true
    }

    fun of(item: TestNode): String = if (item.kind == TestKind.TEST) test(item) else group(item)

    /** Os testes de um grupo, um por método: as execuções de um parametrizado contam como um exemplo só. */
    private class Example(val id: String?, val name: String, val runs: List<TestNode>) {
        val outcome: TestOutcome
            get() = listOf(TestOutcome.FAILED, TestOutcome.INTERRUPTED, TestOutcome.RUNNING, TestOutcome.PASSED, TestOutcome.IGNORED)
                .firstOrNull { wanted -> runs.any { it.outcome == wanted } } ?: TestOutcome.NOT_RUN

        val url: String? get() = runs.firstNotNullOfOrNull { it.locationUrl }
    }

    private fun examples(tests: List<TestNode>): List<Example> =
        tests.groupBy { it.testId ?: it.key }.map { (key, runs) ->
            val id = runs.first().testId
            Example(id, id?.let(lookup.evidence)?.name?.takeIf { it.isNotEmpty() } ?: runs.first().label.takeIf { runs.size == 1 } ?: key.substringAfterLast('#'), runs)
        }

    /** A prova que um teste dá. Com [framed], o bloco ganha a faixa lateral e o nome do teste. */
    private fun example(example: Example, framed: Boolean): String {
        val evidence = example.id?.let(lookup.evidence)
        val failedRun = example.runs.firstOrNull { it.outcome == TestOutcome.FAILED }
        val body = buildString {
            val open = EscudoHtml.openLink(example.url)
            if (framed) {
                val verdict = EscudoHtml.verdict(symbolOf(example.outcome), example.outcome.label, toneOf(example.outcome))
                    .takeIf { example.outcome != TestOutcome.PASSED }
                append("<p style='margin: 0 0 3px 0'>${listOfNotNull(escape(example.name), verdict).joinToString(gray(" · "))}</p>")
            } else {
                append(title(example.name))
            }
            append(runs(example))
            failedRun?.errorMessage?.lineSequence()?.firstOrNull { it.isNotBlank() }?.let {
                append("<p style='margin: 4px 0'>${color(Tone.RED.foreground, escape(it.trim().take(ERROR_PREVIEW)))}")
                if (canExplain(failedRun)) append("&nbsp;&nbsp;<a href='$EXPLAIN_LINK'>Explicar com IA</a>")
                append("</p>")
            }
            if (evidence != null && evidence.proof.isNotEmpty()) {
                val shown = evidence.proof.take(MAX_CHECKS)
                append(
                    if (framed) "<p style='margin: 6px 0 2px 0'>${listOfNotNull(gray("Verifica"), open).joinToString(gray(" · "))}</p>"
                    else section("O teste verifica", open.orEmpty()),
                )
                append(EscudoHtml.proof(shown, evidence.id, example.outcome == TestOutcome.PASSED, openSteps::contains))
                if (evidence.proof.size > shown.size) append("<p style='margin: 1px 0'>${gray("e mais ${evidence.proof.size - shown.size}")}</p>")
            } else if (open != null) {
                append("<p style='margin-top: ${if (framed) 4 else 10}px'>$open</p>")
            }
        }
        return if (framed) EscudoHtml.card(if (failedRun != null) Tone.RED else Tone.GRAY, body) else body
    }

    /** As execuções de um parametrizado: cada uma com o nome que o teste deu a ela e se passou. */
    private fun runs(example: Example): String {
        if (example.runs.size < 2) return ""
        val shown = example.runs.take(MAX_RUNS).joinToString("&nbsp;&nbsp; ") { run ->
            "${color(toneOf(run.outcome).accent, symbolOf(run.outcome))} ${escape(runName(run.label))}"
        }
        val more = if (example.runs.size > MAX_RUNS) "&nbsp;&nbsp; ${gray("e mais ${example.runs.size - MAX_RUNS}")}" else ""
        return "<p style='margin: 6px 0 2px 0'>${gray("${example.runs.size} exemplos:")}&nbsp; $shown$more</p>"
    }

    /** O nome da execução sem o `[1]` do JUnit; só com o número, fica "exemplo 1". */
    private fun runName(label: String): String {
        val match = RUN_INDEX.find(label) ?: return label
        return match.groupValues[2].trim().ifEmpty { "exemplo ${match.groupValues[1]}" }
    }

    private fun test(item: TestNode): String = buildString {
        val evidence = item.testId?.let(lookup.evidence)
        append(title(item.label))
        append("<p style='margin-top: 8px'>${EscudoHtml.verdict(symbolOf(item.outcome), item.outcome.label, toneOf(item.outcome))}")
        TestResults.formatDuration(item.durationMillis)?.let { append(gray(" · $it")) }
        append("</p>")
        item.errorMessage?.let {
            append(section("Falha", if (canExplain(item)) "<a href='$EXPLAIN_LINK'>Explicar com IA</a>" else ""))
            append("<p style='margin: 0'>${color(Tone.RED.foreground, escape(it.trim()).replace("\n", "<br>"))}</p>")
        }
        if (evidence != null && evidence.proof.isNotEmpty()) {
            append(section("O teste verifica"))
            append(EscudoHtml.proof(evidence.proof, evidence.id, item.outcome == TestOutcome.PASSED, openSteps::contains))
        }
        item.stacktrace?.let { trace ->
            val lines = trace.lines()
            val shown = lines.take(STACKTRACE_LINES).joinToString("\n") + if (lines.size > STACKTRACE_LINES) "\n…" else ""
            append(section("Pilha")).append(EscudoHtml.code(shown))
        }
        append(
            footer(
                item.codeReference?.substringAfterLast('.')?.substringBefore('('),
                item.tags.takeIf { it.isNotEmpty() }?.joinToString(" ") { "@$it" },
                link = EscudoHtml.openLink(item.locationUrl),
            ),
        )
    }

    /** Uma tag, uma classe ou um teste parametrizado: quantos passaram e o que cada teste verifica. */
    private fun group(item: TestNode): String = buildString {
        if (item.kind == TestKind.GROUP) {
            // Um parametrizado: os exemplos e o que o método verifica.
            examples(item.tests).forEach { append(example(it, framed = false)) }
            append(footer(item.codeReference?.substringAfterLast('.')?.substringBefore('(')))
            return@buildString
        }
        append(title(if (item.kind == TestKind.TAG) item.tags.firstOrNull()?.let { "@$it" } ?: TestResults.NO_TAG else item.label))
        val failed = item.count(TestOutcome.FAILED)
        val ignored = item.count(TestOutcome.IGNORED)
        val counts = listOfNotNull(
            if (failed == 0 && item.outcome == TestOutcome.PASSED) EscudoHtml.verdict("✔", plural(item.tests.size, "teste passou", "testes passaram"), Tone.GREEN)
            else gray("${item.count(TestOutcome.PASSED)} de ${plural(item.tests.size, "teste passou", "testes passaram")}"),
            failed.takeIf { it > 0 }?.let { color(Tone.RED.foreground, "<b>${plural(it, "falhou", "falharam")}</b>") },
            ignored.takeIf { it > 0 }?.let { gray(plural(it, "ignorado", "ignorados")) },
        )
        append("<p style='margin: 0 0 4px 0'>${counts.joinToString(gray(" · "))}</p>")
        // A classe é lida aqui de uma vez: cada teste com o que verifica, sem abrir um por um na árvore.
        if (item.kind == TestKind.CLASS) {
            val examples = examples(item.tests)
            examples.take(MAX_EXAMPLES).forEach { append(example(it, framed = true)) }
            if (examples.size > MAX_EXAMPLES) append("<p>${gray("e mais ${examples.size - MAX_EXAMPLES}, na árvore")}</p>")
            append(footer(item.codeReference?.substringAfterLast('.'), link = EscudoHtml.openLink(item.locationUrl, "Abrir a classe")))
            return@buildString
        }
        append(failures(item))
    }

    /** Os testes do nó que falharam, com a primeira linha do erro. */
    private fun failures(item: TestNode): String = buildString {
        val failures = item.tests.filter { it.outcome == TestOutcome.FAILED }
        if (failures.isEmpty()) return@buildString
        append(section("Falhas", if (canExplain(item)) "<a href='$EXPLAIN_LINK'>Explicar com IA</a>" else ""))
        failures.take(MAX_FAILURES).forEach { failure ->
            val body = "<p style='margin: 0'>${escape(failure.label)}</p>" +
                failure.errorMessage?.lineSequence()?.firstOrNull { it.isNotBlank() }?.let { "<p style='margin: 2px 0 0 0'>${color(Tone.RED.foreground, escape(it.trim().take(ERROR_PREVIEW)))}</p>" }.orEmpty()
            append(EscudoHtml.card(Tone.RED, body))
        }
        if (failures.size > MAX_FAILURES) append("<p>${gray("e mais ${failures.size - MAX_FAILURES}")}</p>")
    }

    private fun title(text: String) = "<p style='margin: 0 0 6px 0'><b>${escape(text)}</b></p>"

    /** O que só identifica o item, numa linha discreta no fim: o método, as tags. */
    private fun footer(vararg parts: String?, link: String? = null): String {
        val shown = parts.filterNotNull().filter { it.isNotBlank() }.map { gray(escape(it)) } + listOfNotNull(link)
        return if (shown.isEmpty()) "" else "<p style='margin-top: 16px'>${shown.joinToString(gray(" · "))}</p>"
    }

    private fun escape(text: String) = EscudoHtml.escape(text)
    private fun gray(html: String) = EscudoHtml.gray(html)
    private fun color(color: Color, html: String) = EscudoHtml.color(color, html)
    private fun section(title: String, aside: String = "") = EscudoHtml.section(title, aside)
    private fun toneOf(outcome: TestOutcome) = EscudoHtml.toneOf(outcome)
    private fun symbolOf(outcome: TestOutcome) = EscudoHtml.symbolOf(outcome)

    companion object {
        const val EXPLAIN_LINK = "explicar"
        private const val STACKTRACE_LINES = 40
        private const val MAX_CHECKS = 12
        private const val MAX_RUNS = 12
        private const val MAX_EXAMPLES = 60
        private const val MAX_FAILURES = 10
        private const val ERROR_PREVIEW = 200
        private val RUN_INDEX = Regex("""^\s*\[(\d+)]\s*(.*)$""", RegexOption.DOT_MATCHES_ALL)
    }
}
