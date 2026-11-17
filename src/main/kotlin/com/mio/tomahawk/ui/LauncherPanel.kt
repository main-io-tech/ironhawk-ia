package com.mio.tomahawk.ui

import com.intellij.icons.AllIcons
import com.intellij.ide.ui.laf.darcula.ui.DarculaButtonUI
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.mio.irondome.base.Collapsible
import com.intellij.ui.JBColor
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.TitledSeparator
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.panels.VerticalLayout
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.intellij.util.ui.WrapLayout
import com.mio.irondome.base.FitWidthPanel
import com.mio.irondome.base.FlowRow
import com.mio.irondome.base.MechanismList
import com.mio.irondome.base.NoticeBox
import com.mio.irondome.base.Pill
import com.mio.irondome.base.Segment
import com.mio.irondome.base.Stat
import com.mio.irondome.base.SummaryHeader
import com.mio.irondome.base.Tone
import com.mio.irondome.base.WrapText
import com.mio.irondome.base.wrapping
import com.mio.irondome.interceptor.AgentProgressPanel
import com.mio.irondome.interceptor.AgentRunner
import com.mio.irondome.interceptor.AgentTask
import com.mio.tomahawk.base.AttackTabs
import com.mio.tomahawk.base.TomahawkIcons
import com.mio.tomahawk.run.ExtraSkills
import com.mio.tomahawk.run.LaunchTerminal
import com.mio.tomahawk.run.Mission
import com.mio.tomahawk.run.MissionRunner
import com.mio.tomahawk.run.RunMode
import com.mio.tomahawk.run.Skill
import com.mio.tomahawk.run.SpecKitSetup
import com.mio.tomahawk.run.TomahawkSettings
import com.mio.tomahawk.speckit.FeatureReport
import com.mio.tomahawk.speckit.SddStep
import com.mio.tomahawk.speckit.Snapshot
import com.mio.tomahawk.speckit.SpecKitService
import com.mio.tomahawk.speckit.StepState
import com.mio.tomahawk.speckit.StepStatus
import java.awt.BorderLayout
import java.awt.Component
import java.awt.FlowLayout
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.DefaultComboBoxModel
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListCellRenderer
import javax.swing.ListSelectionModel
import javax.swing.SwingConstants

/** Uma opção do seletor de feature: uma pasta de `specs/` ou uma feature nova, a designar. */
private sealed interface FeatureChoice {
    data class Existing(val report: FeatureReport) : FeatureChoice
    data object New : FeatureChoice
}

/** Uma linha da lista de etapas: [mark] é o número dela na feature; [group], o título que abre um grupo. */
private class StepRow(val status: StepStatus, val mark: String, val group: String? = null)

/**
 * A aba Lançador: as nove etapas do SDD, a constituição do projeto, que vale para todas as features, e as oito da
 * feature escolhida, com o estado de cada uma e a próxima em destaque. O formulário da etapa tem seções: a etapa (o
 * que ela faz e por que está no estado atual, com os detalhes recolhidos), Executar (a entrada e onde lançar), as
 * skills extras marcadas no Arsenal e a Entrega (commit, push e PR), recolhida até o usuário abrir.
 *
 * Escolher outra feature no seletor só muda o que se vê: ela vira a ativa (`.specify/feature.json`) quando o
 * usuário lança uma etapa ou copia o comando.
 */
class LauncherPanel(private val project: Project) : SimpleToolWindowPanel(true, true), Disposable {

    private val service = SpecKitService.getInstance(project)
    private val runner = MissionRunner.getInstance(project)
    private val header = SummaryHeader(TomahawkIcons.lancador, Tone.ORANGE)

    private val setup = SpecKitSetupPanel(project, this, withInit = true)
    private val initPanel = JPanel(VerticalLayout(JBUI.scale(8), VerticalLayout.FILL)).apply {
        border = JBUI.Borders.compound(JBUI.Borders.customLineBottom(JBColor.border()), JBUI.Borders.empty(10, 14))
        add(WrapText("Este projeto ainda não tem o .specify/: inicialize o Spec Kit para instalar os comandos do SDD."))
        add(setup)
    }

