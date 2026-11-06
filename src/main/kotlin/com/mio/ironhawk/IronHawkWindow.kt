package com.mio.ironhawk

import com.intellij.ide.ActivityTracker
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.ControlFlowException
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.DumbAwareToggleAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.IconLoader
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.openapi.wm.impl.content.ToolWindowContentUi
import com.intellij.icons.AllIcons
import com.intellij.ui.content.Content
import com.intellij.ui.content.ContentFactory
import com.mio.irondome.base.DefenseTabs
import com.mio.irondome.base.IronDomeIcons
import com.mio.irondome.base.IronDomeRoot
import com.mio.irondome.base.ProjectRootSelector
import com.mio.irondome.base.TabErrorPanel
import com.mio.irondome.base.onPanel
import com.mio.irondome.base.web.WebTab as SwingWebTab
import com.mio.irondome.interceptor.AgentProgressPanel
import com.mio.irondome.interceptor.InterceptorPanel
import com.mio.irondome.interceptor.InterceptorTab
import com.mio.tomahawk.base.AttackTabs
import com.mio.tomahawk.base.TomahawkIcons
import com.mio.tomahawk.speckit.SpecKitService
import java.awt.BorderLayout
import javax.swing.Icon
import javax.swing.JComponent
import javax.swing.JPanel

class IronHawkToolWindowFactory : ToolWindowFactory, DumbAware {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) = IronHawkWindow.getInstance(project).install(toolWindow)

    companion object {
        const val ID = "IronHawk IA"
    }
}

object IronHawkIcons {
    val toolWindow: Icon = IconLoader.getIcon("/icons/ironhawkToolWindow.svg", IronHawkIcons::class.java)
}

/**
 * O painel único do IronHawk IA: no título, Defesa ou Ataque escolhe as abas do sistema, e o Arsenal fica sempre no
 * fim, comum aos dois. As abas do sistema que sai ficam guardadas, sem recarregar, até ele voltar.
 */
@Service(Service.Level.PROJECT)
class IronHawkWindow(private val project: Project) {

    private var toolWindow: ToolWindow? = null
    private val tabs = mutableMapOf<IronHawkSystem, List<Content>>()
    private val lastSelected = mutableMapOf<IronHawkSystem, Content>()
    private var arsenal: Content? = null

    /** O sistema à vista; o último escolhido volta ao abrir o projeto. */
    var current: IronHawkSystem = IronHawkSystem.entries.firstOrNull { it.name == PropertiesComponent.getInstance(project).getValue(CURRENT_KEY) }
        ?: IronHawkSystem.DEFENSE
        private set

    fun install(toolWindow: ToolWindow) {
        this.toolWindow = toolWindow
        // Arrastar uma aba reordena, e levá-la para um lado divide o painel: dois mecanismos lado a lado.
        toolWindow.component.putClientProperty(ToolWindowContentUi.ALLOW_DND_FOR_TABS, true)
        val actions = ActionManager.getInstance()
        toolWindow.setTitleActions(
            listOfNotNull(
                SystemToggle(IronHawkSystem.DEFENSE, IronDomeIcons.toolWindow),
                SystemToggle(IronHawkSystem.ATTACK, TomahawkIcons.toolWindow),
                ProjectRootSelector(),
                AttackRefresh(),
                actions.getAction(TabErrorPanel.REPORT_BUG_ACTION),
                OpenSettings(),
            ),
        )
        val gear = listOf(TabErrorPanel.COMPATIBILITY_ACTION, TabErrorPanel.ERROR_LOG_ACTION).mapNotNull(actions::getAction)
        if (gear.isNotEmpty()) toolWindow.setAdditionalGearActions(DefaultActionGroup(gear))
        Disposer.register(toolWindow.disposable) {
            (tabs.values.flatten() + listOfNotNull(arsenal)).filter { !it.onPanel() }.forEach(Disposer::dispose)
            tabs.clear()
            arsenal = null
            this.toolWindow = null
        }
        // Outro projeto escolhido no Iron Dome: as abas da Defesa renascem com os comentários, as alterações e o Git dele.
        project.messageBus.connect(toolWindow.disposable).subscribe(IronDomeRoot.Listener.TOPIC, IronDomeRoot.Listener { rebuild(IronHawkSystem.DEFENSE) })
        ApplicationManager.getApplication().messageBus.connect(toolWindow.disposable).subscribe(IronHawkSystem.TOPIC, IronHawkSystem.Listener {
            ApplicationManager.getApplication().invokeLater({ systemsChanged() }, project.disposed)
        })
        select(available(current))
    }

    /** Mostra as abas de [system], com o Arsenal no fim. */
    fun select(system: IronHawkSystem) {
        val toolWindow = toolWindow ?: return
        if (!system.enabled) return
        val manager = toolWindow.contentManager
        val keepArsenal = arsenal != null && manager.selectedContent == arsenal
        manager.selectedContent?.takeIf { it in tabs[current].orEmpty() }?.let { lastSelected[current] = it }
        IronHawkSystem.entries.filter { it != system }.forEach { other -> tabs[other]?.filter { it.onPanel() }?.forEach { it.manager?.removeContent(it, false) } }
        val mine = tabs.getOrPut(system) { build(system, toolWindow) }
        val arsenal = arsenal(toolWindow)
        if (arsenal.onPanel()) arsenal.manager?.removeContent(arsenal, false)
        mine.filter { !it.onPanel() }.forEach(manager::addContent)
        manager.addContent(arsenal)
        current = system
        PropertiesComponent.getInstance(project).setValue(CURRENT_KEY, system.name)
        val selected = when {
            keepArsenal -> arsenal
            else -> lastSelected[system]?.takeIf { it.onPanel() } ?: mine.firstOrNull() ?: arsenal
        }
        selected.manager?.setSelectedContent(selected)
        ActivityTracker.getInstance().inc()
    }

