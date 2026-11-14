package com.mio.irondome.radar

import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.panels.VerticalLayout
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.mio.irondome.base.DefenseTabs
import com.mio.irondome.base.rootPath
import com.mio.irondome.interceptor.AgentRunner
import com.mio.irondome.interceptor.AgentTask
import java.awt.BorderLayout
import java.nio.file.Path
import javax.swing.JComponent
import javax.swing.JPanel

/** Dispara a verificação das regras de IA e leva o resultado ao usuário. */
object RulesCheckRunner {

    private const val GROUP = "Iron Dome IA"

    /** Na EDT. Acha regras e alterações em segundo plano e só então chama a IA, em modo só leitura. */
    fun start(project: Project) {
        val runner = AgentRunner.getInstance(project)
        if (runner.running != null) {
            notify(project, "A IA já está trabalhando", "Espere terminar ou cancele na barra de progresso da aba.", NotificationType.WARNING)
            return
        }
        val root = project.rootPath()?.let(Path::of) ?: return
        ApplicationManager.getApplication().executeOnPooledThread {
            val rules = RulesCheck.detectRuleFiles(root)
            val changed = if (rules.isEmpty()) emptyList() else RulesCheck.changedFiles(root)
            val instructions = if (rules.isEmpty() || changed.isEmpty()) null
            else runner.writeInstructions(RulesCheck.INSTRUCTIONS_FILE, RulesCheck.instructions(rules, changed))
            ApplicationManager.getApplication().invokeLater({
                when {
                    rules.isEmpty() -> notify(
                        project,
                        "Nenhum arquivo de regras de IA",
                        "O projeto não tem CLAUDE.md, AGENTS.md, .claude/rules, .cursor/rules nem .github/copilot-instructions.md. Nada foi enviado à IA.",
                        NotificationType.WARNING,
                    )
                    changed.isEmpty() -> notify(
                        project,
                        "Nada alterado para verificar",
                        "Não há alterações não commitadas nem commits da branch além da base.",
                        NotificationType.INFORMATION,
                    )
                    instructions == null -> notify(project, "Não deu para preparar a verificação", "Não consegui gravar as instruções para a IA.", NotificationType.ERROR)
                    else -> runner.run(
                        AgentTask(
                            "Verificar regras de IA",
                            "Verifique se as alterações do projeto cumprem as regras de IA seguindo as instruções em $instructions e responda só com o JSON pedido.",
                            write = false,
                            origin = DefenseTabs.RADAR,
                        ),
                    ) { result ->
                        if (result.ok) finish(project, result.answer, rules, changed.size)
                    }
                }
            }, project.disposed)
        }
    }

    private fun finish(project: Project, answer: String, rules: List<String>, changed: Int) {
        val result = RulesCheck.parse(answer, rules, changed)
        if (result == null) {
            notify(
                project,
                "A IA não devolveu o resultado esperado",
                "A resposta não trouxe o JSON de violações. Abra a execução na aba Arsenal para ver o que ela respondeu.",
                NotificationType.WARNING,
            )
            return
        }
        project.service<RulesCheckState>().last = result
        show(project)
    }

    /** Abre o resultado da última verificação, se houver. Na EDT. */
    fun show(project: Project) {
        val result = project.service<RulesCheckState>().last ?: return
        RulesCheckDialog(project, result).show()
    }

    fun notify(project: Project, title: String, content: String, type: NotificationType, action: Pair<String, () -> Unit>? = null) {
        val notification = NotificationGroupManager.getInstance().getNotificationGroup(GROUP).createNotification(title, content, type)
        action?.let { (text, run) -> notification.addAction(NotificationAction.createSimpleExpiring(text) { run() }) }
        notification.notify(project)
    }
}

/**
 * O resultado de uma verificação: as violações, cada uma com a regra, o arquivo, a linha e o trecho, para marcar no
 * Radar as que o usuário escolher, e no fim as regras que a IA não conseguiu avaliar.
 */
class RulesCheckDialog(private val project: Project, private val result: RulesCheckResult) : DialogWrapper(project) {

    private class Row(val violation: RuleViolation, val box: JBCheckBox, val inRadar: Boolean)

