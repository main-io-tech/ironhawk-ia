package com.mio.tomahawk.base

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.diagnostic.ControlFlowException
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.ui.components.JBLabel
import com.intellij.ui.content.Content
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.jcef.JBCefApp
import com.intellij.util.messages.Topic
import com.intellij.util.ui.JBUI
import com.mio.ironhawk.IronHawkSystem
import com.mio.ironhawk.IronHawkToolWindowFactory
import com.mio.ironhawk.IronHawkWindow
import com.mio.tomahawk.delivery.PullRequestGateway
import com.mio.tomahawk.settings.TomahawkConfigurable
import com.mio.tomahawk.speckit.SddStep
import com.mio.tomahawk.ui.EvaluationPanel
import com.mio.tomahawk.ui.LauncherPanel
import com.mio.tomahawk.ui.RadioFallback
import com.mio.tomahawk.ui.TelemetryPanel
import com.mio.tomahawk.ui.web.EvaluationWeb
import com.mio.tomahawk.ui.web.LauncherWeb
import com.mio.tomahawk.ui.web.RadioWeb
import com.mio.tomahawk.ui.web.TelemetryWeb
import com.mio.tomahawk.ui.web.WebTab
import java.awt.BorderLayout
import javax.swing.Icon
import javax.swing.JComponent
import javax.swing.JPanel

/** As abas do Ataque no painel do IronHawk IA: Lançador, Avaliação, Telemetria e Rádio. O [IronHawkWindow] decide quando elas aparecem. */
object AttackTabs {

    const val ID = IronHawkToolWindowFactory.ID
    const val LAUNCHER = "Lançador"
    const val EVALUATION = "Avaliação"
    const val TELEMETRY = "Telemetria"
    const val RADIO = "Rádio"
    const val ARSENAL = "Arsenal"
    private val LOG = logger<AttackTabs>()

    /** Cria as abas do Ataque, fora do painel. */
    fun create(project: Project): List<Content> = buildList {
        val web = JBCefApp.isSupported()
        add(content(LAUNCHER, TomahawkIcons.lancador, "Dispara as etapas do Spec Kit: a missão SDD da feature ativa, etapa por etapa") {
            if (web) LauncherWeb(project) else LauncherPanel(project)
        })
        add(content(EVALUATION, TomahawkIcons.avaliacao, "Avalia o que foi gerado: spec, plano, tarefas, checklists e pendências") {
            if (web) EvaluationWeb(project) else EvaluationPanel(project)
        })
        add(content(TELEMETRY, TomahawkIcons.telemetria, "Monitora as missões: a execução em voo, o log ao vivo e o histórico") {
            if (web) TelemetryWeb(project) else TelemetryPanel(project)
        })
        if (PullRequestGateway.available() != null) {
            add(content(RADIO, TomahawkIcons.radio, "Os comentários dos seus PRs no GitHub, com a resposta escrita pela IA") {
                if (web) RadioWeb(project) else RadioFallback(project)
            })
        }
    }

    /**
     * Uma aba que não abre não pode levar as outras junto: o erro vai para o log e para o lugar dela. [create] devolve
     * um componente Swing ou uma [WebTab], descartada com a aba.
     */
    private fun content(title: String, icon: Icon, description: String, create: () -> Any): Content {
        var disposable: Disposable? = null
        val component = try {
            when (val created = create()) {
                is WebTab -> created.component.also { disposable = created }
                is JComponent -> created.also { disposable = it as? Disposable }
                else -> error("Aba $title sem componente")
            }
        } catch (error: Throwable) {
            if (error is ControlFlowException || error is VirtualMachineError) throw error
            LOG.warn("A aba $title do Tomahawk IA não pôde ser aberta", error)
            JPanel(BorderLayout()).apply {
                border = JBUI.Borders.empty(16)
                add(NoticeBox(AllIcons.General.Error, Tone.RED).apply {
                    text = "A aba $title não abriu: ${error.message ?: error.javaClass.simpleName}. Veja o log da IDE."
                }, BorderLayout.NORTH)
                add(JBLabel(), BorderLayout.CENTER)
            }
        }
        return ContentFactory.getInstance().createContent(component, title, false).apply {
            this.icon = icon
            this.description = description
            putUserData(ToolWindow.SHOW_CONTENT_ICON, true)
            disposable?.let(::setDisposer)
        }
    }

    /** Pede ao Lançador que abra uma etapa: a Avaliação usa para "Lançar" um artefato que falta. */
    fun interface GotoListener {
        fun goto(step: SddStep)
    }

    @Topic.ProjectLevel
    val GOTO = Topic(GotoListener::class.java, Topic.BroadcastDirection.NONE)

    /** Abre o painel do IronHawk IA na aba [title] do Ataque (ou no Arsenal). */
    fun showTab(project: Project, title: String) = IronHawkWindow.show(project, IronHawkSystem.ATTACK, title)
}

internal fun refreshAction(onRefresh: () -> Unit): AnAction =
    object : DumbAwareAction("Atualizar", "Relê o Spec Kit do projeto e as execuções", AllIcons.Actions.Refresh) {
        override fun actionPerformed(e: AnActionEvent) = onRefresh()
    }

internal fun settingsAction(project: Project): AnAction =
    object : DumbAwareAction("Configurações", "Abre Settings > Tools > IronHawk IA > Tomahawk IA", AllIcons.General.Settings) {
        override fun actionPerformed(e: AnActionEvent) {
            ShowSettingsUtil.getInstance().showSettingsDialog(project, TomahawkConfigurable::class.java)
        }
    }