    private val featureModel = DefaultComboBoxModel<FeatureChoice>()
    private val feature = ComboBox(featureModel).apply {
        renderer = SimpleListCellRenderer.create("") { choice ->
            when (choice) {
                is FeatureChoice.Existing -> choice.report.name + if (choice.report.dir == service.snapshot.active?.dir) "  (ativa)" else ""
                FeatureChoice.New -> "+ Nova feature (Designar Alvo)"
                null -> ""
            }
        }
        toolTipText = "A feature escolhida vira a ativa quando você lança uma etapa ou copia o comando"
        addActionListener { if (!loading) featureChanged() }
    }
    private val featureBar = JPanel(BorderLayout(JBUI.scale(8), 0)).apply {
        border = JBUI.Borders.empty(6, 10)
        add(JBLabel("Feature:"), BorderLayout.WEST)
        add(feature, BorderLayout.CENTER)
    }

    private val stepsModel = DefaultListModel<StepRow>()
    private val steps = MechanismList(stepsModel, TomahawkIcons.lancador, Tone.ORANGE, fitWidth = true).apply {
        selectionMode = ListSelectionModel.SINGLE_SELECTION
        cellRenderer = StepRenderer()
        emptyText.text = "Inicialize o Spec Kit para ver as etapas"
        addListSelectionListener { if (!it.valueIsAdjusting && !loading) stepChanged() }
    }

    // Etapa: o nome, as pílulas, o que ela faz (uma frase), por que está no estado atual e os detalhes recolhidos.
    private val stepTitle = WrapText(font = JBFont.h4())
    private val stepPills = wrapRow()
    private val purpose = WrapText()
    private val reason = NoticeBox(AllIcons.General.Information, Tone.BLUE)
    private val fullPurpose = WrapText(font = JBFont.small(), color = UIUtil.getContextHelpForeground())
    private val commandLink = ActionLink("") { selectedStatus()?.let { s -> service.snapshot.commands[s.step]?.let { openFile(project, it.file) } } }
    private val details = JPanel()
    private val detailsBody = JPanel(VerticalLayout(JBUI.scale(4), VerticalLayout.FILL)).apply {
        isOpaque = false
        border = JBUI.Borders.emptyLeft(4)
        add(fullPurpose)
        add(commandLink)
    }

