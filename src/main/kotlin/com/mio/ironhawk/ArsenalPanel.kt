package com.mio.ironhawk

import com.intellij.icons.AllIcons
import com.intellij.ide.ui.laf.darcula.ui.DarculaButtonUI
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.JBColor
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBTabbedPane
import com.intellij.ui.components.JBTextField
import com.intellij.ui.components.panels.VerticalLayout
import com.intellij.util.ui.ColorIcon
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.intellij.util.ui.WrapLayout
import com.mio.irondome.base.FitWidthPanel
import com.mio.irondome.base.IronDomeIcons
import com.mio.irondome.base.MechanismList
import com.mio.irondome.base.NoticeBox
import com.mio.irondome.base.Pill
import com.mio.irondome.base.SummaryHeader
import com.mio.irondome.base.Tone
import com.mio.irondome.base.refreshAction
import com.mio.irondome.base.wrapping
import com.mio.irondome.interceptor.AgentAutoSetup
import com.mio.irondome.interceptor.AgentHost
import com.mio.irondome.interceptor.AgentLaunch
import com.mio.irondome.interceptor.AgentProvider
import com.mio.irondome.interceptor.AgentRunMode
import com.mio.irondome.interceptor.AgentRunner
import com.mio.irondome.interceptor.AgentSettings
import com.mio.irondome.interceptor.AgentTerminal
import com.mio.irondome.interceptor.AgentTranscript
import com.mio.irondome.interceptor.AgentVerifier
import com.mio.tomahawk.run.DevContainer
import com.mio.tomahawk.run.ExtraSkills
import com.mio.tomahawk.run.Guardrails
import com.mio.tomahawk.run.LaunchTerminal
import com.mio.tomahawk.run.MissionRunner
import com.mio.tomahawk.run.Skill
import com.mio.tomahawk.run.SpecKitSetup
import com.mio.tomahawk.run.TomahawkSettings
import com.mio.tomahawk.speckit.SddStep
import com.mio.tomahawk.speckit.SpecKitService
import com.mio.tomahawk.ui.SpecKitSetupPanel
import org.jetbrains.annotations.TestOnly
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.DefaultListModel
import javax.swing.Icon
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.JSpinner
import javax.swing.SpinnerNumberModel
import javax.swing.SwingConstants

/**
 * O Arsenal, um só para os dois sistemas, em quatro abas: IA (a configuração automática e o CLI), Execução (onde rodar,
 * a segurança de cada modo e o devcontainer), Recursos (as skills extras e o Spec Kit do Ataque) e Execuções (as
 * recentes dos dois sistemas). O que vale só para um sistema leva a etiqueta dele e some com ele desligado. O que lê o
 * disco ou o PATH roda fora da EDT ([Probe]); a tela mostra a última leitura e se refaz quando a próxima chega.
 */
class ArsenalPanel(private val project: Project) : SimpleToolWindowPanel(true, true), Disposable {

    private class Status(val text: String, val ok: Boolean?)

    /** Uma execução da IA: um pedido da Defesa ou uma missão do Ataque. */
    private class Run(val path: Path, val title: String, val status: String, val ok: Boolean, val system: IronHawkSystem, val time: Long)

    /** O que a tela precisa do disco e do PATH, lido fora da EDT de uma vez. */
    private class Probe(
        val launch: AgentLaunch?,
        val ready: Boolean,
        val verifiedAt: String?,
        val terminalVerifiedAt: String?,
        val containerCli: Boolean,
        val containerConfig: Path?,
        val containerProblems: List<String>,
        val specify: Path?,
        val specifyInfo: SpecKitSetup.Installed?,
        val skills: List<Skill>,
    ) {
        companion object {
            fun read(root: Path?, attack: Boolean): Probe {
                val config = root?.let(DevContainer::config)?.takeIf { Files.isRegularFile(it) }
                val specify = if (attack) TomahawkSettings.resolveSpecify() else null
                val home = runCatching { Path.of(System.getProperty("user.home")) }.getOrNull()
                return Probe(
                    launch = AgentSettings.launch(),
                    ready = AgentSettings.ready,
                    verifiedAt = AgentSettings.verifiedAt,
                    terminalVerifiedAt = AgentSettings.terminalVerifiedAt,
                    containerCli = DevContainer.cli() != null,
                    containerConfig = config,
                    containerProblems = config?.let { DevContainer.audit(runCatching { Files.readString(it) }.getOrDefault("")) }.orEmpty(),
                    specify = specify,
                    specifyInfo = SpecKitSetup.infoFor(specify),
                    skills = if (attack) ExtraSkills.discover(root, home) else emptyList(),
                )
            }
        }
    }

    private val service = SpecKitService.getInstance(project)
    private val missions = MissionRunner.getInstance(project)
    private val agent = AgentRunner.getInstance(project)
    private val header = SummaryHeader(IronDomeIcons.arsenal, Tone.TEAL)

    private val provider = ComboBox(AgentProvider.entries.toTypedArray()).apply {
        renderer = SimpleListCellRenderer.create("") { "${it.title} CLI (${it.executables.first()})" }
        addActionListener { if (!loading) updateProviderFields() }
    }
    private val host = ComboBox(AgentHost.entries.toTypedArray()).apply {
        renderer = SimpleListCellRenderer.create("") { it.label }
        addActionListener { if (!loading) updateProviderFields() }
    }
    private val distro = ComboBox<String>().apply {
        isEditable = true
        addActionListener { if (!loading) updateProviderFields() }
    }
    /** O CLI achado em cada lugar, por `provedor|onde|caminho`: procurar roda o wsl.exe ou lê o PATH, fora da EDT. */
    private val foundCache = mutableMapOf<String, String?>()
    private val executable = TextFieldWithBrowseButton().apply {
        addBrowseFolderListener(project, FileChooserDescriptorFactory.createSingleFileNoJarsDescriptor().withTitle("Executável do CLI de IA"))
    }
    private val found = small("")
    private val model = JBTextField().apply { emptyText.text = "Padrão do CLI" }
    private val timeout = JSpinner(SpinnerNumberModel(AgentSettings.DEFAULT_TIMEOUT_MINUTES, 1, AgentSettings.MAX_TIMEOUT_MINUTES, 5))
    private val extraArgs = JBTextField().apply { emptyText.text = "Ex.: --approve-mcps" }