    private val service = project.service<ReviewCommentService>()
    private val rows = result.violations.map { violation ->
        val inRadar = service.hasOpenComment(violation.path, CommentOrigin.REGRAS, RulesCheck.mention(violation))
        val title = "<html><b>${escape(violation.path)}:${violation.line}</b> · ${escape(violation.rule)}" +
            (if (inRadar) " <i>(já no Radar)</i>" else "") + "</html>"
        Row(violation, JBCheckBox(title, false).apply { isEnabled = !inRadar; toolTipText = violation.why.ifEmpty { null } }, inRadar)
    }

    init {
        title = "Verificação das regras de IA"
        setOKButtonText("Enviar ao Radar")
        setCancelButtonText("Fechar")
        init()
        rows.forEach { it.box.addActionListener { updateOk() } }
        updateOk()
    }

    private fun updateOk() {
        val chosen = rows.count { it.box.isSelected && !it.inRadar }
        isOKActionEnabled = chosen > 0
        setOKButtonText(if (chosen > 0) "Enviar ao Radar ($chosen)" else "Enviar ao Radar")
    }

    override fun createCenterPanel(): JComponent {
        val list = JPanel(VerticalLayout(JBUI.scale(6))).apply {
            border = JBUI.Borders.empty(4)
            if (rows.isEmpty()) add(JBLabel("Nenhuma violação nas ${result.changedFiles} alterações verificadas.").apply { font = JBFont.label().asBold() })
            rows.forEach { row ->
                add(row.box)
                val violation = row.violation
                val detail = listOfNotNull(
                    violation.ruleFile.takeIf { it.isNotEmpty() }?.let { "Regra em $it" },
                    violation.why.takeIf { it.isNotEmpty() },
                ).joinToString(" · ")
                if (detail.isNotEmpty()) add(muted(detail))
                if (violation.snippet.isNotEmpty()) add(code(violation.snippet))
            }
            if (result.unverifiable.isNotEmpty()) {
                add(JBLabel("Não verificável no código (${result.unverifiable.size})").apply {
                    font = JBFont.label().asBold()
                    border = JBUI.Borders.emptyTop(10)
                })
                add(muted("A IA não consegue conferir estas regras lendo o código, então não as avaliou:"))
                result.unverifiable.forEach { add(muted("• ${it}")) }
            }
        }
        return JPanel(BorderLayout(0, JBUI.scale(6))).apply {
            add(
                JBLabel(
                    "${result.violations.size} violações em ${result.changedFiles} arquivos alterados, " +
                        "conferidos contra ${result.ruleFiles.size} arquivos de regras.",
                ),
                BorderLayout.NORTH,
            )
            add(JBScrollPane(list).apply { preferredSize = JBUI.size(720, 420) }, BorderLayout.CENTER)
        }
    }

    override fun doOKAction() {
        val chosen = rows.filter { it.box.isSelected && !it.inRadar }.map { it.violation }
        var created = 0
        val missing = mutableListOf<String>()
        for (violation in chosen) {
            val file = service.findFile(violation.path)
            val document = file?.takeIf { !it.isDirectory }?.let { FileDocumentManager.getInstance().getDocument(it) }
            if (file == null || document == null) {
                missing += violation.path
                continue
            }
            val line = (violation.line - 1).coerceIn(0, maxOf(document.lineCount - 1, 0))
            val comment = service.addComment(file, document, line..line, RulesCheck.commentText(violation), discuss = false, origin = CommentOrigin.REGRAS)
            if (comment != null) created++ else missing += violation.path
        }
        super.doOKAction()
        val failed = if (missing.isEmpty()) "" else " Não achei no projeto: ${missing.distinct().joinToString(", ")}."
        RulesCheckRunner.notify(
            project,
            if (created == 1) "1 alvo marcado no Radar" else "$created alvos marcados no Radar",
            "Marcados como de Regras de IA. Dispare a ordem de interceptação para a IA corrigir.$failed",
            if (created > 0) NotificationType.INFORMATION else NotificationType.WARNING,
        )
    }

    private fun muted(text: String) = JBLabel("<html>${escape(text)}</html>").apply {
        foreground = ReviewColors.mutedForeground
        border = JBUI.Borders.emptyLeft(22)
    }

    private fun code(text: String) = JBLabel("<html><pre style='margin:0'>${escape(text)}</pre></html>").apply {
        border = JBUI.Borders.emptyLeft(22)
    }

    private fun escape(text: String) = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}
