package com.mio.irondome.base

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.ex.ComboBoxAction
import com.intellij.openapi.diagnostic.ControlFlowException
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.DumbAwareToggleAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.wm.ToolWindow
import com.intellij.ui.content.Content
import com.intellij.ui.content.ContentFactory
import com.mio.irondome.radar.CommentsPanel
import com.mio.irondome.sentinela.ChangesPanel
import com.mio.ironhawk.IronHawkSystem
import com.mio.ironhawk.IronHawkToolWindowFactory
import com.mio.ironhawk.IronHawkWindow
import javax.swing.Icon
import javax.swing.JComponent

/**
 * As abas da Defesa no painel do IronHawk IA: Radar, Sentinela e as dos mecanismos que dependem de plugins da IDE
 * (Raio de Impacto, Escudo, Patrulha). O [IronHawkWindow] decide quando elas aparecem.
 */
object DefenseTabs {

    const val ID = IronHawkToolWindowFactory.ID
    const val RADAR = "Radar"
    private const val CONTENTS_KEY = "IronDome.Contents"
    private val LOG = logger<DefenseTabs>()

    /** Cria as abas da Defesa, fora do painel. */
    fun create(project: Project, toolWindow: ToolWindow): List<Content> = buildList {
        add(content(toolWindow, RADAR, IronDomeIcons.radar, "Os alvos marcados para a IA: comentários da revisão aberta e o histórico") {
            CommentsPanel(project)
        })
        add(content(toolWindow, "Sentinela", IronDomeIcons.sentinela, "O perímetro vigiado: arquivos com alterações ainda não commitadas") {
            ChangesPanel(project)
        })
        for (tab in ReviewToolWindowTab.EP_NAME.extensionList) {
            add(content(toolWindow, tab.title, tab.icon, tab.description) { tab.createComponent(project, toolWindow) })
        }
    }

    /** Uma aba que não abre não pode levar as outras junto: o erro vai para o log e para o lugar dela. */
    fun content(toolWindow: ToolWindow, title: String, icon: Icon?, description: String?, create: () -> JComponent): Content {
        val component = try {
            create().also { TabFailures.clear(title) }
        } catch (error: Throwable) {
            if (error is ControlFlowException || error is VirtualMachineError) throw error
            LOG.warn("A aba $title da Defesa do IronHawk IA não pôde ser aberta", error)
            TabFailures.record(title, error)
            TabErrorPanel(title, error)
        }
        val content = ContentFactory.getInstance().createContent(component, title, false).apply {
            this.icon = icon
            this.description = description
            putUserData(ToolWindow.SHOW_CONTENT_ICON, true)
            (component as? Disposable)?.let(::setDisposer)
        }
        track(toolWindow, content)
        return content
    }

    /** Guarda [content] entre as abas que [findContent] procura, também quando arrastada para uma divisão. */
    fun track(toolWindow: ToolWindow, content: Content) {
        tracked(toolWindow) += content
    }

    /** Esquece [contents], descartadas ao recriar as abas. */
    fun untrack(toolWindow: ToolWindow, contents: Collection<Content>) {
        tracked(toolWindow).removeAll(contents.toSet())
    }

    @Suppress("UNCHECKED_CAST")
    private fun tracked(toolWindow: ToolWindow): MutableList<Content> =
        toolWindow.component.getClientProperty(CONTENTS_KEY) as? MutableList<Content>
            ?: mutableListOf<Content>().also { toolWindow.component.putClientProperty(CONTENTS_KEY, it) }

    /**
     * As abas da Defesa e o Arsenal à vista no painel, também as arrastadas para uma divisão: essas vão para outro
     * gerenciador de abas, que `toolWindow.contentManager` não enxerga.
     */
    fun contents(toolWindow: ToolWindow): List<Content> =
        (toolWindow.component.getClientProperty(CONTENTS_KEY) as? List<*>).orEmpty()
            .filterIsInstance<Content>()
            .filter { it.onPanel() }

    fun findContent(toolWindow: ToolWindow, title: String): Content? = contents(toolWindow).firstOrNull { it.displayName == title }

    fun select(content: Content) {
        content.manager?.setSelectedContent(content)
    }

    /** Abre o painel do IronHawk IA na aba [title] da Defesa (ou no Arsenal). */
    fun showTab(project: Project, title: String) = IronHawkWindow.show(project, IronHawkSystem.DEFENSE, title)
}

/** A aba está no painel ou numa divisão dele: tirada sem descartar, ela ainda lembra o gerenciador de onde saiu. */
fun Content.onPanel(): Boolean = manager?.let { it.getIndexOfContent(this) >= 0 } == true

/** No título do painel, com mais de um projeto na janela: qual deles a Defesa vigia. */
internal class ProjectRootSelector : ComboBoxAction(), DumbAware {

    override fun getActionUpdateThread() = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        val project = e.project
        val candidates = project?.let { IronDomeRoot.getInstance(it).candidates() }.orEmpty()
        e.presentation.isVisible = candidates.size > 1 && project?.let { IronHawkWindow.getInstance(it).current } == IronHawkSystem.DEFENSE
        if (project == null || !e.presentation.isVisible) return
        val current = project.rootPath()
        e.presentation.text = candidates.firstOrNull { it.path == current }?.name ?: current?.substringAfterLast('/') ?: "Projeto"
        e.presentation.icon = AllIcons.Nodes.Module
        e.presentation.description = "O projeto que a Defesa vigia: alvos, alterações, Git e a pasta ironhawk/iron-dome/ são dele"
    }

    override fun createPopupActionGroup(button: JComponent, context: DataContext): DefaultActionGroup {
        val project = CommonDataKeys.PROJECT.getData(context) ?: return DefaultActionGroup()
        val root = IronDomeRoot.getInstance(project)
        val current = project.rootPath()
        return DefaultActionGroup(
            root.candidates().map { candidate ->
                object : DumbAwareToggleAction(candidate.name, candidate.path, AllIcons.Nodes.Module) {
                    override fun getActionUpdateThread() = ActionUpdateThread.EDT
                    override fun isSelected(e: AnActionEvent) = candidate.path == current
                    override fun setSelected(e: AnActionEvent, state: Boolean) {
                        root.select(candidate.path)?.let { Messages.showWarningDialog(project, it, "Trocar o Projeto da Defesa") }
                    }
                }
            },
        )
    }
}

internal fun refreshAction(onRefresh: () -> Unit): AnAction =
    object : DumbAwareAction("Atualizar", null, AllIcons.Actions.Refresh) {
        override fun actionPerformed(e: AnActionEvent) = onRefresh()
    }
