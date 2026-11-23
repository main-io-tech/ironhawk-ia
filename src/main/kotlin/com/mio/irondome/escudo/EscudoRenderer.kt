package com.mio.irondome.escudo

import com.intellij.icons.AllIcons
import com.intellij.ui.SimpleTextAttributes
import com.mio.irondome.base.RichTreeCellRenderer
import com.mio.irondome.base.Segment
import com.mio.irondome.base.Tone
import com.mio.irondome.base.plural
import javax.swing.Icon
import javax.swing.tree.DefaultMutableTreeNode

/**
 * As linhas da árvore do Escudo: o ícone do resultado e o nome do teste como está no `@DisplayName`. Cor e etiqueta
 * ficam só para o que não passou; o que o teste verifica fica na ficha.
 */
internal class EscudoRenderer(private val lookup: () -> EscudoLookup) : RichTreeCellRenderer() {

    /** A prova troca de objeto quando muda: a linha vale enquanto for a mesma. */
    override fun cacheStamp(): Any = lookup()

    override fun describe(row: Row, value: Any?) {
        val node = value as? DefaultMutableTreeNode ?: return
        val item = node.userObject as? TestNode ?: return
        // Só o primeiro nível vai em negrito: é o que se percorre com os olhos para achar um grupo.
        val top = node.level == 1
        when (item.kind) {
            TestKind.TAG -> {
                val tag = item.tags.firstOrNull()
                row.icon(if (tag == null) AllIcons.General.Information else iconOf(item.outcome))
                row.text(tag?.let { "@$it" } ?: TestResults.NO_TAG, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
                counts(row, item)
            }
            TestKind.CLASS -> {
                row.icon(iconOf(item.outcome))
                row.text(item.label, if (top) SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES else SimpleTextAttributes.REGULAR_ATTRIBUTES)
                counts(row, item)
            }
            TestKind.GROUP -> {
                row.icon(iconOf(item.outcome))
                row.text(item.label, plain(item))
                counts(row, item, one = "exemplo", many = "exemplos")
            }
            TestKind.TEST -> {
                row.icon(iconOf(item.outcome))
                row.text(item.label, plain(item))
                item.durationMillis?.takeIf { it >= SLOW_MS }?.let { row.text(TestResults.formatDuration(it).orEmpty(), SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES) }
                failure(row, item)
            }
        }
        row.tooltip(item.errorMessage?.lineSequence()?.firstOrNull { it.isNotBlank() } ?: item.codeReference)
    }

    /** Quantos testes o nó tem e, em destaque, só o que não passou. */
    private fun counts(row: Row, item: TestNode, one: String = "teste", many: String = "testes") {
        val tests = item.tests
        if (tests.isEmpty()) return
        row.text(plural(tests.size, one, many), SimpleTextAttributes.GRAYED_ATTRIBUTES)
        item.count(TestOutcome.FAILED).takeIf { it > 0 }?.let { row.pill(plural(it, "falhou", "falharam"), Tone.RED, bold = true) }
        item.count(TestOutcome.IGNORED).takeIf { it > 0 }?.let { row.pill(plural(it, "ignorado", "ignorados"), Tone.GRAY) }
    }

    /** A primeira linha do erro, embaixo do teste que falhou. */
    private fun failure(row: Row, test: TestNode) {
        test.errorMessage?.lineSequence()?.firstOrNull { it.isNotBlank() }?.let { row.detail(it.trim().take(ERROR_PREVIEW), FAILED_SMALL) }
    }

    private fun plain(item: TestNode) = if (item.outcome == TestOutcome.FAILED) FAILED_PLAIN else SimpleTextAttributes.REGULAR_ATTRIBUTES

    companion object {
        private const val ERROR_PREVIEW = 140
        private const val SLOW_MS = 1000L
        private val FAILED_PLAIN = SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, Tone.RED.accent)
        private val FAILED_SMALL = SimpleTextAttributes(SimpleTextAttributes.STYLE_SMALLER, Tone.RED.accent)

        fun barOf(tests: List<TestNode>): List<Segment> {
            val counts = tests.groupingBy { it.outcome }.eachCount()
            fun count(outcome: TestOutcome) = counts[outcome] ?: 0
            return listOf(
                Segment(count(TestOutcome.PASSED), Tone.GREEN.accent),
                Segment(count(TestOutcome.FAILED), Tone.RED.accent),
                Segment(count(TestOutcome.IGNORED), Tone.YELLOW.accent),
                Segment(count(TestOutcome.INTERRUPTED), Tone.ORANGE.accent),
                Segment(count(TestOutcome.RUNNING) + count(TestOutcome.NOT_RUN), Tone.GRAY.background),
            )
        }

        fun iconOf(outcome: TestOutcome): Icon = when (outcome) {
            TestOutcome.RUNNING -> AllIcons.RunConfigurations.TestState.Run
            TestOutcome.FAILED -> AllIcons.RunConfigurations.TestFailed
            TestOutcome.INTERRUPTED -> AllIcons.RunConfigurations.TestTerminated
            TestOutcome.IGNORED -> AllIcons.RunConfigurations.TestIgnored
            TestOutcome.PASSED -> AllIcons.RunConfigurations.TestPassed
            TestOutcome.NOT_RUN -> AllIcons.RunConfigurations.TestNotRan
        }
    }
}
