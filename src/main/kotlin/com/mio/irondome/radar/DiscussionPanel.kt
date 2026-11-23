package com.mio.irondome.radar

import com.intellij.ide.ui.laf.darcula.ui.DarculaButtonUI
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBRadioButton
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.ui.components.panels.VerticalLayout
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.mio.irondome.base.FitWidthPanel
import com.mio.irondome.base.Pill
import com.mio.irondome.base.Segment
import com.mio.irondome.base.SegmentBar
import com.mio.irondome.base.Tone
import com.mio.irondome.base.WrapText
import com.mio.irondome.base.plural
import com.mio.irondome.comando.ReviewMarkdown
import java.awt.BorderLayout
import java.awt.FlowLayout
import javax.swing.BorderFactory
import javax.swing.ButtonGroup
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

/**
 * A aba Discussão do Radar: a entrevista do "Discutir antes de alterar". Mostra uma decisão por vez, com a resposta
 * curta da IA ao comentário, as opções com o efeito de cada uma e a recomendada já marcada. O usuário responde com uma
 * opção ou com um texto, que a IA responde antes de perguntar de novo. No fim, a tela lista o que ficou decidido e só
 * então oferece aplicar.
 */
class DiscussionPanel(private val project: Project, parent: Disposable) : JPanel(BorderLayout()) {

    private val service = project.service<DiscussionService>()
    private val body = JPanel(VerticalLayout(JBUI.scale(10))).apply { border = JBUI.Borders.empty(12, 16) }

    /** Avisado a cada mudança, para o Radar atualizar o título da aba e trazê-la para a frente. */
    var onChange: () -> Unit = {}

    init {
        add(JBScrollPane(FitWidthPanel(body)).apply { border = JBUI.Borders.empty() }, BorderLayout.CENTER)
        project.messageBus.connect(parent).subscribe(DiscussionService.Listener.TOPIC, DiscussionService.Listener {
            ApplicationManager.getApplication().invokeLater({
                rebuild()
                onChange()
            }, project.disposed)
        })
        rebuild()
    }

    /** O que a aba diz no título: em que ponto a discussão está. */
    fun title(): String {
        val session = service.session ?: return TITLE
        return when {
            session.busy -> "$TITLE · IA trabalhando"
            session.confirming -> "$TITLE · confirmar"
            else -> "$TITLE · ${session.decisions.size + 1} de ${total(session)}"
        }
    }

    private fun total(session: DiscussionService.Session): Int =
        session.decisions.size + session.queue.count { Discussion.applies(it, session.decisions) }

    private fun rebuild() {
        body.removeAll()
        val session = service.session
        when {
            session == null -> idle()
            session.busy -> working(session)
            session.current != null -> asking(session, session.current!!)
            else -> confirming(session)
        }
        body.revalidate()
        body.repaint()
    }

    private fun idle() {
        body.add(title("Nenhuma discussão em andamento"))
        body.add(muted("Marque um alvo como \"Discutir antes de alterar\" e dispare com IA. Ela responde ao comentário e traz as decisões, uma por vez. Nada é alterado antes de você confirmar."))
    }

    private fun working(session: DiscussionService.Session) {
        body.add(title(session.status.ifEmpty { "A IA está trabalhando…" }))
        if (session.draft.isNotEmpty()) body.add(quote("Você escreveu", session.draft))
        body.add(muted("O andamento aparece no alto da aba. Você pode continuar trabalhando: a resposta fica aqui."))
        body.add(actions(link("Cancelar Discussão", "Fecha a discussão; os comentários continuam pendentes no Radar") { service.cancel() }))
    }

