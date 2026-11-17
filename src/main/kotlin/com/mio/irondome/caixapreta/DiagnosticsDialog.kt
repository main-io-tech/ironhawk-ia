package com.mio.irondome.caixapreta

import com.intellij.icons.AllIcons
import com.intellij.ide.util.PropertiesComponent
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.PlatformCoreDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.CollectionListModel
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTabbedPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.mio.irondome.base.IronDomeIcons
import com.mio.irondome.base.Pill
import com.mio.irondome.base.TabErrorPanel
import com.mio.irondome.base.Tone
import com.mio.irondome.base.moreActions
import com.mio.irondome.base.refreshAction
import com.mio.irondome.interceptor.AgentRunner
import com.mio.ironhawk.IronHawkSystem
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.Font
import java.awt.datatransfer.StringSelection
import java.awt.event.ActionEvent
import javax.swing.AbstractAction
import javax.swing.Action
import javax.swing.BoxLayout
import javax.swing.Icon
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListSelectionModel
import javax.swing.SwingUtilities

/** As abas do Diagnóstico do IronHawk IA. */
enum class DiagnosticsTab(val title: String) {
    COMPATIBILITY("Compatibilidade"),
    ERROR_LOG("Logs de erro"),
}

/**
 * IronHawk IA: Diagnóstico, no menu Help e na engrenagem do painel. Abre na aba Compatibilidade, salvo quando quem
 * chama marca o componente com [TabErrorPanel.DIAGNOSTICS_TAB_PROPERTY] (como a aba que não abriu, que abre nos logs).
 */
class DiagnosticsAction : DumbAwareAction() {

    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        DiagnosticsDialog.show(e.project ?: return, requestedTab(e.getData(PlatformCoreDataKeys.CONTEXT_COMPONENT)))
    }

    private fun requestedTab(component: java.awt.Component?): DiagnosticsTab {
        var current = component
        while (current != null) {
            val name = (current as? JComponent)?.getClientProperty(TabErrorPanel.DIAGNOSTICS_TAB_PROPERTY) as? String
            if (name != null) return DiagnosticsTab.entries.firstOrNull { it.name == name } ?: DiagnosticsTab.COMPATIBILITY
            current = current.parent
        }
        return DiagnosticsTab.COMPATIBILITY
    }
}

/**
 * Confere ao abrir o projeto e só informa: os problemas viram um aviso, até o usuário pedir para não avisar mais. Vale
 * para o plugin todo; com a Defesa desligada, ficam só os mecanismos comuns aos dois sistemas.
 */
class CompatibilityStartup : ProjectActivity {

    override suspend fun execute(project: Project) {
        val env = Compatibility.environment(project)
        val problems = Compatibility.problems(Compatibility.relevant(Compatibility.evaluate(env), IronHawkSystem.DEFENSE.enabled))
        if (problems.isEmpty()) return
        val signature = problems.joinToString("|") { "${it.mechanism.name}:${it.detail}" }
        if (PropertiesComponent.getInstance().getValue(DISMISSED_KEY) == signature) return
        val title = if (problems.size == 1) "IronHawk IA: ${problems.single().mechanism.title} com problema"
        else "IronHawk IA: ${problems.size} mecanismos com problema"
        NotificationGroupManager.getInstance().getNotificationGroup(GROUP)
            .createNotification(title, problems.joinToString("<br>") { "<b>${it.mechanism.title}:</b> ${escapeHtml(it.detail)}" }, NotificationType.INFORMATION)
            .setIcon(IronDomeIcons.caixaPreta)
            .addAction(NotificationAction.createSimpleExpiring("Abrir Diagnóstico") { DiagnosticsDialog.show(project, DiagnosticsTab.COMPATIBILITY) })
            .addAction(NotificationAction.createSimpleExpiring("Não Avisar de Novo") { PropertiesComponent.getInstance().setValue(DISMISSED_KEY, signature) })
            .notify(project)
    }