    /** Vazio mantém a chave salva; só o Remover a apaga, como nas configurações. */
    private val cursorKey = JBPasswordField()
    private val keyState = small("")
    private val clearKey = ActionLink("Remover a chave salva") {
        ApplicationManager.getApplication().executeOnPooledThread { AgentSettings.cursorApiKey = null }
        cursorKey.text = ""
    }
    private val providerNotice = NoticeBox(AllIcons.General.Warning, Tone.YELLOW)
    private val saveTest = JButton("Salvar e Testar", AllIcons.Actions.Execute).apply {
        putClientProperty(DarculaButtonUI.DEFAULT_STYLE_KEY, true)
        addActionListener { save(test = true) }
    }
    private val saveOnly = JButton("Salvar", AllIcons.Actions.MenuSaveall).apply { addActionListener { save(test = false) } }
    private val terminalTest = JButton("Testar no Terminal", AllIcons.Debugger.Console).apply { addActionListener { testTerminal() } }
    private val testLine = small("")
    private val terminalLine = small("")
    private val autoButton = JButton("Configurar Automaticamente", IronDomeIcons.ia).apply {
        putClientProperty(DarculaButtonUI.DEFAULT_STYLE_KEY, true)
        addActionListener { autoSetup() }
    }
    private val autoLine = small("")
    private val autoNotice = NoticeBox(AllIcons.General.Warning, Tone.YELLOW).apply { isVisible = false }

    private val hostRow = JPanel(VerticalLayout(JBUI.scale(3), VerticalLayout.FILL)).apply {
        isOpaque = false
        add(labeled("Onde está o CLI", host))
        add(small(HOST_HINT))
    }
    private val distroRow = labeled("Distribuição do WSL", distro)
    private val keyRow = JPanel(VerticalLayout(JBUI.scale(3), VerticalLayout.FILL)).apply {
        isOpaque = false
        add(labeled("Chave de API do Cursor", cursorKey))
        add(small(CURSOR_KEY_HINT))
        add(row(keyState, clearKey))
    }
    private val cliUses = small("")

    /** Tempo máximo e argumentos extras: recolhidos, porque quase ninguém mexe neles. */
    private val advanced = JPanel(VerticalLayout(JBUI.scale(8), VerticalLayout.FILL)).apply {
        isOpaque = false
        isVisible = false
        add(labeled("Tempo máximo (min)", timeout))
        add(small("Uma tarefa ou missão que passar disso é interrompida."))
        add(labeled("Argumentos extras", extraArgs))
        add(small("Vão para o CLI antes do pedido, separados por espaço; aspas agrupam."))
    }
    private val advancedToggle = ActionLink(ADVANCED_CLOSED) { advanced.isVisible = !advanced.isVisible; updateAdvancedToggle() }
    private val cliCard = JPanel(VerticalLayout(JBUI.scale(8), VerticalLayout.FILL)).apply {
        isOpaque = false
        add(subtitle("CLI de IA", AllIcons.Nodes.Console))
        add(cliUses)
        add(labeled("CLI", provider))
        add(providerNotice)
        add(hostRow)
        add(distroRow)
        add(JPanel(VerticalLayout(JBUI.scale(3), VerticalLayout.FILL)).apply {
            isOpaque = false
            add(labeled("Executável", executable))
            add(found)
        })
        add(labeled("Modelo", model))
        add(small("Como no --model do CLI."))
        add(keyRow)
        add(advancedToggle)
        add(advanced)
        add(row(saveTest, saveOnly, terminalTest))
        add(testLine)
        add(terminalLine)
    }
    private val autoCard = JPanel(VerticalLayout(JBUI.scale(8), VerticalLayout.FILL)).apply {
        isOpaque = false
        add(subtitle("Configuração automática", AllIcons.Actions.Lightning))
        add(small(AUTO_SETUP_HINT))
        add(row(autoButton))
        add(autoLine)
        add(autoNotice)
    }

    /** As abas de baixo do cabeçalho; as três últimas têm cartões que se montam de novo a cada mudança. */
    private enum class Tab(val title: String) { AI("IA"), EXECUTION("Execução"), RESOURCES("Recursos"), RUNS("Execuções") }

    /** O que falta numa aba: [tone] diz se é vermelho (quebrado) ou amarelo (falta fazer) e [reason], o motivo. */
    private class Pending(val tone: Tone, val reason: String)

    private fun stack() = JPanel(VerticalLayout(JBUI.scale(12), VerticalLayout.FILL)).apply {
        isOpaque = false
        border = JBUI.Borders.empty(10, 14, 16, 14)
    }

    private val aiTab = stack()
    private val executionTab = stack()
    private val resourcesTab = stack()
    private val runsTab = stack()
    private val tabs = JBTabbedPane()
    private var pendingTarget: Tab? = null
    private val pendingLink = ActionLink("") { pendingTarget?.let { tabs.selectedIndex = it.ordinal } }.apply { isVisible = false }
    private var initialTabChosen = false
    private val specKit = SpecKitSetupPanel(project, this, withInit = false)
    private val runsModel = DefaultListModel<Run>()
    private val runsList = MechanismList(runsModel, IronDomeIcons.ia, Tone.TEAL).apply {
        cellRenderer = RunRenderer()
        emptyText.text = "Nenhuma execução da IA neste projeto ainda"
        visibleRowCount = 10
    }

    private var loading = false
    private var saving = false
    private var defenseRuns: List<Run> = emptyList()
    private var testStatus: Status? = null
    private var terminalStatus: Status? = null
    private var containerStatus: Status? = null
    private var containerBusy = false
    private var securityDetailsOpen = false

    /** A última leitura do disco e do PATH; nula até a primeira chegar. */
    private var probe: Probe? = null
    private var probing = false
    private var probeAgain = false
    private var autoSetupChecked = false

    /** A primeira leitura do disco e do PATH já chegou à tela. */
    @get:TestOnly
    internal val probed: Boolean get() = probe != null

