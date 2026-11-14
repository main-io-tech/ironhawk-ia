package com.mio.irondome.radar

import com.intellij.icons.AllIcons
import com.intellij.ide.ui.laf.darcula.ui.DarculaButtonUI
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBRadioButton
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.ui.components.panels.VerticalLayout
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.mio.irondome.comando.ReviewMarkdown
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import javax.swing.BorderFactory
import javax.swing.ButtonGroup
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

/**
 * As perguntas da IA no "Discutir antes de alterar", no topo da aba Radar: um bloco por comentário, com as opções de cada
 * pergunta (a recomendada marcada) e um campo de texto livre. Só aparece enquanto há uma discussão em andamento.
 */
class DiscussionPanel(private val project: Project, parent: Disposable) : JPanel(BorderLayout()) {

    private val service = project.service<DiscussionService>()
    private val body = JPanel(VerticalLayout(JBUI.scale(8))).apply { border = JBUI.Borders.empty(8, 12) }
    private val scroll = JBScrollPane(body).apply { border = JBUI.Borders.empty() }

    init {
        isVisible = false
        border = BorderFactory.createMatteBorder(0, 0, 1, 0, JBColor.border())
        add(scroll, BorderLayout.CENTER)
        project.messageBus.connect(parent).subscribe(DiscussionService.Listener.TOPIC, DiscussionService.Listener {
            ApplicationManager.getApplication().invokeLater({ rebuild() }, project.disposed)
        })
        rebuild()
    }

    private fun rebuild() {
        body.removeAll()
        val session = service.session
        isVisible = session != null
        if (session == null) {
            revalidate()
            repaint()
            return
        }
        body.add(JBLabel("Discussão antes de alterar", AllIcons.General.Information, JBLabel.LEFT).apply { font = JBFont.label().asBold() })
        if (session.busy || session.questions.isEmpty()) {
            body.add(muted(session.status.ifEmpty { "A IA está trabalhando…" }))
            body.add(buttons(session, send = false))
        } else {
            if (session.status.isNotEmpty()) body.add(muted(session.status))
            session.comments.forEach { comment ->
                val questions = session.questions.filter { it.commentId == comment.id }
                if (questions.isEmpty()) return@forEach
                body.add(JBLabel("${comment.id} · ${where(comment)}").apply { font = JBFont.label().asBold(); border = JBUI.Borders.emptyTop(4) })
                body.add(muted(CommentMarkdown.plain(comment.text, SUMMARY_MAX)))
                questions.forEach { body.add(questionBlock(session, it)) }
            }
            body.add(muted("Pergunta sem resposta usa a recomendação da IA."))
            body.add(buttons(session, send = true))
        }
        val height = body.preferredSize.height + JBUI.scale(2)
        scroll.preferredSize = Dimension(JBUI.scale(100), minOf(height, JBUI.scale(MAX_HEIGHT)))
        revalidate()
        repaint()
    }

    private fun where(comment: ReviewComment) =
        if (comment.target == CommentTarget.PROJECT) "projeto inteiro"
        else "${comment.filePath.substringAfterLast('/')}, ${ReviewMarkdown.describe(comment.target, comment.startLine, comment.endLine)}"

    private fun questionBlock(session: DiscussionService.Session, question: DiscussQuestion): JPanel =
        JPanel(VerticalLayout(JBUI.scale(2))).apply {
            border = JBUI.Borders.emptyLeft(8)
            add(JBLabel("<html>${escape(question.text)}</html>").apply { font = JBFont.label() })
            val answer = session.answers[question.id]
            val group = ButtonGroup()
            question.options.forEachIndexed { index, option ->
                val recommended = index == question.recommended
                val label = "<html><b>${escape(option.label)}</b>${if (recommended) " · <i>recomendada</i>" else ""}" +
                    (if (option.detail.isNotEmpty()) "<br><small>${escape(option.detail)}</small>" else "") + "</html>"
                add(JBRadioButton(label, answer?.option == index).apply {
                    group.add(this)
                    addActionListener { service.setAnswer(question, index, null) }
                })
            }
            add(JBTextField(answer?.text.orEmpty()).apply {
                emptyText.text = "Ou escreva a sua resposta"
                document.addDocumentListener(object : DocumentListener {
                    override fun insertUpdate(e: DocumentEvent) = changed()
                    override fun removeUpdate(e: DocumentEvent) = changed()
                    override fun changedUpdate(e: DocumentEvent) = changed()
                    private fun changed() = service.setAnswer(question, null, text)
                })
            })
        }

    private fun buttons(session: DiscussionService.Session, send: Boolean) = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0)).apply {
        if (send) {
            add(JButton("Enviar respostas").apply {
                putClientProperty(DarculaButtonUI.DEFAULT_STYLE_KEY, true)
                isEnabled = !session.busy
                addActionListener { service.submit() }
            })
        }
        add(JButton("Cancelar discussão").apply { addActionListener { service.cancel() } })
    }

    private fun muted(text: String) = JBLabel("<html>${escape(text)}</html>").apply {
        componentStyle = UIUtil.ComponentStyle.SMALL
        foreground = ReviewColors.mutedForeground
    }

    private fun escape(text: String) = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private companion object {
        const val MAX_HEIGHT = 320
        const val SUMMARY_MAX = 160
    }
}