    private companion object {
        /** O id do grupo de avisos fica com o nome antigo, para não perder o que o usuário configurou nele. */
        const val GROUP = "Iron Dome IA"

        /** Guarda os problemas dispensados: um problema diferente volta a avisar. */
        const val DISMISSED_KEY = "IronDome.Compatibility.Dismissed"
    }
}

/**
 * Diagnóstico do IronHawk IA: a compatibilidade de cada mecanismo com a IDE e os erros e avisos do plugin no log, em
 * duas abas. Não é modal, para ficar aberto enquanto se reproduz um erro.
 */
internal class DiagnosticsDialog private constructor(
    private val project: Project,
    private val env: Environment,
    private val checks: List<CompatibilityCheck>,
    private var errors: List<PluginError>,
    initialTab: DiagnosticsTab,
) : DialogWrapper(project, true) {

    private val tabs = JBTabbedPane()

    private val model = CollectionListModel<ErrorGroup>()
    private val list = JBList(model).apply {
        selectionMode = ListSelectionModel.SINGLE_SELECTION
        cellRenderer = ErrorRenderer()
        emptyText.text = "Nenhum erro do IronHawk IA no log da IDE"
        emptyText.appendLine("Quando o plugin registrar um problema no idea.log, ele aparece aqui.", SimpleTextAttributes.GRAYED_ATTRIBUTES, null)
    }
    private val detail = JBTextArea().apply {
        isEditable = false
        font = Font(Font.MONOSPACED, Font.PLAIN, JBFont.label().size)
        lineWrap = false
    }
    private val mechanismFilter = ComboBox(arrayOf<Mechanism?>(null) + Mechanism.entries).apply {
        renderer = SimpleListCellRenderer.create("Todos os mecanismos") { it?.title ?: "Todos os mecanismos" }
        addActionListener { reload() }
    }
    private val showWarnings = JBCheckBox("Mostrar avisos (WARN)", true).apply { addActionListener { reload() } }
    private val summary = JBLabel().apply { foreground = UIUtil.getContextHelpForeground() }
    private val reportError = JButton("Reportar Este Erro", IronDomeIcons.caixaPreta).apply {
        toolTipText = "Abre o Reportar Bug com o erro escolhido"
        addActionListener { selected()?.let { close(OK_EXIT_CODE); ReportBugAction.open(project, it.latest) } }
    }
    private val analyze = JButton("Analisar com IA", IronDomeIcons.ia).apply {
        toolTipText = "Pede à IA a causa provável e a correção; precisa da IA testada no Arsenal"
        addActionListener { selected()?.let { close(OK_EXIT_CODE); BugAnalysis.analyzeError(project, it) } }
    }
    private val copyError = JButton("Copiar Erro", AllIcons.Actions.Copy).apply {
        addActionListener { selected()?.let { copy(listOf(it)) } }
    }

    init {
        title = "Diagnóstico do IronHawk IA"
        isModal = false
        setCancelButtonText("Fechar")
        list.addListSelectionListener { if (!it.valueIsAdjusting) showDetail() }
        init()
        reload()
        tabs.selectedIndex = initialTab.ordinal
    }

    override fun createActions(): Array<Action> = arrayOf(cancelAction)

    override fun createLeftSideActions(): Array<Action> = arrayOf(
        object : AbstractAction("Reportar Bug") {
            override fun actionPerformed(e: ActionEvent) {
                close(OK_EXIT_CODE)
                val action = ActionManager.getInstance().getAction(TabErrorPanel.REPORT_BUG_ACTION) ?: return
                ActionManager.getInstance().tryToExecute(action, null, null, ActionPlaces.UNKNOWN, true)
            }
        },
    )

    override fun createCenterPanel(): JComponent {
        tabs.addTab(DiagnosticsTab.COMPATIBILITY.title, compatibilityTab())
        tabs.addTab(DiagnosticsTab.ERROR_LOG.title, errorLogTab())
        return tabs.apply { preferredSize = JBUI.size(900, 600) }
    }

    override fun getPreferredFocusedComponent(): JComponent = if (tabs.selectedIndex == DiagnosticsTab.ERROR_LOG.ordinal) list else tabs

    private fun compatibilityTab(): JComponent {
        val rows = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = JBUI.Borders.empty(8)
            checks.forEach { add(row(it)) }
        }
        val top = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(8, 8, 0, 8)
            add(comment("Plugin ${env.pluginVersion} · ${env.ide} · ${env.system}"), BorderLayout.CENTER)
            add(JButton("Copiar Diagnóstico", AllIcons.Actions.Copy).apply {
                toolTipText = "Copia a compatibilidade e os erros recentes em Markdown, para colar numa conversa ou issue"
                addActionListener {
                    CopyPasteManager.getInstance().setContents(StringSelection(Compatibility.markdown(env, checks, errors.take(COPIED_ERRORS))))
                }
            }, BorderLayout.EAST)
        }
        return JPanel(BorderLayout()).apply {
            add(top, BorderLayout.NORTH)
            add(JBScrollPane(rows).apply { border = JBUI.Borders.empty() }, BorderLayout.CENTER)
        }
    }

    private fun errorLogTab(): JComponent {
        val toolbar = ActionManager.getInstance().createActionToolbar(
            "IronHawk.DiagnosticsLog",
            DefaultActionGroup(
                refreshAction { refreshErrors() },
                moreActions(
                    object : DumbAwareAction("Copiar Todos", "Copia todos os erros listados em Markdown", AllIcons.Actions.Copy) {
                        override fun actionPerformed(e: AnActionEvent) = copy(model.items)
                    },
                    object : DumbAwareAction("Abrir idea.log", "Abre o log da IDE no editor", AllIcons.Actions.MenuOpen) {
                        override fun actionPerformed(e: AnActionEvent) {
                            val log = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(PluginLog.file) ?: return
                            close(OK_EXIT_CODE)
                            FileEditorManager.getInstance(project).openFile(log, true)
                        }
                    },
                ),
            ),
            true,
        )
        val filters = JPanel(BorderLayout()).apply {
            add(JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(8), 0)).apply {
                add(JBLabel("Mecanismo:"))
                add(mechanismFilter)
                add(showWarnings)
                add(summary)
            }, BorderLayout.CENTER)
            add(toolbar.component.also { toolbar.targetComponent = list }, BorderLayout.EAST)
        }
        val splitter = OnePixelSplitter(true, 0.4f).apply {
            firstComponent = JBScrollPane(list)
            secondComponent = JBScrollPane(detail)
        }
        val buttons = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0)).apply {
            add(reportError)
            add(analyze)
            add(copyError)
        }
        return JPanel(BorderLayout(0, JBUI.scale(8))).apply {
            border = JBUI.Borders.empty(8)
            add(filters, BorderLayout.NORTH)
            add(splitter, BorderLayout.CENTER)
            add(JPanel(BorderLayout(0, JBUI.scale(4))).apply {
                add(buttons, BorderLayout.NORTH)
                add(comment("Lido de ${PluginLog.file}."), BorderLayout.SOUTH)
            }, BorderLayout.SOUTH)
        }
    }

    private fun row(check: CompatibilityCheck): JComponent {
        val header = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0)).apply {
            add(JBLabel(icon(check.status)))
            add(JBLabel(check.mechanism.title).apply { font = JBFont.label().asBold() })
            add(Pill(check.status.label, tone(check.status)))
        }
        val text = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = JBUI.Borders.emptyLeft(28)
            add(JBLabel("<html>${escapeHtml(check.detail)}</html>"))
            check.fix?.let { add(comment(it)) }
        }
        return JPanel(BorderLayout()).apply {
            border = JBUI.Borders.emptyBottom(8)
            alignmentX = 0f
            add(header, BorderLayout.NORTH)
            add(text, BorderLayout.CENTER)
        }
    }

    private fun selected(): ErrorGroup? = list.selectedValue

    private fun refreshErrors() {
        ApplicationManager.getApplication().executeOnPooledThread {
            val fresh = readErrors()
            SwingUtilities.invokeLater {
                if (isDisposed) return@invokeLater
                errors = fresh
                reload()
            }
        }
    }

    private fun reload() {
        val previous = list.selectedValue?.latest
        val mechanism = mechanismFilter.selectedItem as? Mechanism
        val shown = errors.filter { error ->
            (showWarnings.isSelected || error.level != "WARN") && (mechanism == null || error.mechanism == mechanism)
        }
        val groups = PluginLog.group(shown)
        model.replaceAll(groups)
        summary.text = when {
            groups.isEmpty() -> ""
            groups.size == shown.size -> "${shown.size} ${if (shown.size == 1) "ocorrência" else "ocorrências"}"
            else -> "${groups.size} erros diferentes, ${shown.size} ocorrências"
        }
        val index = groups.indexOfFirst { it.latest == previous }.takeIf { it >= 0 } ?: 0
        if (groups.isNotEmpty()) list.selectedIndex = index else showDetail()
    }

    private fun showDetail() {
        val group = list.selectedValue
        reportError.isEnabled = group != null
        copyError.isEnabled = group != null
        analyze.isEnabled = group != null && AgentRunner.getInstance(project).available()
        detail.text = group?.let(PluginLog::describe).orEmpty()
        detail.caretPosition = 0
    }

    private fun copy(groups: List<ErrorGroup>) {
        if (groups.isEmpty()) return
        CopyPasteManager.getInstance().setContents(StringSelection(PluginLog.markdown(groups)))
    }

    private fun icon(status: CheckStatus): Icon = when (status) {
        CheckStatus.OK -> AllIcons.General.InspectionsOK
        CheckStatus.INFO -> AllIcons.General.Information
        CheckStatus.WARNING -> AllIcons.General.Warning
        CheckStatus.ERROR -> AllIcons.General.Error
    }

    private fun tone(status: CheckStatus): Tone = when (status) {
        CheckStatus.OK -> Tone.GREEN
        CheckStatus.INFO -> Tone.BLUE
        CheckStatus.WARNING -> Tone.YELLOW
        CheckStatus.ERROR -> Tone.RED
    }

    private fun comment(text: String) = JBLabel("<html>${escapeHtml(text)}</html>").apply {
        componentStyle = UIUtil.ComponentStyle.SMALL
        foreground = UIUtil.getContextHelpForeground()
    }

    private class ErrorRenderer : ColoredListCellRenderer<ErrorGroup>() {
        override fun customizeCellRenderer(list: JList<out ErrorGroup>, value: ErrorGroup?, index: Int, selected: Boolean, hasFocus: Boolean) {
            value ?: return
            val error = value.latest
            icon = if (error.level == "WARN") AllIcons.General.Warning else AllIcons.General.Error
            append(error.time, SimpleTextAttributes.GRAYED_ATTRIBUTES)
            append("  ")
            append(error.mechanism?.title ?: "Plugin", SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
            if (value.count > 1) append("  ×${value.count}", SimpleTextAttributes.GRAYED_BOLD_ATTRIBUTES)
            append("  ")
            append(error.message.lines().first().take(MESSAGE_MAX).ifBlank { error.exception.orEmpty() })
        }
    }

    companion object {
        private const val LOG_ERRORS = 200
        private const val COPIED_ERRORS = 10
        private const val MESSAGE_MAX = 160

        private fun readErrors(): List<PluginError> =
            runCatching { PluginLog.recentErrors(LOG_ERRORS, includeRotated = true) }.getOrDefault(emptyList())

        /** Lê o ambiente e o log fora da EDT e abre o diálogo em [tab]. */
        fun show(project: Project, tab: DiagnosticsTab = DiagnosticsTab.COMPATIBILITY) {
            ApplicationManager.getApplication().executeOnPooledThread {
                val env = Compatibility.environment(project)
                val checks = Compatibility.evaluate(env)
                val errors = readErrors()
                ApplicationManager.getApplication().invokeLater({
                    DiagnosticsDialog(project, env, checks, errors, tab).show()
                }, project.disposed)
            }
        }
    }
}

private fun escapeHtml(text: String) = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
