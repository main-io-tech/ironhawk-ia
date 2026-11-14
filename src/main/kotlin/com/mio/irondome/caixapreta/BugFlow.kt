package com.mio.irondome.caixapreta

import com.intellij.notification.Notification
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.mio.irondome.base.NoticeBox
import com.mio.irondome.base.Tone
import com.mio.irondome.interceptor.AgentRunner
import com.mio.irondome.interceptor.AgentTask
import java.awt.BorderLayout
import java.awt.Font
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.JComponent
import javax.swing.JPanel

/** O que o usuário escreveu e juntou no formulário, antes de virar relatório. */
internal class BugDraft(
    val title: String,
    val text: String,
    val mechanism: Mechanism,
    val errors: List<PluginError>,
    val context: List<Pair<String, String>>,
    val screenshot: BufferedImage?,
    val recording: Path?,
) {
    /** O relatório com o texto organizado pela IA ou, sem ela, o texto como foi escrito. */
    fun report(structured: StructuredBug?, includeErrors: Boolean) = BugReport(
        title = title,
        mechanism = mechanism,
        description = structured?.description ?: text,
        steps = structured?.steps.orEmpty(),
        expected = structured?.expected.orEmpty(),
        errors = if (includeErrors) errors else emptyList(),
        context = context,
        screenshot = screenshot != null,
        recording = recording != null,
        structured = structured != null,
    )

    fun discard() {
        recording?.let { runCatching { Files.deleteIfExists(it) } }
    }
}

/**
 * Do formulário à issue: a IA, se estiver pronta, organiza o texto (sem ler o código do projeto aberto); o usuário vê o
 * que será publicado, avisado de que é público, e só então o plugin cria a issue. Sem a IA, o texto vai como foi escrito.
 */
internal object BugFlow {

    private const val GROUP = "Iron Dome IA"

    /** Na EDT. */
    fun start(project: Project, draft: BugDraft) {
        val runner = AgentRunner.getInstance(project)
        if (!runner.available() || runner.running != null) return preview(project, draft, null)
        val notification = NotificationGroupManager.getInstance().getNotificationGroup(GROUP).createNotification(
            "Organizando o relatório com a IA",
            "Só o texto que você escreveu é enviado. Se ela falhar, o relatório segue como você escreveu.",
            NotificationType.INFORMATION,
        )
        notification.notify(project)
        ApplicationManager.getApplication().executeOnPooledThread {
            val file = runner.writeInstructions(BugStructuring.INSTRUCTIONS_FILE, BugStructuring.instructions(draft.title, draft.text))
            ApplicationManager.getApplication().invokeLater({
                if (file == null) return@invokeLater proceed(project, draft, null, notification)
                val started = runner.run(
                    AgentTask(
                        "Organizar o relatório de bug",
                        "Organize o relato de bug seguindo as instruções em $file e responda só com o JSON pedido.",
                        write = false,
                    ),
                    unattended = true,
                    onDone = { result -> proceed(project, draft, if (result.ok) BugStructuring.parse(result.answer) else null, notification) },
                )
                if (!started) proceed(project, draft, null, notification)
            }, project.disposed)
        }
    }

    private fun proceed(project: Project, draft: BugDraft, structured: StructuredBug?, notification: Notification) {
        notification.expire()
        preview(project, draft, structured)
    }

    private fun preview(project: Project, draft: BugDraft, structured: StructuredBug?) {
        val dialog = BugPreviewDialog(project, draft, structured)
        if (dialog.showAndGet()) {
            BugReports.submit(project, dialog.report(), draft.screenshot, draft.recording)
        } else {
            draft.discard()
        }
    }
}

/** O que será publicado, com o aviso de que a issue é pública e a escolha de levar ou não os erros do log. */
internal class BugPreviewDialog(
    private val project: Project,
    private val draft: BugDraft,
    private val structured: StructuredBug?,
) : DialogWrapper(project) {

    private val includeErrors = JBCheckBox(
        when (draft.errors.size) {
            0 -> "Nenhum erro do plugin no log da IDE"
            1 -> "Incluir o erro do plugin no log da IDE"
            else -> "Incluir os ${draft.errors.size} erros do plugin no log da IDE"
        },
        draft.errors.isNotEmpty(),
    ).apply {
        isEnabled = draft.errors.isNotEmpty()
        addActionListener { refresh() }
    }
    private val preview = JBTextArea(22, 90).apply {
        isEditable = false
        lineWrap = true
        wrapStyleWord = true
        font = Font(Font.MONOSPACED, Font.PLAIN, JBFont.label().size)
    }

    init {
        title = "O que será publicado"
        setOKButtonText("Publicar no GitHub")
        init()
        refresh()
    }

    fun report(): BugReport = draft.report(structured, includeErrors.isSelected)

    private fun refresh() {
        val report = report()
        preview.text = "${BugReports.issueTitle(report)}\n\n${BugReports.issueBody(report)}"
        preview.caretPosition = 0
    }

    override fun createCenterPanel(): JComponent = JPanel(BorderLayout(0, JBUI.scale(8))).apply {
        add(
            NoticeBox(com.intellij.icons.AllIcons.General.Warning, Tone.YELLOW).apply {
                text = "Esta issue será pública em ${BugGitHub.REPOSITORY.fullName}: qualquer pessoa verá o texto abaixo, os erros do log " +
                    "(que podem trazer caminhos e nomes de arquivos do seu projeto) e o ambiente. O print e a gravação não vão no texto: você os " +
                    "anexa depois, na página da issue, e eles também ficam públicos. Remova os erros do log se não quiser expô-los."
            },
            BorderLayout.NORTH,
        )
        add(JBScrollPane(preview), BorderLayout.CENTER)
        add(
            JPanel(BorderLayout(0, JBUI.scale(4))).apply {
                add(includeErrors, BorderLayout.NORTH)
                add(
                    JBLabel(
                        if (structured != null) "A IA organizou o seu texto em partes; o que você escreveu foi mantido."
                        else "Texto como você escreveu: a IA não organizou este relatório.",
                    ),
                    BorderLayout.SOUTH,
                )
            },
            BorderLayout.SOUTH,
        )
    }
}
