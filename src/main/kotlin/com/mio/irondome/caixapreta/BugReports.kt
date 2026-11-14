package com.mio.irondome.caixapreta

import com.intellij.ide.BrowserUtil
import com.intellij.ide.actions.RevealFileAction
import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.notification.Notification
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.vcs.ProjectLevelVcsManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.jcef.JBCefApp
import com.mio.irondome.base.DefenseTabs
import com.mio.irondome.base.TabFailures
import com.mio.irondome.base.rootPath
import com.mio.irondome.interceptor.AgentRunner
import java.awt.KeyboardFocusManager
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.format.DateTimeFormatter
import javax.imageio.ImageIO
import javax.swing.SwingUtilities

/** O ambiente do bug, lido na EDT na hora de abrir o formulário. */
internal class BugContext(val activeTab: Mechanism?, val entries: List<Pair<String, String>>) {

    companion object {
        private const val PLUGIN_ID = "com.mio.ironhawk"

        fun collect(project: Project): BugContext {
            val toolWindow = ToolWindowManager.getInstance(project).getToolWindow(DefenseTabs.ID)
            // Com o painel dividido, cada lado tem uma aba selecionada: vale a do lado com o foco.
            val tab = toolWindow?.takeIf { it.isVisible }?.let { window ->
                val selected = DefenseTabs.contents(window).filter { it.isSelected }
                val focus = KeyboardFocusManager.getCurrentKeyboardFocusManager().focusOwner
                (selected.firstOrNull { focus != null && SwingUtilities.isDescendingFrom(focus, it.component) } ?: selected.firstOrNull())
                    ?.displayName
            }
            val editor = FileEditorManager.getInstance(project).selectedTextEditor
            val file = editor?.let { FileDocumentManager.getInstance().getFile(it.document) }
            val openFile = if (editor != null && file != null) {
                val path = project.rootPath()?.let { file.path.removePrefix("$it/") } ?: file.path
                "$path, linha ${editor.caretModel.logicalPosition.line + 1}"
            } else {
                "nenhum"
            }
            val app = ApplicationInfo.getInstance()
            return BugContext(
                Mechanism.ofTab(tab),
                listOf(
                    "Versão do plugin" to (PluginManagerCore.getPlugin(PluginId.getId(PLUGIN_ID))?.version ?: "desconhecida"),
                    "IDE" to "${app.fullApplicationName} (build ${app.build.asString()})",
                    "Sistema operacional" to "${SystemInfo.OS_NAME} ${SystemInfo.OS_VERSION} (${SystemInfo.OS_ARCH})",
                    "Java" to "${SystemInfo.JAVA_RUNTIME_VERSION} (${SystemInfo.JAVA_VENDOR})",
                    "Plugins opcionais" to listOf("com.intellij.java" to "Java", "JUnit" to "JUnit")
                        .joinToString { (id, name) -> "$name ${if (Compatibility.pluginEnabled(id)) "ativo" else "inativo"}" },
                    "Navegador embutido (JCEF)" to if (runCatching { JBCefApp.isSupported() }.getOrDefault(false)) "disponível" else "indisponível",
                    "Abas que não abriram" to TabFailures.all.entries.joinToString("; ") { (title, error) -> "$title ($error)" }.ifEmpty { "nenhuma" },
                    "Controle de versão" to ProjectLevelVcsManager.getInstance(project).allActiveVcss
                        .joinToString { it.displayName }.ifEmpty { "nenhum" },
                    "Aba ativa do Iron Dome IA" to (tab ?: "painel fechado"),
                    "Arquivo aberto" to openFile,
                ),
            )
        }
    }
}

/**
 * Publica o relatório como issue no GitHub do plugin ([BugGitHub]). O print e a gravação não vão pela API: ficam, com o
 * `bug.md`, numa pasta temporária que o plugin abre ao lado da issue, para o autor arrastá-los para ela.
 */
object BugReports {

    private val STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
    private const val TITLE_PREFIX = "[Bug]"

    /** O prefixo `[Bug]` é o que a GitHub Action do repositório usa para pôr a label `bug` nas issues novas. */
    fun issueTitle(report: BugReport) = "$TITLE_PREFIX ${report.mechanism.title}: ${report.title.trim()}"

    /** O texto da issue, como será publicado. */
    fun issueBody(report: BugReport) = BugReportMarkdown.render(report, inline = false)