    /** A aba [title] à vista no painel, do sistema atual ou o Arsenal. */
    fun find(title: String): Content? =
        (tabs[current].orEmpty() + listOfNotNull(arsenal)).firstOrNull { it.displayName == title && it.onPanel() }

    private fun available(system: IronHawkSystem): IronHawkSystem =
        if (system.enabled) system else IronHawkSystem.enabledSystems.firstOrNull() ?: system

    private fun build(system: IronHawkSystem, toolWindow: ToolWindow): List<Content> = when (system) {
        IronHawkSystem.DEFENSE -> DefenseTabs.create(project, toolWindow)
        IronHawkSystem.ATTACK -> AttackTabs.create(project)
    }

    /** Joga fora as abas de [system] e, se ele está à vista, cria de novo. */
    private fun rebuild(system: IronHawkSystem) {
        val toolWindow = toolWindow ?: return
        val selected = toolWindow.contentManager.selectedContent?.displayName
        discard(system, toolWindow)
        if (current == system) {
            select(system)
            selected?.let(::find)?.let { it.manager?.setSelectedContent(it) }
        }
    }

    private fun discard(system: IronHawkSystem, toolWindow: ToolWindow) {
        val old = tabs.remove(system).orEmpty()
        lastSelected.remove(system)
        DefenseTabs.untrack(toolWindow, old)
        old.forEach { content -> if (content.onPanel()) content.manager?.removeContent(content, true) else Disposer.dispose(content) }
    }

    /** Um sistema foi ligado ou desligado em Settings > Tools > IronHawk IA. */
    private fun systemsChanged() {
        val toolWindow = toolWindow ?: return
        IronHawkSystem.entries.filter { !it.enabled }.forEach { discard(it, toolWindow) }
        select(available(current))
    }

    private fun arsenal(toolWindow: ToolWindow): Content = arsenal ?: createArsenal(toolWindow).also {
        arsenal = it
        DefenseTabs.track(toolWindow, it)
    }

    private fun createArsenal(toolWindow: ToolWindow): Content {
        val web = if (SwingWebTab.supported) {
            runCatching { ArsenalWeb(project) }
                .onFailure { if (it is ControlFlowException) throw it else LOG.warn("O Arsenal web não abriu; vale o painel sem navegador", it) }
                .getOrNull()
        } else {
            null
        }
        val component: JComponent = if (web != null) {
            JPanel(BorderLayout()).apply {
                add(AgentProgressPanel(project, InterceptorTab.TITLE, web), BorderLayout.NORTH)
                add(web.component, BorderLayout.CENTER)
            }
        } else {
            InterceptorPanel(project)
        }
        return ContentFactory.getInstance().createContent(component, InterceptorTab.TITLE, false).apply {
            icon = IronDomeIcons.arsenal
            description = "A IA dos dois sistemas: qual CLI usar, o teste da integração, o que é de cada sistema e as execuções recentes"
            putUserData(ToolWindow.SHOW_CONTENT_ICON, true)
            setDisposer(web ?: component as InterceptorPanel)
        }
    }

    /** Defesa ou Ataque no título; só aparecem com os dois sistemas ligados. */
    private inner class SystemToggle(private val system: IronHawkSystem, icon: Icon) :
        DumbAwareToggleAction(system.label, "Mostra as abas da ${system.title}", icon) {

        override fun getActionUpdateThread() = ActionUpdateThread.EDT

        override fun isSelected(e: AnActionEvent) = current == system

        override fun setSelected(e: AnActionEvent, state: Boolean) = select(system)

        override fun update(e: AnActionEvent) {
            super.update(e)
            e.presentation.isVisible = IronHawkSystem.enabledSystems.size > 1
            e.presentation.putClientProperty(ActionUtil.SHOW_TEXT_IN_TOOLBAR, true)
        }
    }

    /** Relê o Spec Kit do projeto e as missões; só no Ataque. */
    private inner class AttackRefresh : DumbAwareAction("Atualizar", "Relê o Spec Kit do projeto e as execuções", AllIcons.Actions.Refresh) {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT
        override fun update(e: AnActionEvent) {
            e.presentation.isVisible = current == IronHawkSystem.ATTACK
        }
        override fun actionPerformed(e: AnActionEvent) = SpecKitService.getInstance(project).refresh(0)
    }

    private class OpenSettings : DumbAwareAction("Configurações", "Abre Settings > Tools > IronHawk IA", AllIcons.General.Settings) {
        override fun getActionUpdateThread() = ActionUpdateThread.BGT
        override fun actionPerformed(e: AnActionEvent) {
            ShowSettingsUtil.getInstance().showSettingsDialog(e.project, IronHawkConfigurable::class.java)
        }
    }

    companion object {
        private const val CURRENT_KEY = "IronHawk.Window.System"
        private val LOG = logger<IronHawkWindow>()

        fun getInstance(project: Project): IronHawkWindow = project.service()

        /** Abre o painel na aba [title]: troca para [system] antes, salvo no Arsenal, que é dos dois. */
        fun show(project: Project, system: IronHawkSystem, title: String) {
            val toolWindow = ToolWindowManager.getInstance(project).getToolWindow(IronHawkToolWindowFactory.ID) ?: return
            toolWindow.activate {
                val window = getInstance(project)
                if (title != InterceptorTab.TITLE && system != window.current && system.enabled) window.select(system)
                window.find(title)?.let { it.manager?.setSelectedContent(it) }
            }
        }
    }
}