    private fun asking(session: DiscussionService.Session, question: DiscussQuestion) {
        val total = total(session)
        val number = session.decisions.size + 1
        body.add(JPanel(BorderLayout(JBUI.scale(10), 0)).apply {
            isOpaque = false
            add(JBLabel("Decisão $number de $total").apply { font = JBFont.small().asBold(); foreground = ReviewColors.mutedForeground }, BorderLayout.WEST)
            add(JPanel(BorderLayout()).apply {
                isOpaque = false
                border = JBUI.Borders.emptyTop(6)
                add(SegmentBar(listOf(Segment(number - 1, Tone.GREEN.accent), Segment(1, Tone.BLUE.accent), Segment(total - number, Tone.GRAY.background)), barHeight = 4), BorderLayout.NORTH)
            }, BorderLayout.CENTER)
        })
        session.comments.firstOrNull { it.id == question.commentId }?.let { body.add(commentBlock(it, session.answers[it.id])) }
        if (session.reply.isNotBlank()) body.add(quote("Resposta da IA", session.reply, Tone.BLUE))
        if (session.status.isNotBlank()) body.add(WrapText(session.status, JBFont.label(), Tone.ORANGE.foreground))

        body.add(WrapText(question.text, JBFont.h4().asBold()).apply { border = JBUI.Borders.emptyTop(4) })
        val group = ButtonGroup()
        val radios = question.options.mapIndexed { index, option ->
            val recommended = index == question.recommended
            val radio = JBRadioButton(option.label, recommended).apply {
                isOpaque = false
                font = JBFont.label().asBold()
                group.add(this)
            }
            body.add(JPanel(VerticalLayout(JBUI.scale(1))).apply {
                isOpaque = false
                add(JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(8), 0)).apply {
                    isOpaque = false
                    border = JBUI.Borders.emptyLeft(-JBUI.scale(8))
                    add(radio)
                    if (recommended) add(Pill("recomendada", Tone.GREEN))
                    if (!option.changes) add(Pill("não altera", Tone.GRAY))
                })
                if (option.effect.isNotBlank()) {
                    add(JPanel(BorderLayout()).apply {
                        isOpaque = false
                        border = JBUI.Borders.emptyLeft(OPTION_INDENT)
                        add(WrapText(option.effect, JBFont.label(), ReviewColors.mutedForeground), BorderLayout.CENTER)
                    })
                }
            })
            radio
        }
        if (question.reason.isNotBlank() && question.recommended in question.options.indices) {
            body.add(WrapText("Por que a recomendada: ${question.reason}", JBFont.label().asItalic(), ReviewColors.mutedForeground))
        }

        val field = JBTextField(session.draft).apply {
            emptyText.text = "Ou escreva: uma dúvida, uma discordância ou outra saída"
        }
        val answer = JButton(ANSWER).apply { putClientProperty(DarculaButtonUI.DEFAULT_STYLE_KEY, true) }
        fun send() {
            val text = field.text.trim()
            if (text.isNotEmpty()) service.reply(text) else radios.indexOfFirst { it.isSelected }.takeIf { it >= 0 }?.let(service::choose)
        }
        fun refresh() {
            val writing = field.text.isNotBlank()
            answer.text = if (writing) ASK else ANSWER
            answer.toolTipText = if (writing) "A IA responde ao que você escreveu e pergunta de novo; nada é decidido por você" else "Vale a opção marcada e vem a próxima decisão"
            answer.isEnabled = writing || radios.any { it.isSelected }
        }
        field.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = refresh()
            override fun removeUpdate(e: DocumentEvent) = refresh()
            override fun changedUpdate(e: DocumentEvent) = refresh()
        })
        field.addActionListener { send() }
        radios.forEach { radio -> radio.addActionListener { refresh() } }
        answer.addActionListener { send() }
        refresh()
        body.add(field)
        body.add(actions(
            answer,
            JButton("Voltar").apply {
                toolTipText = "Volta à decisão anterior"
                isVisible = session.decisions.isNotEmpty()
                addActionListener { service.back() }
            },
            link("Encerrar sem Alterar", "A resposta bastou: os comentários saem como tratados e nenhum arquivo é alterado") { service.conclude() },
            link("Cancelar Discussão", "Fecha a discussão; os comentários continuam pendentes no Radar") { service.cancel() },
        ))
        if (session.decisions.isNotEmpty()) body.add(decided(session.decisions, "Decidido até aqui"))
    }

    private fun confirming(session: DiscussionService.Session) {
        val changes = session.changes
        body.add(title(if (session.decisions.isEmpty()) "A IA respondeu" else "O que ficou decidido"))
        if (session.reply.isNotBlank()) body.add(quote("Resposta da IA", session.reply, Tone.BLUE))
        session.comments.forEach { comment ->
            body.add(JPanel(VerticalLayout(JBUI.scale(4))).apply {
                isOpaque = false
                border = JBUI.Borders.compound(BorderFactory.createMatteBorder(1, 0, 0, 0, JBColor.border()), JBUI.Borders.emptyTop(8))
                add(commentBlock(comment, session.answers[comment.id]))
                session.decisionsOf(comment).forEach { add(decisionRow(it)) }
            })
        }
        val kept = session.comments.count { comment -> session.decisionsOf(comment).none { it.chosen.changes } }
        body.add(muted(when {
            changes.isEmpty() -> "Nada a alterar: ao encerrar, os comentários saem como tratados."
            else -> listOfNotNull(
                "${plural(session.comments.size - kept, "comentário vira alteração", "comentários viram alteração")} para a IA aplicar, com a decisão escrita",
                kept.takeIf { it > 0 }?.let { plural(it, "sai como tratado, sem alterar nada", "saem como tratados, sem alterar nada") },
            ).joinToString("; ") + "."
        }))
        val back = JButton("Voltar").apply {
            toolTipText = "Volta à última decisão"
            isVisible = session.decisions.isNotEmpty()
            addActionListener { service.back() }
        }
        val cancel = link("Cancelar Discussão", "Fecha a discussão; os comentários continuam pendentes no Radar") { service.cancel() }
        if (changes.isEmpty()) {
            body.add(actions(
                JButton("Encerrar").apply {
                    putClientProperty(DarculaButtonUI.DEFAULT_STYLE_KEY, true)
                    toolTipText = "Os comentários saem como tratados e nenhum arquivo é alterado"
                    addActionListener { service.confirm(fire = false) }
                },
                back,
                cancel,
            ))
        } else {
            body.add(actions(
                JButton("Aplicar com IA").apply {
                    putClientProperty(DarculaButtonUI.DEFAULT_STYLE_KEY, true)
                    toolTipText = "Os comentários viram alterações com a decisão escrita, e a IA as aplica agora"
                    addActionListener { service.confirm(fire = true) }
                },
                JButton("Guardar para Disparar Depois").apply {
                    toolTipText = "Os comentários viram alterações no Radar, com a decisão escrita; você dispara quando quiser"
                    addActionListener { service.confirm(fire = false) }
                },
                back,
                cancel,
            ))
        }
    }

    /** O comentário do usuário e a resposta curta da IA a ele. */
    private fun commentBlock(comment: ReviewComment, answer: DiscussAnswer?) = JPanel(VerticalLayout(JBUI.scale(4))).apply {
        isOpaque = false
        add(JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0)).apply {
            isOpaque = false
            border = JBUI.Borders.emptyLeft(-JBUI.scale(6))
            add(Pill(comment.id, ReviewColors.discussTone, bold = true))
            add(JBLabel(where(comment)).apply { foreground = ReviewColors.mutedForeground })
        })
        add(WrapText("“${CommentMarkdown.plain(comment.text.substringBefore(Discussion.DECISION_TITLE).trim(), COMMENT_MAX)}”", JBFont.label().asItalic(), ReviewColors.mutedForeground))
        if (answer != null && !answer.isEmpty) {
            add(JPanel(VerticalLayout(JBUI.scale(3))).apply {
                isOpaque = false
                border = JBUI.Borders.compound(
                    BorderFactory.createMatteBorder(0, JBUI.scale(3), 0, 0, Tone.BLUE.accent),
                    JBUI.Borders.empty(2, 10),
                )
                if (answer.verdict.isNotBlank()) add(WrapText(answer.verdict, JBFont.label().asBold()))
                if (answer.text.isNotBlank()) add(WrapText(answer.text))
            })
        }
    }

    private fun decisionRow(decision: DiscussDecision) = JPanel(VerticalLayout(JBUI.scale(1))).apply {
        isOpaque = false
        border = JBUI.Borders.emptyTop(4)
        add(WrapText(decision.question.text, JBFont.label(), ReviewColors.mutedForeground))
        add(JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0)).apply {
            isOpaque = false
            border = JBUI.Borders.emptyLeft(-JBUI.scale(6))
            add(JBLabel("→ ${decision.chosen.label}").apply { font = JBFont.label().asBold() })
            add(if (decision.chosen.changes) Pill("altera", Tone.ORANGE) else Pill("não altera", Tone.GRAY))
        })
        if (decision.chosen.effect.isNotBlank()) add(WrapText(decision.chosen.effect))
    }

    /** As decisões já tomadas, em uma linha cada, para o usuário não perder o fio. */
    private fun decided(decisions: List<DiscussDecision>, heading: String) = JPanel(VerticalLayout(JBUI.scale(2))).apply {
        isOpaque = false
        border = JBUI.Borders.compound(BorderFactory.createMatteBorder(1, 0, 0, 0, JBColor.border()), JBUI.Borders.emptyTop(8))
        add(JBLabel(heading).apply { font = JBFont.small().asBold(); foreground = ReviewColors.mutedForeground })
        decisions.forEach { add(WrapText("✓ ${it.question.text} → ${it.chosen.label}", JBFont.small(), ReviewColors.mutedForeground)) }
    }

    private fun quote(heading: String, text: String, tone: Tone = Tone.GRAY) = JPanel(VerticalLayout(JBUI.scale(2))).apply {
        isOpaque = false
        border = JBUI.Borders.compound(BorderFactory.createMatteBorder(0, JBUI.scale(3), 0, 0, tone.accent), JBUI.Borders.empty(2, 10))
        add(JBLabel(heading).apply { font = JBFont.small().asBold(); foreground = ReviewColors.mutedForeground })
        add(WrapText(text))
    }

    private fun where(comment: ReviewComment) =
        if (comment.target == CommentTarget.PROJECT) "projeto inteiro"
        else "${comment.filePath.trimEnd('/').substringAfterLast('/')}, ${ReviewMarkdown.describe(comment.target, comment.startLine, comment.endLine)}"

    private fun actions(vararg components: JComponent) = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(10), JBUI.scale(2))).apply {
        isOpaque = false
        border = JBUI.Borders.empty(4, -JBUI.scale(10), 0, 0)
        components.forEach(::add)
    }

    private fun link(text: String, tooltip: String, action: () -> Unit) = ActionLink(text) { action() }.apply { toolTipText = tooltip }

    private fun title(text: String) = WrapText(text, JBFont.h4().asBold(), UIUtil.getLabelForeground())

    private fun muted(text: String) = WrapText(text, JBFont.label(), ReviewColors.mutedForeground)

    private companion object {
        const val TITLE = "Discussão"
        const val ANSWER = "Responder"
        const val ASK = "Enviar para a IA"
        const val COMMENT_MAX = 300
        const val OPTION_INDENT = 26
    }
}