    /** [recording] é um arquivo temporário, movido para a pasta do relatório. */
    fun submit(project: Project, report: BugReport, screenshot: BufferedImage?, recording: Path?) {
        object : Task.Backgroundable(project, "Reportando o bug", false) {
            private var dir: Path? = null
            private var issue: BugIssue? = null
            private var failure: BugReportException? = null

            override fun run(indicator: ProgressIndicator) {
                indicator.text = "Salvando os anexos"
                val dir = Files.createTempDirectory("iron-dome-bug-${report.createdAt.format(STAMP)}-")
                this.dir = dir
                screenshot?.let { ImageIO.write(it, "png", dir.resolve(BugReportMarkdown.SCREENSHOT).toFile()) }
                recording?.let { Files.move(it, dir.resolve(BugReportMarkdown.RECORDING), StandardCopyOption.REPLACE_EXISTING) }
                Files.writeString(dir.resolve(BugReportMarkdown.FILE_NAME), BugReportMarkdown.render(report))
                indicator.text = "Criando a issue no GitHub"
                create(project, report)
            }

            private fun create(project: Project, report: BugReport) {
                try {
                    issue = BugGitHub.createIssue(project, issueTitle(report), issueBody(report))
                } catch (error: BugReportException) {
                    failure = error
                }
            }

            override fun onSuccess() {
                val dir = dir ?: return
                notifyResult(project, report, dir, issue, failure)
            }

            override fun onThrowable(error: Throwable) {
                group().createNotification(
                    "Não foi possível preparar o relatório de bug",
                    error.message ?: error.javaClass.simpleName,
                    NotificationType.ERROR,
                ).notify(project)
            }
        }.queue()
    }

    private fun retry(project: Project, report: BugReport, dir: Path) {
        object : Task.Backgroundable(project, "Criando a issue no GitHub", false) {
            private var issue: BugIssue? = null
            private var failure: BugReportException? = null

            override fun run(indicator: ProgressIndicator) {
                try {
                    issue = BugGitHub.createIssue(project, issueTitle(report), issueBody(report))
                } catch (error: BugReportException) {
                    failure = error
                }
            }

            override fun onSuccess() = notifyResult(project, report, dir, issue, failure)
        }.queue()
    }

    private fun notifyResult(project: Project, report: BugReport, dir: Path, issue: BugIssue?, failure: BugReportException?) {
        val captures = report.screenshot || report.recording
        val files = listOfNotNull(BugReportMarkdown.SCREENSHOT.takeIf { report.screenshot }, BugReportMarkdown.RECORDING.takeIf { report.recording })
        val notification = when {
            issue != null -> {
                BrowserUtil.browse(issue.url)
                if (captures) RevealFileAction.openDirectory(dir.toFile())
                val attach = if (captures) "Arraste ${files.joinToString(" e ")} da pasta que abri para a issue, no navegador: o GitHub só aceita anexos pela página." else "Aberta no navegador."
                group().createNotification("Issue #${issue.number} criada no GitHub", "${report.title}\n$attach", NotificationType.INFORMATION)
                    .addAction(NotificationAction.createSimple("Abrir a Issue") { BrowserUtil.browse(issue.url) })
            }
            failure?.noPlugin == true -> {
                BrowserUtil.browse(BugGitHub.newIssueUrl(issueTitle(report), issueBody(report)))
                if (captures) RevealFileAction.openDirectory(dir.toFile())
                group().createNotification(
                    "Abri a página de nova issue",
                    "Os plugins Git e GitHub da IDE não estão ativos, então não deu para criar a issue por aqui. Confira o texto e clique em Submit." +
                        if (captures) " Arraste ${files.joinToString(" e ")} da pasta que abri." else "",
                    NotificationType.INFORMATION,
                )
            }
            else -> group().createNotification("O bug não foi enviado ao GitHub", failure?.message.orEmpty(), NotificationType.WARNING)
                .addAction(NotificationAction.createSimpleExpiring("Tentar de Novo") { retry(project, report, dir) })
                .apply {
                    if (failure?.needsAccount == true) {
                        addAction(NotificationAction.createSimple("Abrir as Configurações do GitHub") {
                            ShowSettingsUtil.getInstance().showSettingsDialog(project, BugGitHub.GITHUB_SETTINGS)
                        })
                    }
                }
        }
        notification.addOpenActions(project, dir).notify(project)
    }

    private fun Notification.addOpenActions(project: Project, dir: Path) = apply {
        addAction(NotificationAction.createSimple("Abrir ${BugReportMarkdown.FILE_NAME}") { openReport(project, dir) })
        addAction(NotificationAction.createSimple("Abrir Pasta") { RevealFileAction.openDirectory(dir.toFile()) })
        if (AgentRunner.getInstance(project).available()) {
            addAction(NotificationAction.createSimpleExpiring("Analisar com IA") {
                BugAnalysis.analyzeReport(project, dir.resolve(BugReportMarkdown.FILE_NAME))
            })
        }
    }

    private fun openReport(project: Project, dir: Path) {
        LocalFileSystem.getInstance().refreshAndFindFileByNioFile(dir.resolve(BugReportMarkdown.FILE_NAME))
            ?.let { FileEditorManager.getInstance(project).openFile(it, true) }
    }

    private fun group() = NotificationGroupManager.getInstance().getNotificationGroup("Iron Dome IA")
}
