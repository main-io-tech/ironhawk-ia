package com.mio.irondome.patrulha

import com.intellij.icons.AllIcons
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.TitledSeparator
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBRadioButton
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.mio.irondome.base.NoticeBox
import com.mio.irondome.base.Pill
import com.mio.irondome.base.Tone
import com.mio.irondome.radar.CommentTarget
import com.mio.irondome.radar.ReviewComment
import java.awt.BorderLayout
import java.awt.FlowLayout
import javax.swing.BoxLayout
import javax.swing.ButtonGroup
import javax.swing.JComponent
import javax.swing.JPanel

/** O que fazer com cada comentário no envio: enviar agora, guardar para outro envio ou excluir. */
internal enum class SendChoice(val label: String) {
    SEND("Enviar"),
    LATER("Depois"),
    DELETE("Excluir"),
}

/**
 * Confirma o envio dos não enviados ao rascunho de revisão do PR no GitHub, mostrando onde cada um vai entrar. Cada
 * comentário pode ser enviado agora, ficar para depois ou ser excluído. O envio é sempre como rascunho: o veredito e
 * a publicação ficam só no Concluir Revisão.
 */
internal class SendReviewDialog(
    project: Project,
    private val pr: PullRequest,
    private val planned: List<PlannedRequest>,
    /** Aviso quando o código local não é o head do PR e as linhas podem não bater. */
    private val warning: String?,
) : DialogWrapper(project, true) {

    private val choices = planned.map { ChoiceButtons(it) }
    private val summary = JBLabel()

    val toSend: List<ReviewComment> get() = chosen(SendChoice.SEND)

    val toDelete: List<ReviewComment> get() = chosen(SendChoice.DELETE)

    init {
        title = "Enviar ao Rascunho do PR #${pr.number}"
        init()
        updateMode()
    }

    private fun chosen(choice: SendChoice) = choices.filter { it.choice == choice }.map { it.request.comment }

    private fun updateMode() {
        val sending = toSend.size
        val deleting = toDelete.size
        val later = choices.size - sending - deleting
        summary.text = listOfNotNull(
            "$sending para enviar",
            "$later para depois".takeIf { later > 0 },
            "$deleting para excluir".takeIf { deleting > 0 },
        ).joinToString(" · ")
        setOKButtonText(
            when {
                sending == 0 && deleting == 0 -> "Enviar Rascunho"
                sending == 0 -> "Excluir $deleting"
                deleting == 0 -> "Enviar Rascunho ($sending)"
                else -> "Enviar Rascunho ($sending) e Excluir $deleting"
            },
        )
        isOKActionEnabled = sending + deleting > 0
    }

    private fun chooseAll(choice: SendChoice) {
        choices.forEach { it.select(choice) }
        updateMode()
    }

    /** Enviar, Depois e Excluir de um comentário. */
    private inner class ChoiceButtons(val request: PlannedRequest) {
        private val buttons = SendChoice.entries.associateWith { choice ->
            JBRadioButton(choice.label, choice == SendChoice.SEND).apply { addActionListener { updateMode() } }
        }
        val panel = JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(4), 0)).apply {
            val group = ButtonGroup()
            buttons.values.forEach {
                group.add(it)
                add(it)
            }
        }

        val choice: SendChoice get() = buttons.entries.first { it.value.isSelected }.key

        /** O botão Enviar, que recebe o foco ao abrir. */
        val send: JComponent get() = buttons.getValue(SendChoice.SEND)

        fun select(choice: SendChoice) {
            buttons.getValue(choice).isSelected = true
        }
    }

    override fun getPreferredFocusedComponent(): JComponent? = choices.firstOrNull()?.send

    override fun createCenterPanel(): JComponent {
        val list = JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS) }
        choices.forEach { list.add(row(it)) }
        val bulk = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(10), 0)).apply {
            add(summary.apply { font = font.deriveFont(java.awt.Font.BOLD) })
            add(ActionLink("Enviar todos") { chooseAll(SendChoice.SEND) })
            add(ActionLink("Todos para depois") { chooseAll(SendChoice.LATER) })
            add(ActionLink("Excluir todos") { chooseAll(SendChoice.DELETE) })
        }
        val builder = FormBuilder.createFormBuilder()
            .addComponent(JBLabel("<html><b>#${pr.number}</b> ${escape(pr.title)}<br>${escape("@${pr.author} · ${pr.headRef} → ${pr.baseRef}")}</html>"))
        warning?.let { builder.addComponent(NoticeBox(AllIcons.General.Warning, Tone.YELLOW).apply { text = it }) }
        return builder
            .addComponent(TitledSeparator("Comentários (${planned.size})"))
            .addComponent(bulk)
            .addComponent(JBScrollPane(list).apply {
                border = JBUI.Borders.empty()
                preferredSize = JBUI.size(640, minOf(60 + planned.size * 52, 300))
            })
            .addComponent(comment("Vão para o rascunho no GitHub, que só você vê. Para publicar com o veredito, use Concluir Revisão."))
            .panel
    }

    private fun row(choice: ChoiceButtons): JComponent {
        val request = choice.request
        val comment = request.comment
        val where = when (comment.target) {
            CommentTarget.LINES -> if (comment.startLine == comment.endLine) "linha ${comment.startLine + 1}" else "linhas ${comment.startLine + 1}–${comment.endLine + 1}"
            CommentTarget.FILE -> "arquivo inteiro"
            CommentTarget.PACKAGE -> "pacote inteiro"
            CommentTarget.PROJECT -> "projeto inteiro"
        }
        val (label, tone, hint) = when (request.placement) {
            Placement.INLINE -> Triple("Na linha", Tone.GREEN, null)
            Placement.FILE -> Triple("No arquivo", Tone.BLUE, "A linha está fora do que o PR altera: vai no arquivo inteiro")
            Placement.BODY -> Triple("No texto geral", Tone.GRAY, "Fora dos arquivos do PR: vai no texto geral da revisão")
        }
        val header = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0)).apply {
            add(JBLabel(comment.id).apply { font = font.deriveFont(java.awt.Font.BOLD) })
            add(JBLabel("${comment.filePath}, $where"))
            add(Pill(label, tone).apply { hint?.let { toolTipText = it } })
            if (comment.suggestion.isNotBlank()) {
                add(
                    if (request.placement == Placement.INLINE) Pill("Sugestão aplicável", Tone.PURPLE).apply {
                        toolTipText = "No GitHub, o autor aplica o código sugerido com Commit suggestion"
                    }
                    else Pill("Código de exemplo", Tone.PURPLE).apply {
                        toolTipText = "Fora do diff o GitHub não aplica sugestões: o código vai como exemplo"
                    },
                )
            }
        }
        val info = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            add(header)
            add(comment(comment.text.trim().lines().first().take(140)).apply { border = JBUI.Borders.emptyLeft(8) })
        }
        return JPanel(BorderLayout()).apply {
            border = JBUI.Borders.emptyBottom(6)
            add(info, BorderLayout.CENTER)
            add(choice.panel, BorderLayout.EAST)
        }
    }

    private fun comment(text: String) = JBLabel("<html>${escape(text)}</html>").apply {
        componentStyle = UIUtil.ComponentStyle.SMALL
        foreground = UIUtil.getContextHelpForeground()
    }

    private fun escape(text: String) = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}