    init {
        aiTab.add(autoCard)
        aiTab.add(cliCard)
        listOf(Tab.AI to aiTab, Tab.EXECUTION to executionTab, Tab.RESOURCES to resourcesTab, Tab.RUNS to runsTab).forEach { (tab, panel) ->
            tabs.addTab(tab.title, ScrollPaneFactory.createScrollPane(FitWidthPanel(panel), true))
        }
        setContent(JPanel(BorderLayout()).apply {
            add(JPanel(BorderLayout()).apply {
                add(header, BorderLayout.NORTH)
                add(JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(14), 0)).apply {
                    isOpaque = false
                    add(pendingLink)
                }, BorderLayout.CENTER)
            }, BorderLayout.NORTH)
            add(tabs, BorderLayout.CENTER)
        })
        toolbar = ActionManager.getInstance().createActionToolbar(
            "IronHawk.ArsenalToolbar",
            DefaultActionGroup(refreshAction {
                foundCache.clear()
                loadDefenseRuns()
                service.refresh(0)
                ApplicationManager.getApplication().executeOnPooledThread { SpecKitSetup.check(force = true) }
                refreshCards()
            }),
            true,
        ).apply { targetComponent = this@ArsenalPanel }.wrapping().component
        runsList.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2) runsList.selectedValue?.let(::openRun)
            }
        })
        val app = ApplicationManager.getApplication()
        app.messageBus.connect(this).subscribe(AgentSettings.Listener.TOPIC, AgentSettings.Listener {
            app.invokeLater({
                if (!saving && !AgentAutoSetup.running) loadFields()
                refreshCards()
            }, project.disposed)
        })
        app.messageBus.connect(this).subscribe(TomahawkSettings.Listener.TOPIC, TomahawkSettings.Listener { app.invokeLater({ refreshCards() }, project.disposed) })
        app.messageBus.connect(this).subscribe(ExtraSkills.Listener.TOPIC, ExtraSkills.Listener { app.invokeLater({ refreshCards() }, project.disposed) })
        app.messageBus.connect(this).subscribe(IronHawkSystem.TOPIC, IronHawkSystem.Listener { app.invokeLater({ refreshCards() }, project.disposed) })
        app.messageBus.connect(this).subscribe(SpecKitSetup.Listener.TOPIC, SpecKitSetup.Listener { app.invokeLater({ refreshCards() }, project.disposed) })
        project.messageBus.connect(this).subscribe(SpecKitService.CHANGED, SpecKitService.Listener { refreshCards() })
        project.messageBus.connect(this).subscribe(AgentRunner.RUNS, AgentRunner.Listener { app.invokeLater({ loadDefenseRuns() }, project.disposed) })
        project.messageBus.connect(this).subscribe(MissionRunner.RUNS, MissionRunner.Listener { app.invokeLater({ refreshCards() }, project.disposed) })
        loadFields()
        loadDistros()
        loadDefenseRuns()
        refreshCards()
    }

    override fun dispose() = Unit

    private fun loadFields() {
        loading = true
        try {
            provider.selectedItem = AgentSettings.provider
            host.selectedItem = AgentSettings.host
            distro.selectedItem = AgentSettings.wslDistro
            executable.text = AgentSettings.executable.orEmpty()
            model.text = AgentSettings.model.orEmpty()
            timeout.value = AgentSettings.timeoutMinutes
            extraArgs.text = AgentSettings.extraArgs.orEmpty()
        } finally {
            loading = false
        }
        updateProviderFields()
    }

    /** As distribuições do WSL, para escolher; listar roda o wsl.exe. */
    private fun loadDistros() {
        if (!AgentLaunch.wslSupported) return
        ApplicationManager.getApplication().executeOnPooledThread {
            val names = AgentLaunch.distributions()
            ApplicationManager.getApplication().invokeLater({
                val chosen = distro.selectedItem as? String ?: AgentSettings.wslDistro
                loading = true
                try {
                    distro.removeAllItems()
                    names.forEach(distro::addItem)
                    distro.selectedItem = chosen ?: names.firstOrNull()
                } finally {
                    loading = false
                }
                updateProviderFields()
            }, project.disposed)
        }
    }

    private fun updateProviderFields() {
        val chosen = provider.selectedItem as? AgentProvider ?: return
        keyRow.isVisible = chosen == AgentProvider.CURSOR
        val savedKey = AgentSettings.hasCursorApiKey
        keyState.text = if (savedKey) "Há uma chave salva; deixe o campo vazio para mantê-la." else "Nenhuma chave salva."
        clearKey.isVisible = savedKey
        providerNotice.isVisible = chosen == AgentProvider.ANTIGRAVITY
        providerNotice.text = antigravityHint()
        hostRow.isVisible = AgentLaunch.wslSupported
        val inWsl = host.selectedItem == AgentHost.WSL && AgentLaunch.wslSupported
        distroRow.isVisible = inWsl
        val configured = executable.text.trim().ifEmpty { null }
        val name = (distro.selectedItem as? String)?.trim()?.ifEmpty { null }
        when {
            inWsl && name == null -> showFound(null, chosen, "Escolha a distribuição do WSL.")
            inWsl && configured != null -> showFound("WSL $name: $configured", chosen)
            else -> {
                val key = "${chosen.name}|${if (inWsl) name else ""}|${configured.orEmpty()}"
                if (key in foundCache) return showFound(foundCache[key], chosen)
                found.text = "Procurando o ${chosen.title} CLI${if (inWsl) " no WSL $name" else ""}…"
                found.foreground = UIUtil.getContextHelpForeground()
                ApplicationManager.getApplication().executeOnPooledThread {
                    val path = if (inWsl) AgentLaunch.findInWsl(chosen, name!!)?.let { "WSL $name: $it" }
                    else AgentSettings.resolveExecutable(chosen, configured)?.toString()
                    ApplicationManager.getApplication().invokeLater({
                        foundCache[key] = path
                        updateProviderFields()
                    }, project.disposed)
                }
            }
        }
    }

    private fun showFound(where: String?, chosen: AgentProvider, missing: String = "Não encontrado. ${chosen.installHint}") {
        found.text = where?.let { "✓ Encontrado: $it" } ?: missing
        found.foreground = if (where != null) Tone.GREEN.foreground else JBColor.RED
    }

    private fun save(test: Boolean) {
        saving = true
        AgentSettings.provider = provider.selectedItem as AgentProvider
        AgentSettings.host = if (AgentLaunch.wslSupported) host.selectedItem as? AgentHost ?: AgentHost.LOCAL else AgentHost.LOCAL
        AgentSettings.wslDistro = (distro.selectedItem as? String) ?: distro.editor.item?.toString()
        AgentSettings.executable = executable.text
        AgentSettings.model = model.text
        AgentSettings.timeoutMinutes = timeout.value as Int
        AgentSettings.extraArgs = extraArgs.text
        val key = String(cursorKey.password).trim()
        cursorKey.text = ""
        testStatus = Status(if (test) "Testando o ${AgentSettings.provider.title}…" else "Salvo.", if (test) null else true)
        refreshCards()
        ApplicationManager.getApplication().executeOnPooledThread {
            if (key.isNotEmpty()) AgentSettings.cursorApiKey = key
            ApplicationManager.getApplication().invokeLater({
                saving = false
                if (test) {
                    AgentVerifier.test { outcome ->
                        testStatus = Status(outcome.message, outcome.ok)
                        refreshCards()
                    }
                }
                refreshCards()
            }, project.disposed)
        }
    }

    private fun testTerminal() {
        terminalStatus = Status("Abrindo o terminal e esperando a IA gravar OK…", null)
        refreshCards()
        AgentVerifier.testTerminal(project) { outcome ->
            terminalStatus = Status(outcome.message, outcome.ok)
            refreshCards()
        }
    }

    private fun autoSetup() {
        autoNotice.isVisible = false
        AgentAutoSetup.run(
            onProgress = { message ->
                status(autoLine, Status(message, null))
                refreshCards()
            },
            onDone = { result ->
                foundCache.clear()
                loadFields()
                status(autoLine, Status(if (result.ok) result.message else "Não deu para configurar sozinho: termine nos campos do CLI.", result.ok))
                autoNotice.isVisible = !result.ok
                autoNotice.text = result.message
                testStatus = null
                refreshCards()
            },
        )
        refreshCards()
    }

    private fun loadDefenseRuns() {
        ApplicationManager.getApplication().executeOnPooledThread {
            val loaded = agent.transcripts().take(MAX_RUNS).map { path ->
                val (title, status) = AgentTranscript.summary(runCatching { Files.readAllLines(path).take(12) }.getOrDefault(emptyList()))
                val time = runCatching { Files.getLastModifiedTime(path).toMillis() }.getOrDefault(0L)
                Run(path, title ?: path.fileName.toString(), status.orEmpty(), status == "concluída", IronHawkSystem.DEFENSE, time)
            }
            ApplicationManager.getApplication().invokeLater({
                defenseRuns = loaded
                render()
            }, project.disposed)
        }
    }

    private fun runs(): List<Run> {
        val defense = if (IronHawkSystem.DEFENSE.enabled) defenseRuns else emptyList()
        val attack = if (IronHawkSystem.ATTACK.enabled) {
            service.snapshot.runs.map { Run(it.file, it.title, it.status.label, it.ok, IronHawkSystem.ATTACK, it.startedAt) }
        } else {
            emptyList()
        }
        return (defense + attack).sortedByDescending { it.time }.take(MAX_RUNS)
    }

    private fun openRun(run: Run) {
        if (run.system == IronHawkSystem.DEFENSE) agent.open(run.path) else missions.open(run.path)
    }

    /** Pede uma leitura nova do disco e do PATH e, enquanto ela não chega, mostra a última. */
    private fun refreshCards() {
        requestProbe()
        render()
    }

    private fun requestProbe() {
        if (probing) {
            probeAgain = true
            return
        }
        probing = true
        val root = service.root()
        val attack = IronHawkSystem.ATTACK.enabled
        ApplicationManager.getApplication().executeOnPooledThread {
            val fresh = runCatching { Probe.read(root, attack) }.getOrNull()
            ApplicationManager.getApplication().invokeLater({
                probing = false
                fresh?.let { probe = it }
                if (probeAgain) {
                    probeAgain = false
                    requestProbe()
                }
                render()
                fresh?.let(::autoSetupOnce)
            }, project.disposed)
        }
    }

    /** A configuração automática roda sozinha na primeira vez, se nada foi configurado à mão. */
    private fun autoSetupOnce(probe: Probe) {
        if (autoSetupChecked) return
        autoSetupChecked = true
        if (!probe.ready && !AgentSettings.autoSetupTried && AgentSettings.untouched) autoSetup()
    }

    /** O cabeçalho, os botões do CLI e os cartões de baixo, com o estado de agora e a última leitura do disco. */
    private fun render() {
        val probe = probe
        updateHeader(probe)
        val busy = AgentAutoSetup.running || AgentVerifier.testing
        autoButton.isEnabled = !busy
        autoButton.text = if (AgentAutoSetup.running) "Configurando…" else "Configurar Automaticamente"
        saveTest.isEnabled = !busy
        saveTest.text = if (AgentVerifier.testing) "Testando…" else "Salvar e Testar"
        saveOnly.isEnabled = !busy
        val terminal = AgentTerminal.available() != null
        terminalTest.isEnabled = terminal && !AgentVerifier.testingTerminal && probe?.launch != null
        terminalTest.text = if (AgentVerifier.testingTerminal) "Esperando o Terminal…" else "Testar no Terminal"
        terminalTest.toolTipText = if (terminal) "Abre o terminal da IDE e roda o CLI com a configuração salva" else "O plugin de terminal da IDE está desligado"
        status(testLine, testStatus ?: probe?.verifiedAt?.takeIf { probe.ready }?.let { Status("Testada em $it.", true) })
        status(terminalLine, terminalStatus ?: probe?.terminalVerifiedAt?.let { Status("Terminal testado em $it.", true) })
        cliUses.text = "O agente que executa " + listOfNotNull(
            "as tarefas da Defesa".takeIf { IronHawkSystem.DEFENSE.enabled },
            "as missões do Ataque".takeIf { IronHawkSystem.ATTACK.enabled },
        ).joinToString(" e ") + "."
        updateProviderFields()

        executionTab.removeAll()
        executionTab.add(runCard())
        executionTab.add(securityCard())
        executionTab.add(containerCard(probe))
        resourcesTab.removeAll()
        if (IronHawkSystem.ATTACK.enabled) {
            resourcesTab.add(skillsCard(probe))
            resourcesTab.add(specKitCard(probe))
        } else {
            resourcesTab.add(small("Os recursos do Ataque aparecem aqui quando ele está ligado."))
        }
        runsTab.removeAll()
        runsTab.add(small("Duplo clique abre o registro: o pedido, os passos, os arquivos alterados e a resposta."))
        runsModel.clear()
        runs().forEach(runsModel::addElement)
        runsTab.add(ScrollPaneFactory.createScrollPane(runsList).apply { preferredSize = JBUI.size(120, 320) })
        listOf(executionTab, resourcesTab, runsTab).forEach {
            it.revalidate()
            it.repaint()
        }
        updateTabs(probe)
    }

    private fun updateAdvancedToggle() {
        advancedToggle.text = if (advanced.isVisible) ADVANCED_OPEN else ADVANCED_CLOSED
    }

    /** A pendência de uma aba, ou nulo se está tudo certo nela. */
    private fun pending(tab: Tab, probe: Probe): Pending? = when (tab) {
        Tab.AI -> when {
            probe.launch == null -> Pending(Tone.RED, "a IA não está configurada")
            !probe.ready -> Pending(Tone.YELLOW, "a integração ainda não foi testada")
            else -> null
        }
        Tab.EXECUTION -> when {
            probe.containerProblems.isNotEmpty() -> Pending(Tone.RED, "o devcontainer não isola a máquina")
            // Perguntar a cada vez também pode rodar na aba: o devcontainer precisa estar pronto do mesmo jeito.
            AgentSettings.runMode != AgentRunMode.TERMINAL && (probe.containerConfig == null || !probe.containerCli) ->
                Pending(Tone.YELLOW, "a IA pode rodar na aba, mas o devcontainer não está pronto")
            else -> null
        }
        Tab.RESOURCES -> when {
            !IronHawkSystem.ATTACK.enabled -> null
            probe.specify == null -> Pending(Tone.YELLOW, "o specify do Spec Kit não está instalado")
            probe.specifyInfo?.broken == true -> Pending(Tone.RED, "o specify está com defeito")
            else -> null
        }
        Tab.RUNS -> null
    }

    /** Os pontos nos títulos das abas, o link da pendência no topo e, na primeira leitura, a aba em que abrir. */
    private fun updateTabs(probe: Probe?) {
        probe ?: return
        val pendings = Tab.entries.mapNotNull { tab -> pending(tab, probe)?.let { tab to it } }
        Tab.entries.forEach { tab ->
            val found = pendings.firstOrNull { it.first == tab }?.second
            tabs.setIconAt(tab.ordinal, found?.let { ColorIcon(8, it.tone.accent) })
        }
        val worst = pendings.firstOrNull { it.second.tone === Tone.RED } ?: pendings.firstOrNull()
        pendingTarget = worst?.first
        pendingLink.isVisible = worst != null
        worst?.let { pendingLink.text = "Resolver: ${it.second.reason} (aba ${it.first.title})" }
        if (!initialTabChosen) {
            initialTabChosen = true
            tabs.selectedIndex = (worst?.first ?: Tab.RUNS).ordinal
        }
    }

    private fun updateHeader(probe: Probe?) {
        val chosen = AgentSettings.provider
        val launch = probe?.launch
        val working = agent.running
        val subtitle = when {
            probe == null -> "${chosen.title} CLI"
            launch == null -> chosen.installHint
            else -> "${chosen.title} CLI${launch.where} · ${AgentSettings.model?.let { "modelo $it" } ?: "modelo padrão"}" +
                probe.verifiedAt?.let { " · testada em $it" }.orEmpty()
        }
        when {
            AgentAutoSetup.running -> header.update("Configurando a IA…", Tone.YELLOW.accent, autoLine.text)
            AgentVerifier.testing -> header.update("Testando a integração…", Tone.YELLOW.accent, subtitle)
            probe == null -> header.update("Verificando a IA…", Tone.GRAY.accent, subtitle)
            launch == null -> header.update("IA não configurada", Tone.GRAY.accent, subtitle,
                warning = if (AgentSettings.host == AgentHost.WSL) "Escolha a distribuição do WSL." else null)
            !probe.ready -> header.update("Teste a integração", Tone.YELLOW.accent, subtitle,
                warning = "Os botões de IA ficam desligados até Salvar e Testar passar.")
            working != null -> header.update("IA trabalhando: ${working.title}", Tone.GREEN.accent, subtitle)
            missions.progress != null -> header.update("Missão em voo", Tone.GREEN.accent, subtitle)
            else -> header.update("IA pronta", Tone.GREEN.accent, subtitle)
        }
    }

    /** Onde rodar a IA, uma escolha só para os dois sistemas. */
    private fun runCard(): JComponent = card {
        add(subtitle("Onde rodar a IA", AllIcons.Actions.Execute))
        add(labeled("Rodar", ComboBox(AgentRunMode.entries.toTypedArray()).apply {
            renderer = SimpleListCellRenderer.create("") { it.label }
            selectedItem = AgentSettings.runMode
            addActionListener { (selectedItem as? AgentRunMode)?.let { if (it != AgentSettings.runMode) AgentSettings.runMode = it } }
        }))
        add(small(RUN_MODE_HINT))
        if (IronHawkSystem.ATTACK.enabled) add(tagged(IronHawkSystem.ATTACK, "Cada etapa do Lançador pode trocar na hora de lançar."))
        if (LaunchTerminal.available() == null) add(small("O plugin de terminal da IDE está desligado: a IA só roda na aba.").apply { foreground = JBColor.RED })
    }

    /**
     * A segurança de cada modo, num cartão só: o que vale na aba, no terminal e nos textos, a contenção da Defesa (sempre
     * ligada) e as proteções das missões do Ataque (que dá para desligar). As flags de cada CLI ficam nos detalhes.
     */
    private fun securityCard(): JComponent = card {
        val defense = IronHawkSystem.DEFENSE.enabled
        val attack = IronHawkSystem.ATTACK.enabled
        add(subtitle("Segurança", AllIcons.Nodes.Padlock))
        caps(
            listOf(
                Triple(true, "Na aba", "sem perguntar, só no devcontainer, que enxerga só a pasta do projeto."),
                Triple(true, "No terminal", "nesta máquina, com a IA pedindo a sua aprovação antes de agir."),
                Triple(true, "Textos e revisões", "sempre só leitura, nos dois modos."),
            ),
        )
        if (defense) {
            add(tagged(IronHawkSystem.DEFENSE, "Contenção da IA, sempre ligada: foto do projeto antes de alterar e Desfazer Alterações da IA no fim."))
            add(JBCheckBox("Perguntar antes de cada tarefa que altera arquivos na aba", AgentSettings.confirmWrites).apply {
                isOpaque = false
                addActionListener { AgentSettings.confirmWrites = isSelected }
            })
        }
        if (attack) {
            val on = TomahawkSettings.guardrails
            add(JPanel(BorderLayout(JBUI.scale(6), 0)).apply {
                isOpaque = false
                add(JBCheckBox("Proteções das missões: regras, vigia e ponto de restauração", on).apply {
                    isOpaque = false
                    addActionListener {
                        TomahawkSettings.guardrails = isSelected
                        val enabled = isSelected
                        service.root()?.let { root ->
                            ApplicationManager.getApplication().executeOnPooledThread { runCatching { Guardrails.applyCursorConfig(root, enabled) } }
                        }
                        refreshCards()
                    }
                }, BorderLayout.CENTER)
                add(systemPill(IronHawkSystem.ATTACK), BorderLayout.EAST)
            })
            if (!on) {
                add(NoticeBox(AllIcons.General.Error, Tone.RED).apply {
                    text = "Desligadas: as missões rodam sem as regras do Ataque e sem ponto de restauração."
                })
            }
            add(row(JButton("Desfazer a Última Missão", AllIcons.Actions.Rollback).apply {
                isEnabled = missions.progress == null
                toolTipText = "Volta o projeto para o ponto de restauração da última missão, depois de mostrar o que mudou"
                addActionListener { missions.undo() }
            }))
        }
        add(ActionLink(if (securityDetailsOpen) DETAILS_OPEN else DETAILS_CLOSED) {
            securityDetailsOpen = !securityDetailsOpen
            render()
        })
        if (securityDetailsOpen) {
            if (defense) {
                add(tagged(IronHawkSystem.DEFENSE, DEFENSE_FLAGS))
                add(small(DEFENSE_CONTAINMENT))
            }
            if (attack) {
                add(tagged(IronHawkSystem.ATTACK, ATTACK_FLAGS))
                caps(ATTACK_GUARDS.map { Triple(TomahawkSettings.guardrails, it.first, it.second) })
            }
        }
    }

    private fun containerCard(probe: Probe?): JComponent = card {
        val root = service.root()
        val cli = probe?.containerCli
        val config = probe?.containerConfig
        val problems = probe?.containerProblems.orEmpty()
        add(subtitle("Devcontainer", AllIcons.Nodes.Deploy))
        add(small("Na aba, a IA dos dois sistemas roda só aqui; sem ele, só no terminal da IDE."))
        if (probe == null) {
            add(small("Conferindo o devcontainer…"))
            return@card
        }
        add(pills(
            if (cli == true) Pill("devcontainer CLI ✓", Tone.GREEN) else Pill("devcontainer CLI não instalado", Tone.RED),
            when {
                config == null -> Pill("projeto sem devcontainer", Tone.YELLOW)
                problems.isEmpty() -> Pill("isolamento conferido ✓", Tone.GREEN)
                else -> Pill("${problems.size} problema(s) de isolamento", Tone.RED)
            },
        ))
        if (cli != true) add(NoticeBox(AllIcons.General.Warning, Tone.YELLOW).apply { text = DevContainer.CLI_HINT })
        if (problems.isNotEmpty()) {
            add(NoticeBox(AllIcons.General.Error, Tone.RED).apply {
                text = "O ${DevContainer.CONFIG} não isola a máquina:\n" + problems.joinToString("\n") { "• $it" }
            })
        }
        when (AgentSettings.provider) {
            AgentProvider.CURSOR -> add(small("No container, o Cursor CLI entra pela chave de API da aba IA.").apply {
                if (!AgentSettings.hasCursorApiKey) foreground = JBColor.RED
            })
            AgentProvider.CODEX -> add(small("No container, o Codex usa o login feito lá dentro ou o OPENAI_API_KEY desta máquina."))
            AgentProvider.CLAUDE -> add(small("No container, o Claude Code usa o login feito lá dentro ou o ANTHROPIC_API_KEY desta máquina."))
            AgentProvider.ANTIGRAVITY -> add(small("O Antigravity não está no container: com ele, a IA só roda no terminal.").apply { foreground = JBColor.RED })
        }
        if (config == null) {
            add(row(primary("Criar o Devcontainer") { createContainer() }.apply {
                isEnabled = root != null
                toolTipText = "Grava ${DevContainer.CONFIG} com os CLIs de IA e o isolamento"
            }))
        } else {
            add(row(
                JButton("Abrir devcontainer.json", AllIcons.Actions.EditSource).apply { addActionListener { root?.let { missions.open(DevContainer.config(it)) } } },
                JButton(if (containerBusy) "Subindo…" else "Subir e Conferir", AllIcons.Actions.Upload).apply {
                    isEnabled = cli == true && !containerBusy && missions.progress == null
                    toolTipText = "Sobe o container (a primeira vez demora) e confere o isolamento"
                    addActionListener { root?.let(::containerUp) }
                },
            ))
        }
        containerStatus?.let { add(small("").also { label -> status(label, it) }) }
    }

    private fun createContainer() {
        val root = service.root() ?: return
        containerStatus = runCatching { DevContainer.create(root) }.fold(
            onSuccess = {
                missions.open(it)
                Status("Criado em ${DevContainer.CONFIG}. Se a IA precisar de mais ferramentas, acrescente-as em features.", true)
            },
            onFailure = { Status("Não deu para criar: ${it.message ?: it.javaClass.simpleName}", false) },
        )
        refreshCards()
    }

    private fun containerUp(root: Path) {
        containerBusy = true
        containerStatus = Status("Subindo o devcontainer (a primeira vez demora)…", null)
        render()
        var shown = 0L
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = runCatching {
                DevContainer.up(root, { line ->
                    val now = System.currentTimeMillis()
                    if (now - shown > 1_000) {
                        shown = now
                        ApplicationManager.getApplication().invokeLater({
                            if (containerBusy) containerStatus = Status("Subindo: $line", null)
                            render()
                        }, project.disposed)
                    }
                }, { false })
            }
            ApplicationManager.getApplication().invokeLater({
                containerBusy = false
                containerStatus = result.fold(
                    onSuccess = { Status("Container de pé e isolado: a IA só enxerga ${it.folder}.", true) },
                    onFailure = { Status(it.message ?: it.javaClass.simpleName, false) },
                )
                refreshCards()
            }, project.disposed)
        }
    }

    /** As skills extras do Ataque: as achadas no projeto e na máquina, quais viram botão no Lançador e as pastas indicadas. */
    private fun skillsCard(probe: Probe?): JComponent = card {
        val skills = probe?.skills.orEmpty()
        val chosen = ExtraSkills.selected
        add(subtitle("Skills extras", AllIcons.Nodes.Plugin, IronHawkSystem.ATTACK))
        add(small("As marcadas viram botões no Lançador, ao lado das etapas do Spec Kit. Vale só para você, nesta IDE."))
        if (probe == null) {
            add(small("Procurando as skills…"))
        } else if (skills.isEmpty()) {
            add(small("Nenhuma skill achada em ${ExtraSkills.FOLDERS.joinToString(", ")}. Se elas estão em outro lugar, indique a pasta."))
        }
        skills.forEach { skill ->
            add(JPanel(BorderLayout(JBUI.scale(6), 0)).apply {
                isOpaque = false
                add(JBCheckBox(skill.name, skill.name in chosen).apply {
                    isOpaque = false
                    addActionListener { toggleSkill(skill.name, isSelected) }
                }, BorderLayout.WEST)
                add(small(skill.description.ifEmpty { "Sem descrição." }.take(SKILL_DESCRIPTION_MAX)), BorderLayout.CENTER)
                add(Pill(skill.scope.label, Tone.GRAY), BorderLayout.EAST)
            })
        }
        if (probe != null) {
            chosen.filter { name -> skills.none { it.name == name } }.sorted().forEach { name ->
                add(JPanel(BorderLayout(JBUI.scale(6), 0)).apply {
                    isOpaque = false
                    add(JBCheckBox(name, true).apply {
                        isOpaque = false
                        addActionListener { toggleSkill(name, isSelected) }
                    }, BorderLayout.WEST)
                    add(small("Não encontrada aqui: o botão fica desligado no Lançador.").apply { foreground = JBColor.RED }, BorderLayout.CENTER)
                })
            }
        }
        ExtraSkills.customFolders.forEach { folder ->
            add(row(small("Pasta: $folder"), ActionLink("Remover") {
                ExtraSkills.customFolders = ExtraSkills.customFolders - folder
                ExtraSkills.notifyChanged()
            }))
        }
        add(row(JButton("Adicionar Pasta de Skills…", AllIcons.General.Add).apply {
            toolTipText = "Uma pasta com várias skills dentro, ou a pasta de uma skill só (com o SKILL.md)"
            addActionListener { addSkillsFolder() }
        }))
    }

    private fun toggleSkill(name: String, on: Boolean) {
        ExtraSkills.selected = if (on) ExtraSkills.selected + name else ExtraSkills.selected - name
        ExtraSkills.notifyChanged()
    }

    private fun addSkillsFolder() {
        val descriptor = FileChooserDescriptorFactory.createSingleFolderDescriptor().withTitle("Pasta de skills")
        FileChooser.chooseFile(descriptor, project, null) { folder ->
            ExtraSkills.customFolders = ExtraSkills.customFolders + Path.of(folder.path).normalize().toString()
            ExtraSkills.notifyChanged()
        }
    }

    private fun specKitCard(probe: Probe?): JComponent = card {
        val s = service.snapshot
        val specify = probe?.specify
        val info = probe?.specifyInfo
        val installing = SpecKitSetup.job != null
        val integration = TomahawkSettings.provider.integration
        add(subtitle("Spec Kit", AllIcons.Nodes.PpLib, IronHawkSystem.ATTACK))
        add(small("O CLI specify nesta máquina e os comandos instalados neste projeto."))
        add(pills(*listOfNotNull(
            when {
                probe == null -> Pill("procurando o specify…", Tone.GRAY)
                specify == null -> Pill("specify não instalado", Tone.RED)
                info?.broken == true -> Pill("specify com defeito", Tone.RED)
                info?.outdated == true -> Pill("specify v${info.version ?: "?"} · nova v${info.latest.orEmpty()}", Tone.ORANGE)
                else -> Pill("specify ${info?.version?.let { "v$it " }.orEmpty()}✓", Tone.GREEN)
            },
            if (s.initialized) Pill("projeto inicializado", Tone.GREEN) else Pill("projeto sem .specify/", Tone.YELLOW),
            s.integration.key?.let { Pill("integração $it", Tone.GRAY) },
            if (s.initialized) Pill("${s.commands.size}/${SddStep.entries.size} comandos", if (s.commands.size == SddStep.entries.size) Tone.GREEN else Tone.ORANGE) else null,
        ).toTypedArray()))
        specify?.let { add(small(it.toString())) }
        val key = s.integration.key
        if (s.initialized && key != null && key != integration && TomahawkSettings.supported) {
            add(NoticeBox(AllIcons.General.Warning, Tone.YELLOW).apply {
                text = "O projeto foi inicializado para $key, mas a IA é o ${TomahawkSettings.provider.title}: instale a integração dele."
            })
            add(row(JButton("Instalar a Integração $integration", AllIcons.General.Add).apply {
                isEnabled = specify != null && !installing && info?.broken != true
                addActionListener { SpecKitSetup.installIntegration(project) }
            }))
        }
        specKit.withInit = !s.initialized
        specKit.update()
        add(specKit)
        val buttons = listOfNotNull(
            JButton("Verificar de Novo", AllIcons.Actions.Refresh).apply {
                toolTipText = "Procura o specify de novo, relê a versão e o projeto"
                addActionListener {
                    ApplicationManager.getApplication().executeOnPooledThread { SpecKitSetup.recheck() }
                    service.refresh(0)
                }
            }.takeIf { specify != null && !installing },
            JButton("Remover do Projeto", AllIcons.Actions.GC).apply {
                isEnabled = !installing && missions.progress == null
                toolTipText = when {
                    installing -> "Espere o terminal do Spec Kit terminar"
                    missions.progress != null -> "Há uma missão em voo"
                    else -> "Apaga a .specify/ e os comandos /speckit da IA; pergunta antes se a specs/ vai junto"
                }
                addActionListener { if (missions.progress == null) SpecKitSetup.remove(project) }
            }.takeIf { s.initialized },
        )
        if (buttons.isNotEmpty()) add(row(*buttons.toTypedArray()))
    }

    private fun card(build: JPanel.() -> Unit): JComponent =
        JPanel(VerticalLayout(JBUI.scale(8), VerticalLayout.FILL)).apply {
            isOpaque = false
            build()
        }

    private fun JPanel.caps(items: List<Triple<Boolean, String, String>>) {
        items.forEach { (on, title, text) ->
            add(JPanel(BorderLayout(JBUI.scale(6), 0)).apply {
                isOpaque = false
                add(JBLabel(if (on) AllIcons.General.InspectionsOK else AllIcons.General.InspectionsPause).apply { verticalAlignment = SwingConstants.TOP }, BorderLayout.WEST)
                add(html("<b>${escape(title)}</b>: ${escape(text)}"), BorderLayout.CENTER)
            })
        }
    }

    private fun subtitle(text: String, icon: Icon, system: IronHawkSystem? = null) = JPanel(WrapLayout(FlowLayout.LEFT, JBUI.scale(6), 0)).apply {
        isOpaque = false
        add(JBLabel(text, icon, SwingConstants.LEFT).apply { font = JBFont.label().asBold() })
        system?.let { add(systemPill(it)) }
    }

    private fun systemPill(system: IronHawkSystem) =
        if (system == IronHawkSystem.DEFENSE) Pill("Defesa", Tone.BLUE, bold = true) else Pill("Ataque", Tone.ORANGE, bold = true)

    private fun tagged(system: IronHawkSystem, text: String) = JPanel(BorderLayout(JBUI.scale(6), 0)).apply {
        isOpaque = false
        add(JPanel(BorderLayout()).apply {
            isOpaque = false
            add(systemPill(system), BorderLayout.NORTH)
        }, BorderLayout.WEST)
        add(small(text), BorderLayout.CENTER)
    }

    private fun pills(vararg items: JComponent) = JPanel(WrapLayout(FlowLayout.LEFT, JBUI.scale(6), JBUI.scale(2))).apply {
        isOpaque = false
        items.forEach(::add)
    }

    private fun row(vararg items: JComponent) = JPanel(WrapLayout(FlowLayout.LEFT, JBUI.scale(6), JBUI.scale(2))).apply {
        isOpaque = false
        items.forEach(::add)
    }

    private fun primary(text: String, action: () -> Unit) = JButton(text, AllIcons.General.Add).apply {
        putClientProperty(DarculaButtonUI.DEFAULT_STYLE_KEY, true)
        addActionListener { action() }
    }

    private fun labeled(label: String, field: JComponent) = JPanel(BorderLayout(0, JBUI.scale(3))).apply {
        isOpaque = false
        add(JBLabel(label).apply { font = JBFont.small().asBold(); foreground = UIUtil.getContextHelpForeground() }, BorderLayout.NORTH)
        add(field, BorderLayout.CENTER)
    }

    private fun small(text: String) = JBLabel("<html>${escape(text)}</html>").apply {
        componentStyle = UIUtil.ComponentStyle.SMALL
        foreground = UIUtil.getContextHelpForeground()
        setAllowAutoWrapping(true)
    }

    private fun html(content: String) = JBLabel("<html>$content</html>").apply { setAllowAutoWrapping(true) }

    private fun status(label: JBLabel, status: Status?) {
        label.isVisible = status != null
        status ?: return
        label.text = "<html>${escape(status.text)}</html>"
        label.foreground = when (status.ok) {
            true -> Tone.GREEN.foreground
            false -> JBColor.RED
            null -> UIUtil.getContextHelpForeground()
        }
    }

    private fun escape(text: String) = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\n", "<br>")

    private class RunRenderer : ColoredListCellRenderer<Run>() {
        override fun customizeCellRenderer(list: JList<out Run>, value: Run?, index: Int, selected: Boolean, hasFocus: Boolean) {
            value ?: return
            icon = if (value.ok) AllIcons.General.InspectionsOK else AllIcons.General.Warning
            append(if (value.system == IronHawkSystem.DEFENSE) "Defesa  " else "Ataque  ", SimpleTextAttributes.GRAYED_BOLD_ATTRIBUTES)
            append(value.title)
            append("  ${value.status}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
            append("  ${value.path.fileName.toString().take(15)}", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
        }
    }

    companion object {
        internal const val MAX_RUNS = 30
        internal const val SKILL_DESCRIPTION_MAX = 160
        internal const val ADVANCED_CLOSED = "Opções avançadas ▸"
        internal const val ADVANCED_OPEN = "Opções avançadas ▾"
        internal const val DETAILS_CLOSED = "Como cada CLI roda ▸"
        internal const val DETAILS_OPEN = "Como cada CLI roda ▾"

        /** Os textos curtos que a aba Arsenal e as configurações (Arsenal (IA)) mostram iguais. */
        const val RUN_MODE_HINT = "Na aba, a IA roda sem perguntar no devcontainer; no terminal da IDE, à vista e pedindo aprovação."
        const val HOST_HINT = "Vale para o terminal e o teste; na aba, a IA usa o CLI do devcontainer."
        const val CURSOR_KEY_HINT = "Opcional: sem ela, vale o agent login. Fica no cofre de senhas da IDE."
        const val AUTO_SETUP_HINT = "Procura os CLIs de IA instalados e testa cada um, com o modelo padrão, até um responder."

        /** O Antigravity não está no devcontainer nem no Spec Kit: só roda no terminal, e o `agy -p` pode travar. */
        fun antigravityHint(): String =
            "O Antigravity só roda no terminal da IDE" + (if (IronHawkSystem.ATTACK.enabled) " e não lança missões do Ataque" else "") +
                ". O agy -p pode travar; o tempo máximo interrompe."

        /** Nos detalhes do cartão Segurança. */
        private const val DEFENSE_FLAGS = "Na aba, nas tarefas que alteram arquivos: Cursor com --force, Claude Code com --permission-mode acceptEdits " +
            "(comandos só os liberados nele) e Codex com --dangerously-bypass-approvals-and-sandbox. Nas de leitura, ninguém escreve."
        private const val DEFENSE_CONTAINMENT = "Sem git push e só com a credencial do CLI; a IA é parada ao tentar apagar pastas, descartar " +
            "alterações do git, reescrever o histórico, rodar como administrador ou escrever fora do projeto ou no .git."
        private const val ATTACK_FLAGS = "Nas missões na aba: Cursor com --force --trust, Claude Code com --permission-mode bypassPermissions e " +
            "Codex com --dangerously-bypass-approvals-and-sandbox. Commit, PR e Rádio: só leitura."
        private val ATTACK_GUARDS = listOf(
            "Barrado antes de rodar" to "apagar pastas, formatar discos, pedir administrador, instalar programas, git push/reset --hard/clean.",
            "Vigia" to "a missão para num comando perigoso, numa escrita fora do projeto ou no .git, ou ao ler credenciais.",
            "Ponto de restauração" to "o projeto vai para o Git (refs/tomahawk/) antes de cada missão, sem mexer na sua branch.",
            "Sem segredos" to "tokens e chaves do ambiente (GitHub, nuvem, npm) não chegam à IA.",
        )
    }
}
