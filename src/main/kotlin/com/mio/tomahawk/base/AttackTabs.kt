package com.mio.tomahawk.base

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.diagnostic.ControlFlowException
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.ui.components.JBLabel
import com.intellij.ui.content.Content
import com.intellij.ui.content.ContentFactory
import com.intellij.util.messages.Topic
import com.intellij.util.ui.JBUI
import com.mio.irondome.base.NoticeBox
import com.mio.irondome.base.Tone
import com.mio.ironhawk.IronHawkSystem
import com.mio.ironhawk.IronHawkToolWindowFactory
import com.mio.ironhawk.IronHawkWindow
import com.mio.tomahawk.delivery.PullRequestGateway
import com.mio.tomahawk.speckit.SddStep
import com.mio.tomahawk.ui.EvaluationPanel
import com.mio.tomahawk.ui.LauncherPanel
import com.mio.tomahawk.ui.RadioPanel
import com.mio.tomahawk.ui.TelemetryPanel
import java.awt.BorderLayout
import java.nio.file.Path
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
        add(content(LAUNCHER, TomahawkIcons.lancador, "Lança as etapas do Spec Kit da feature e entrega o resultado num PR") {
            LauncherPanel(project)
        })
        add(content(EVALUATION, TomahawkIcons.avaliacao, "Revisa o que o Spec Kit gerou: spec, plano, tarefas, checklists e pendências") {
            EvaluationPanel(project)
        })
        add(content(TELEMETRY, TomahawkIcons.telemetria, "Acompanha a missão em voo, o log ao vivo e o histórico das execuções") {
            TelemetryPanel(project)
        })
        if (PullRequestGateway.available() != null) {
            add(content(RADIO, TomahawkIcons.radio, "Os comentários dos seus PRs no GitHub, com a resposta escrita pela IA") {
                RadioPanel(project)
            })
        }
    }

    /** Uma aba que não abre não pode levar as outras junto: o erro vai para o log e para o lugar dela. */
    private fun content(title: String, icon: Icon, description: String, create: () -> JComponent): Content {
        var disposable: Disposable? = null
        val component = try {
            create().also { disposable = it as? Disposable }
        } catch (error: Throwable) {
            if (error is ControlFlowException || error is VirtualMachineError) throw error
            LOG.warn("A aba $title do Ataque não pôde ser aberta", error)
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

    /**
     * Pede ao Lançador que abra uma etapa: a Avaliação usa para levar ao Lançador um artefato que falta. [feature] é a
     * pasta da feature em que ele falta, para o Lançador escolhê-la; nula, fica a feature que estiver escolhida.
     */
    fun interface GotoListener {
        fun goto(step: SddStep, feature: Path?)
    }

    @Topic.ProjectLevel
    val GOTO = Topic(GotoListener::class.java, Topic.BroadcastDirection.NONE)

    /** Abre o painel do IronHawk IA na aba [title] do Ataque (ou no Arsenal). */
    fun showTab(project: Project, title: String) = IronHawkWindow.show(project, IronHawkSystem.ATTACK, title)
}