    // Executar: a entrada, onde lançar e o aviso da missão em voo, com o caminho para a Telemetria.
    private val input = JBTextArea(5, 30).apply {
        lineWrap = true
        wrapStyleWord = true
        font = JBFont.label()
        addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                if (e.keyCode == KeyEvent.VK_ENTER && (e.isControlDown || e.isMetaDown) && launch.isEnabled) {
                    e.consume()
                    launch()
                }
            }
        })
    }
    private val mode = ComboBox(arrayOf(RunMode.BACKGROUND, RunMode.TERMINAL)).apply {
        renderer = SimpleListCellRenderer.create("") { if (it == RunMode.TERMINAL) "Terminal" else "Devcontainer" }
        addActionListener { updateHint() }
    }
    private val modeHint = WrapText(font = JBFont.small(), color = UIUtil.getContextHelpForeground())
    private val launch = JButton("Lançar", TomahawkIcons.lancador).apply {
        putClientProperty(DarculaButtonUI.DEFAULT_STYLE_KEY, true)
        toolTipText = "Ctrl+Enter"
        addActionListener { launch() }
    }
    private val copy = JButton("Copiar Comando", AllIcons.Actions.Copy).apply {
        toolTipText = "Copia o comando com a entrada, para colar no chat do agente"
        addActionListener { copyCommand() }
    }
    private val flying = wrapRow().apply {
        add(JBLabel("Há uma missão em voo.", AllIcons.General.Information, SwingConstants.LEFT))
        add(telemetryLink(project))
    }

    private val delivery = DeliveryPanel(project, this) { selectedFeature() ?: service.snapshot.active }
    private val agent = AgentRunner.getInstance(project)

    /** O progresso da IA nas skills extras; some enquanto a missão em voo ocupa o topo, para não mostrar dois ao mesmo tempo. */
    private val agentProgress = JPanel(BorderLayout()).apply {
        isOpaque = false
        add(AgentProgressPanel(project, AttackTabs.LAUNCHER, this@LauncherPanel), BorderLayout.CENTER)
    }

    /** Os botões das skills extras que o usuário marcou no Arsenal; some quando não há nenhuma. */
    private val skillsRow = JPanel(VerticalLayout(JBUI.scale(4), VerticalLayout.FILL)).apply { isOpaque = false }

    /** As skills extras achadas no disco, pelo nome; lidas fora da EDT. Nulo enquanto a primeira leitura não volta. */
    private var foundSkills: Map<String, Skill>? = null
    private var skillsGeneration = 0

    /** O texto escrito em cada etapa, para trocar de etapa sem perder o rascunho. */
    private val drafts = mutableMapOf<SddStep, String>()
    private val modes = mutableMapOf<SddStep, RunMode>()
    private var currentStep: SddStep? = null
    private var wanted: SddStep? = null
    private var lastActive: Path? = null
    private var loading = false

    init {
        Collapsible(details, "Detalhes", false).apply {
            setContentComponent(detailsBody)
            setOn(false)
        }
        details.isOpaque = false
        val form = JPanel(VerticalLayout(JBUI.scale(8), VerticalLayout.FILL)).apply {
            border = JBUI.Borders.empty(10, 14, 14, 14)
            add(stepTitle)
            add(stepPills)
            add(purpose)
            add(reason)
            add(details)
            add(TitledSeparator("Executar"))
            add(JBLabel("Entrada (\$ARGUMENTS)").apply { font = JBFont.small().asBold(); foreground = UIUtil.getContextHelpForeground() })
            add(ScrollPaneFactory.createScrollPane(input))
            add(wrapRow().apply {
                add(launch)
                add(JBLabel("em"))
                add(mode)
                add(copy)
            })
            add(modeHint)
            add(flying)
            add(skillsRow)
            add(JPanel(BorderLayout()).apply {
                isOpaque = false
                border = JBUI.Borders.emptyTop(8)
                add(delivery, BorderLayout.CENTER)
            })
        }
        val splitter = OnePixelSplitter(true, 0.45f).apply {
            firstComponent = JPanel(BorderLayout()).apply {
                add(featureBar, BorderLayout.NORTH)
                add(ScrollPaneFactory.createScrollPane(steps, true), BorderLayout.CENTER)
            }
            secondComponent = ScrollPaneFactory.createScrollPane(FitWidthPanel(form), true)
        }
        setContent(JPanel(BorderLayout()).apply {
            add(JPanel(VerticalLayout(0)).apply {
                add(header)
                add(MissionProgressPanel(project, this@LauncherPanel))
                add(agentProgress)
                add(initPanel)
            }, BorderLayout.NORTH)
            add(splitter, BorderLayout.CENTER)
        })
        toolbar = ActionManager.getInstance().createActionToolbar(
            "Tomahawk.LauncherToolbar",
            DefaultActionGroup(
                object : DumbAwareAction("Abrir Artefato", "Abre o que a etapa selecionada gerou", AllIcons.Actions.EditSource) {
                    override fun actionPerformed(e: AnActionEvent) {
                        artifactOf()?.let { openFile(project, it) }
                    }
                    override fun update(e: AnActionEvent) {
                        e.presentation.isEnabled = artifactOf() != null
                    }
                    override fun getActionUpdateThread() = ActionUpdateThread.EDT
                },
                object : DumbAwareAction("Abrir a Spec", "Abre a spec.md da feature escolhida", AllIcons.FileTypes.Text) {
                    override fun actionPerformed(e: AnActionEvent) {
                        selectedFeature()?.spec?.file?.let { openFile(project, it) }
                    }
                    override fun update(e: AnActionEvent) {
                        e.presentation.isEnabled = selectedFeature()?.spec != null
                    }
                    override fun getActionUpdateThread() = ActionUpdateThread.EDT
                },
                Separator.getInstance(),
                object : DumbAwareAction("Arsenal", "Abre o Arsenal: a IA, onde rodar e as proteções", AllIcons.General.Settings) {
                    override fun actionPerformed(e: AnActionEvent) = AttackTabs.showTab(project, AttackTabs.ARSENAL)
                },
            ),
            true,
        ).apply { targetComponent = this@LauncherPanel }.wrapping().component
        steps.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2) input.requestFocusInWindow()
            }
        })
        project.messageBus.connect(this).subscribe(SpecKitService.CHANGED, SpecKitService.Listener { reload(it) })
        project.messageBus.connect(this).subscribe(
            MissionRunner.RUNS,
            MissionRunner.Listener { ApplicationManager.getApplication().invokeLater({ updateButtons() }, project.disposed) },
        )
        project.messageBus.connect(this).subscribe(
            AgentRunner.RUNS,
            AgentRunner.Listener { ApplicationManager.getApplication().invokeLater({ updateButtons() }, project.disposed) },
        )
        ApplicationManager.getApplication().messageBus.connect(this).subscribe(
            ExtraSkills.Listener.TOPIC,
            ExtraSkills.Listener { ApplicationManager.getApplication().invokeLater({ discoverSkills() }, project.disposed) },
        )
        project.messageBus.connect(this).subscribe(AttackTabs.GOTO, AttackTabs.GotoListener { step, dir -> goto(step, dir) })
        ApplicationManager.getApplication().messageBus.connect(this).subscribe(
            TomahawkSettings.Listener.TOPIC,
            TomahawkSettings.Listener { ApplicationManager.getApplication().invokeLater({ reload(service.snapshot) }, project.disposed) },
        )
        ApplicationManager.getApplication().messageBus.connect(this).subscribe(
            SpecKitSetup.Listener.TOPIC,
            SpecKitSetup.Listener { ApplicationManager.getApplication().invokeLater({ updateHeader(service.snapshot) }, project.disposed) },
        )
        reload(service.snapshot)
    }

    override fun dispose() = Unit

    /**
     * A Avaliação pediu uma etapa da feature [dir]: o Lançador escolhe a feature e a etapa. O Designar Alvo numa feature
     * que já tem spec começa uma feature nova.
     */
    private fun goto(step: SddStep, dir: Path?) {
        wanted = step
        val target = dir?.let(::choiceFor)
        when {
            step == SddStep.SPECIFY && feature.selectedItem != FeatureChoice.New &&
                (target as? FeatureChoice.Existing ?: feature.selectedItem as? FeatureChoice.Existing)?.report?.spec != null ->
                featureModel.selectedItem = FeatureChoice.New
            target != null && target != feature.selectedItem -> featureModel.selectedItem = target
            else -> {
                fillSteps(service.snapshot)
                stepChanged()
            }
        }
    }

    private fun reload(snapshot: Snapshot) {
        loading = true
        try {
            initPanel.isVisible = !snapshot.initialized && snapshot.layout != null
            if (initPanel.isVisible) setup.update()
            featureBar.isVisible = snapshot.initialized
            val previous = feature.selectedItem as? FeatureChoice
            featureModel.removeAllElements()
            snapshot.features.reversed().forEach { featureModel.addElement(FeatureChoice.Existing(it)) }
            featureModel.addElement(FeatureChoice.New)
            // A escolha do usuário fica enquanto a feature existir; a seleção só segue a ativa quando a ativa muda
            // (um lançamento a gravou, ou o specify acabou de criar uma).
            val activeChanged = snapshot.active?.dir != lastActive
            lastActive = snapshot.active?.dir
            val kept = when (previous) {
                FeatureChoice.New -> FeatureChoice.New
                is FeatureChoice.Existing -> choiceFor(previous.report.dir)
                null -> null
            }
            featureModel.selectedItem = kept.takeIf { !activeChanged }
                ?: snapshot.active?.let { choiceFor(it.dir) }
                ?: FeatureChoice.New
            fillSteps(snapshot)
        } finally {
            loading = false
        }
        stepChanged()
        updateHeader(snapshot)
        delivery.update()
        discoverSkills()
    }

    private fun choiceFor(dir: Path): FeatureChoice? =
        (0 until featureModel.size).map { featureModel.getElementAt(it) }.firstOrNull { it is FeatureChoice.Existing && it.report.dir == dir }

    /** A constituição e as etapas da feature; a próxima da feature é marcada entre as dela. */
    private fun statuses(snapshot: Snapshot): Pair<StepStatus?, List<StepStatus>> {
        val all = snapshot.statuses(selectedFeature())
        val constitution = all.firstOrNull { it.step == SddStep.CONSTITUTION }
        val featureSteps = all.filter { it.step != SddStep.CONSTITUTION }.let { list ->
            val first = list.firstOrNull { it.state in ACTIVE }
            list.map { it.copy(next = it === first) }
        }
        return constitution to featureSteps
    }

    private fun fillSteps(snapshot: Snapshot) {
        val chosen = wanted ?: currentStep
        wanted = null
        val wasLoading = loading
        loading = true
        try {
            stepsModel.clear()
            if (!snapshot.initialized) return
            val (constitution, featureSteps) = statuses(snapshot)
            constitution?.let { stepsModel.addElement(StepRow(it, "", "Projeto · vale para todas as features")) }
            val name = selectedFeature()?.name ?: "nova feature"
            featureSteps.forEachIndexed { index, status ->
                stepsModel.addElement(StepRow(status, "${index + 1}", if (index == 0) "Feature · $name" else null))
            }
            val rows = (0 until stepsModel.size()).map { stepsModel[it] }
            val fallback = if (constitution?.state == StepState.READY) SddStep.CONSTITUTION else featureSteps.firstOrNull { it.next }?.step ?: SddStep.SPECIFY
            val target = rows.indexOfFirst { it.status.step == chosen }.takeIf { it >= 0 } ?: rows.indexOfFirst { it.status.step == fallback }.coerceAtLeast(0)
            steps.selectedIndex = target
        } finally {
            loading = wasLoading
        }
    }

    /** Trocar de feature no seletor só muda o que se vê; nada é gravado no disco até lançar ou copiar o comando. */
    private fun featureChanged() {
        if (feature.selectedItem == FeatureChoice.New) wanted = SddStep.SPECIFY
        fillSteps(service.snapshot)
        stepChanged()
        updateHeader(service.snapshot)
        delivery.update()
    }

    private fun stepChanged() {
        currentStep?.let { drafts[it] = input.text }
        val status = selectedStatus()
        val switched = status?.step != currentStep
        currentStep?.let { step -> (mode.selectedItem as? RunMode)?.let { modes[step] = it } }
        currentStep = status?.step
        val snapshot = service.snapshot
        if (status == null) {
            stepTitle.text = if (snapshot.initialized) "Escolha uma etapa" else "Spec Kit não inicializado"
            stepPills.removeAll()
            purpose.text = ""
            reason.isVisible = false
            details.isVisible = false
            input.text = ""
            updateButtons()
            return
        }
        val step = status.step
        stepTitle.text = step.codename
        stepPills.removeAll()
        stepPills.add(Pill(snapshot.invocation(step), Tone.ORANGE, bold = true))
        stepPills.add(Pill(status.state.label, status.state.tone))
        if (step.argsRequired) stepPills.add(Pill("Entrada obrigatória", Tone.YELLOW))
        if (step.readOnly) stepPills.add(Pill("Só leitura", Tone.GRAY))
        if (step.interactive) stepPills.add(Pill("Faz perguntas", Tone.PURPLE))
        purpose.text = firstSentence(step.purpose)
        reason.isVisible = true
        reason.text = status.reason
        val ref = snapshot.commands[step]
        fullPurpose.text = step.purpose.takeIf { it != purpose.text }.orEmpty()
        fullPurpose.isVisible = fullPurpose.text.isNotEmpty()
        commandLink.isVisible = ref != null
        commandLink.text = ref?.let { "Abrir o comando (${if (it.skill) it.file.parent?.fileName else it.file.fileName})" }.orEmpty()
        commandLink.toolTipText = ref?.let { snapshot.relative(it.file) }
        details.isVisible = fullPurpose.isVisible || commandLink.isVisible
        if (switched) {
            input.text = drafts[step].orEmpty()
            input.emptyText.text = step.argsHint
            mode.selectedItem = modes[step]
                ?: if (step.interactive && LaunchTerminal.available() != null) RunMode.TERMINAL
                else TomahawkSettings.runMode.takeIf { it != RunMode.ASK } ?: RunMode.BACKGROUND
        }
        updateButtons()
        stepPills.revalidate()
        stepPills.repaint()
    }

    private fun updateButtons() {
        val status = selectedStatus()
        val inFlight = runner.progress != null
        val blocked = status?.state == StepState.BLOCKED || status?.state == StepState.UNAVAILABLE
        val why = when {
            status == null -> "Escolha uma etapa"
            inFlight -> "Há uma missão em voo"
            !TomahawkSettings.ready -> "Teste a integração com a IA no Arsenal"
            blocked -> status.reason
            else -> null
        }
        launch.isEnabled = why == null
        launch.toolTipText = why ?: "Ctrl+Enter"
        copy.isEnabled = status != null
        reason.isVisible = status != null
        flying.isVisible = inFlight
        agentProgress.isVisible = !inFlight
        updateHint()
        updateSkills()
    }

    private fun updateHint() {
        modeHint.text = if (mode.selectedItem == RunMode.TERMINAL) "No terminal, você conversa com a IA e aprova os comandos."
        else "No devcontainer, a IA roda sem perguntar e a Telemetria acompanha cada passo."
    }

    private fun updateHeader(snapshot: Snapshot) {
        val aiWarning = when {
            TomahawkSettings.ready -> null
            TomahawkSettings.launch() == null -> "O ${TomahawkSettings.provider.title} CLI não foi encontrado: configure a IA no Arsenal."
            else -> "Teste a integração com a IA no Arsenal antes de lançar."
        }
        if (snapshot.layout == null) {
            if (project.basePath == null) header.update("Projeto sem pasta", Tone.GRAY.accent, "Abra um projeto com pasta no disco.")
            else header.update("Lendo o Spec Kit…", Tone.GRAY.accent, "Procurando .specify/ e specs/ no projeto.")
            return
        }
        if (!snapshot.initialized) {
            header.update(
                "Spec Kit não inicializado",
                Tone.GRAY.accent,
                "Inicialize o Spec Kit para lançar as etapas pelo ${TomahawkSettings.provider.title} CLI.",
                warning = aiWarning,
            )
            return
        }
        val chosen = selectedFeature()
        val (_, featureSteps) = statuses(snapshot)
        val next = featureSteps.firstOrNull { it.next }
        val tasks = chosen?.tasks
        val converged = featureSteps.any { it.step == SddStep.CONVERGE && it.state == StepState.DONE }
        val counts = featureSteps.groupingBy { it.state }.eachCount()
        val subtitle = listOfNotNull(
            when {
                next != null -> "Próxima: ${next.step.codename} (${snapshot.invocation(next.step)})"
                converged -> "Missão cumprida: a implementação convergiu"
                else -> "Nenhuma etapa pendente"
            },
            TomahawkSettings.provider.title,
        ).joinToString("  ·  ")
        header.update(
            chosen?.name ?: "Nova feature",
            (next?.state?.tone ?: Tone.GREEN).accent,
            subtitle,
            stats = listOf(
                Stat(counts[StepState.DONE] ?: 0, "de ${featureSteps.size} etapas", Tone.GREEN),
                Stat(tasks?.done ?: 0, "tarefas feitas", Tone.BLUE),
                Stat((tasks?.total ?: 0) - (tasks?.done ?: 0), "tarefas abertas", Tone.ORANGE),
            ),
            bar = featureSteps.map { Segment(1, it.state.tone.accent) },
            warning = aiWarning
                ?: if (!snapshot.constitutionReady) "A constituição ainda é o modelo: comece pelas Regras de Engajamento." else null,
        )
    }

    /** Relê as skills extras fora da EDT: a busca percorre pastas do projeto e da pasta pessoal. */
    private fun discoverSkills() {
        val generation = ++skillsGeneration
        if (ExtraSkills.selected.isEmpty()) {
            foundSkills = emptyMap()
            updateSkills()
            return
        }
        val root = service.root()
        val custom = ExtraSkills.customFolders
        ApplicationManager.getApplication().executeOnPooledThread {
            val home = runCatching { Path.of(System.getProperty("user.home")) }.getOrNull()
            val found = runCatching { ExtraSkills.discover(root, home, custom).associateBy { it.name } }.getOrDefault(emptyMap())
            ApplicationManager.getApplication().invokeLater({
                if (generation != skillsGeneration) return@invokeLater
                foundSkills = found
                updateSkills()
            }, project.disposed)
        }
    }

    /** Um botão por skill extra marcada no Arsenal; desligado se ela sumiu ou a IA não pode rodar agora. */
    private fun updateSkills() {
        skillsRow.removeAll()
        val chosen = ExtraSkills.selected.sorted()
        skillsRow.isVisible = chosen.isNotEmpty()
        if (chosen.isEmpty()) return
        val found = foundSkills
        skillsRow.add(TitledSeparator("Skills extras"))
        skillsRow.add(wrapRow().apply {
            chosen.forEach { name ->
                val skill = found?.get(name)
                val why = when {
                    found == null -> "Procurando a skill…"
                    skill == null -> "Skill não encontrada no projeto nem nesta máquina: ajuste no Arsenal"
                    !agent.available() -> "Teste a integração com a IA no Arsenal"
                    agent.running != null -> "A IA já está trabalhando"
                    runner.progress != null -> "Há uma missão em voo"
                    else -> null
                }
                add(JButton(name, AllIcons.Actions.Execute).apply {
                    isEnabled = why == null
                    toolTipText = why ?: skill?.description?.takeIf { it.isNotBlank() } ?: "Roda a skill $name"
                    addActionListener { skill?.let(::runSkill) }
                })
            }
        })
        skillsRow.revalidate()
        skillsRow.repaint()
    }

    /** Pede uma instrução opcional e roda a skill com o CLI, o host e as proteções do Arsenal, sobre a feature escolhida. */
    private fun runSkill(skill: Skill) {
        val text = Messages.showMultilineInputDialog(
            project,
            "Instrução opcional para a skill ${skill.name}. Pode deixar em branco.",
            "Skill ${skill.name}",
            "",
            AllIcons.Actions.Execute,
            null,
        ) ?: return
        val content = runCatching { Files.readString(skill.file) }.getOrNull()
        if (content == null) {
            Messages.showErrorDialog(project, "Não consegui ler ${skill.file}.", "Skill ${skill.name}")
            return
        }
        val feature = selectedFeature()?.let { service.snapshot.relative(it.dir) }
        val safeName = skill.name.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val file = agent.writeInstructions("skill-$safeName.md", ExtraSkills.instructions(skill, content, feature, text)) ?: return
        agent.run(
            AgentTask(
                "Skill ${skill.name}",
                "Execute a skill ${skill.name} seguindo as instruções em $file.",
                write = true,
                origin = AttackTabs.LAUNCHER,
            ),
        )
    }

    /** Lançar confirma a feature escolhida: o [MissionRunner] grava a ativa antes de disparar. */
    private fun launch() {
        val mission = mission() ?: return
        if (runner.launch(mission)) {
            drafts.remove(mission.step)
            input.text = ""
            if (mission.feature != null) service.refresh()
        }
        updateButtons()
    }

    /** Copiar o comando também confirma a feature: no chat, o agente acha a feature pelo `.specify/feature.json`. */
    private fun copyCommand() {
        val mission = mission() ?: return
        val chosen = selectedFeature()
        if (mission.feature != null && chosen != null && chosen.dir != service.snapshot.active?.dir) service.activate(chosen.dir)
        runner.copyCommand(mission)
    }

    private fun mission(): Mission? {
        val status = selectedStatus() ?: return null
        val step = status.step
        return Mission(
            step = step,
            arguments = input.text,
            feature = selectedFeature()?.takeIf { step != SddStep.SPECIFY && step != SddStep.CONSTITUTION }?.let { service.snapshot.relative(it.dir) },
            mode = mode.selectedItem as? RunMode,
        )
    }

    private fun artifactOf(): Path? {
        val step = selectedStatus()?.step ?: return null
        val chosen = selectedFeature()
        return when (step) {
            SddStep.CONSTITUTION -> service.snapshot.layout?.constitution
            SddStep.SPECIFY, SddStep.CLARIFY -> chosen?.spec?.file
            SddStep.PLAN -> chosen?.plan?.file
            SddStep.CHECKLIST -> chosen?.customChecklists?.firstOrNull()?.file ?: chosen?.checklists?.firstOrNull()?.file
            SddStep.TASKS, SddStep.IMPLEMENT, SddStep.CONVERGE, SddStep.ANALYZE -> chosen?.tasks?.file
        }?.takeIf { Files.exists(it) }
    }

    private fun selectedFeature(): FeatureReport? = (feature.selectedItem as? FeatureChoice.Existing)?.report

    private fun selectedStatus(): StepStatus? = steps.selectedValue?.status

    private fun wrapRow() = JPanel(WrapLayout(FlowLayout.LEFT, JBUI.scale(6), JBUI.scale(3))).apply { isOpaque = false }

    /** Linha da etapa: o número (ou o ícone, na constituição), o nome, o estado e o motivo; o grupo abre com um título. */
    private inner class StepRenderer : ListCellRenderer<StepRow> {
        override fun getListCellRendererComponent(list: JList<out StepRow>, value: StepRow?, index: Int, selected: Boolean, focused: Boolean): Component {
            val row = JPanel(VerticalLayout(JBUI.scale(2))).apply {
                isOpaque = true
                background = UIUtil.getListBackground(selected, focused)
                border = JBUI.Borders.empty(5, 10)
            }
            value ?: return row
            val status = value.status
            val width = (list.width - JBUI.scale(20)).coerceAtLeast(JBUI.scale(120))
            val textColor = UIUtil.getListForeground(selected, focused)
            val muted = status.state == StepState.BLOCKED || status.state == StepState.UNAVAILABLE
            val line = FlowRow(width)
            when {
                status.state == StepState.DONE -> line.add(Pill("✓", Tone.GREEN, bold = true))
                value.mark.isEmpty() -> line.add(JBLabel(AllIcons.Nodes.Template))
                else -> line.add(Pill(value.mark, status.state.tone, bold = true))
            }
            line.add(WrapText(status.step.codename, JBFont.label().asBold(), if (muted && !selected) UIUtil.getContextHelpForeground() else textColor))
            if (status.next) line.add(Pill("Próxima", Tone.ORANGE, bold = true)) else line.add(Pill(status.state.label, status.state.tone))
            row.add(line)
            row.add(WrapText(status.reason, JBFont.small(), if (selected) textColor else UIUtil.getContextHelpForeground()).fit(width))
            val group = value.group ?: return row
            return JPanel(BorderLayout()).apply {
                isOpaque = true
                background = UIUtil.getListBackground()
                add(JBLabel(group.uppercase()).apply {
                    font = JBFont.small().asBold()
                    foreground = UIUtil.getContextHelpForeground()
                    border = JBUI.Borders.compound(
                        if (index > 0) JBUI.Borders.customLineTop(JBColor.border()) else JBUI.Borders.empty(),
                        JBUI.Borders.empty(8, 10, 3, 10),
                    )
                }, BorderLayout.NORTH)
                add(row, BorderLayout.CENTER)
            }
        }
    }

    private companion object {
        val ACTIVE = setOf(StepState.READY, StepState.PARTIAL, StepState.RECOMMENDED)
    }
}
