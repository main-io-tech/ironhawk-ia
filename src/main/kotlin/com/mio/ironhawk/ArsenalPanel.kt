package com.mio.ironhawk

import com.intellij.icons.AllIcons
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction
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
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.JBColor
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.SearchTextField
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.TitledSeparator
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBRadioButton
import com.intellij.ui.components.JBTabbedPane
import com.intellij.ui.components.JBTextField
import com.intellij.ui.components.panels.VerticalLayout
import com.intellij.util.ui.ColorIcon
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.intellij.util.ui.WrapLayout
import com.mio.irondome.base.Collapsible
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
import javax.swing.ButtonGroup
import javax.swing.DefaultListModel
import javax.swing.Icon
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.SwingConstants
import javax.swing.event.DocumentEvent

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
    private val runsTab = stack()

    /** Recursos: as skills extras e o Spec Kit, em subabas; com o Ataque desligado, só o aviso. */
    private val skillsTab = stack()
    private val specKitTab = stack()
    private val resourcesTabs = JBTabbedPane().apply {
        addTab("Skills", AllIcons.Nodes.Plugin, scroll(skillsTab))
        addTab("Spec Kit", AllIcons.Nodes.PpLib, scroll(specKitTab))
    }
    private val resourcesOff = stack().apply { add(small("Os recursos do Ataque aparecem aqui quando ele está ligado.")) }
    private val resourcesTab = JPanel(BorderLayout())

    /** O que a lista de skills mostra: todas, só as do Lançador ou só as que ainda não estão nele. */
    private enum class SkillFilter(val label: String) { ALL("Todas"), ADDED("No Lançador"), AVAILABLE("Disponíveis") }

    private var skillFilter = SkillFilter.ALL
    private val skillSearch = SearchTextField(false).apply {
        textEditor.emptyText.text = "Buscar pelo nome ou pela descrição"
        addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) = renderSkills()
        })
    }
    private val skillFilters = ButtonGroup().let { group ->
        SkillFilter.entries.map { filter ->
            JBRadioButton(filter.label, filter == skillFilter).apply {
                isOpaque = false
                group.add(this)
                addActionListener {
                    skillFilter = filter
                    renderSkills()
                }
            }
        }
    }
    /** A confirmação da última skill adicionada ou removida, embaixo da busca. */
    private val skillFeedback = small("").apply { isVisible = false }
    private val skillList = JPanel(VerticalLayout(JBUI.scale(10), VerticalLayout.FILL)).apply { isOpaque = false }
    private val skillFolders = JPanel(VerticalLayout(JBUI.scale(4), VerticalLayout.FILL)).apply { isOpaque = false }
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
    private var defenseDetailsOpen = false
    private var attackDetailsOpen = false

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
        buildSkillsTab()
        Tab.entries.forEach { tab ->
            tabs.addTab(
                tab.title,
                when (tab) {
                    Tab.AI -> scroll(aiTab)
                    Tab.EXECUTION -> scroll(executionTab)
                    // As subabas de Recursos têm a rolagem delas.
                    Tab.RESOURCES -> resourcesTab
                    Tab.RUNS -> scroll(runsTab)
                },
            )
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
            }, object : DumbAwareAction("Limpar Pasta do IronHawk", "Escolhe o que apagar da pasta ${IronHawkFolder.ROOT}/ do projeto, ou tudo", AllIcons.Actions.GC) {
                override fun actionPerformed(e: AnActionEvent) {
                    if (IronHawkCleanupDialog.show(project)) {
                        loadDefenseRuns()
                        refreshCards()
                    }
                }
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
        executionTab.add(section(Section.WHERE, whereCard()))
        if (IronHawkSystem.DEFENSE.enabled) executionTab.add(section(Section.DEFENSE, defenseCard()))
        if (IronHawkSystem.ATTACK.enabled) executionTab.add(section(Section.ATTACK, attackCard()))
        executionTab.add(section(Section.CONTAINER, containerCard(probe)))
        resourcesTab.removeAll()
        if (IronHawkSystem.ATTACK.enabled) {
            resourcesTab.add(resourcesTabs, BorderLayout.CENTER)
            specKitTab.removeAll()
            specKitTab.add(specKitCard(probe))
            renderSkills()
        } else {
            resourcesTab.add(resourcesOff, BorderLayout.CENTER)
        }
        runsTab.removeAll()
        runsTab.add(small("Clique duas vezes para abrir o registro: o pedido, os passos, os arquivos alterados e a resposta."))
        runsModel.clear()
        runs().forEach(runsModel::addElement)
        runsTab.add(ScrollPaneFactory.createScrollPane(runsList).apply { preferredSize = JBUI.size(120, 320) })
        listOf(executionTab, resourcesTab, specKitTab, runsTab).forEach {
            it.revalidate()
            it.repaint()
        }
        updateTabs(probe)
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

    /**
     * As seções da aba Execução, cada uma recolhível pelo título. O que vale para os dois sistemas fica em Onde rodar a
     * IA e em Devcontainer; o que é só de um fica na seção dele, que some com ele desligado.
     */
    private enum class Section(val title: String) { WHERE("Onde rodar a IA"), DEFENSE("Defesa"), ATTACK("Ataque"), CONTAINER("Devcontainer") }

    /** Uma seção que abre e fecha pelo título; aberta ou recolhida, fica assim na próxima vez. */
    private fun section(section: Section, content: JComponent): JComponent {
        val key = "IronHawk.Arsenal.Section.${section.name}"
        val properties = PropertiesComponent.getInstance()
        val host = JPanel().apply { isOpaque = false }
        content.border = JBUI.Borders.empty(6, 18, 6, 0)
        Collapsible(host, section.title, properties.getBoolean(key, true)) { open -> properties.setValue(key, open, true) }
            .setContentComponent(content)
        return host
    }

    /** Onde rodar a IA, uma escolha só para os dois sistemas, e o que vale em cada modo. */
    private fun whereCard(): JComponent = card {
        add(labeled("Rodar", ComboBox(AgentRunMode.entries.toTypedArray()).apply {
            renderer = SimpleListCellRenderer.create("") { it.label }
            selectedItem = AgentSettings.runMode
            addActionListener { (selectedItem as? AgentRunMode)?.let { if (it != AgentSettings.runMode) AgentSettings.runMode = it } }
        }))
        caps(
            listOf(
                Triple(true, "Na aba", "a IA roda sem perguntar, só no devcontainer, que enxerga só a pasta do projeto."),
                Triple(true, "No terminal", "a IA roda nesta máquina e pede a sua aprovação antes de agir."),
                Triple(true, "Textos e revisões", "sempre só leitura, nos dois modos."),
            ),
        )
        if (IronHawkSystem.ATTACK.enabled) add(small("No Lançador, cada etapa pode trocar o modo na hora de lançar."))
        if (LaunchTerminal.available() == null) add(NoticeBox(AllIcons.General.Warning, Tone.YELLOW).apply {
            text = "O plugin de terminal da IDE está desligado: a IA só roda na aba. Ligue-o em Settings > Plugins."
        })
    }

    /** O que vale só para a Defesa: a contenção, sempre ligada, e a pergunta antes de alterar arquivos. */
    private fun defenseCard(): JComponent = card {
        caps(listOf(Triple(true, "Contenção da IA", CONTAINMENT)))
        add(JBCheckBox("Perguntar antes de cada tarefa que altera arquivos na aba", AgentSettings.confirmWrites).apply {
            isOpaque = false
            addActionListener { AgentSettings.confirmWrites = isSelected }
        })
        add(detailsLink(defenseDetailsOpen) { defenseDetailsOpen = !defenseDetailsOpen })
        if (defenseDetailsOpen) {
            add(small(DEFENSE_FLAGS))
            add(small(DEFENSE_CONTAINMENT))
        }
    }

    /** O que vale só para o Ataque: as proteções das missões, que dá para desligar, e o desfazer da última missão. */
    private fun attackCard(): JComponent = card {
        val on = TomahawkSettings.guardrails
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
        })
        if (!on) {
            add(NoticeBox(AllIcons.General.Error, Tone.RED).apply {
                text = "As missões rodam sem as regras do Ataque e sem ponto de restauração. Ligue as proteções para poder desfazer."
            })
        }
        add(row(JButton("Desfazer a Última Missão", AllIcons.Actions.Rollback).apply {
            isEnabled = missions.progress == null
            toolTipText = "Mostra o que mudou e volta o projeto ao ponto anterior à missão"
            addActionListener { missions.undo() }
        }))
        add(detailsLink(attackDetailsOpen) { attackDetailsOpen = !attackDetailsOpen })
        if (attackDetailsOpen) {
            add(small(ATTACK_FLAGS))
            caps(ATTACK_GUARDS.map { Triple(TomahawkSettings.guardrails, it.first, it.second) })
        }
    }

    /** "Como cada CLI roda": as flags de cada CLI, recolhidas até o usuário pedir. */
    private fun detailsLink(open: Boolean, toggle: () -> Unit) = ActionLink(if (open) DETAILS_OPEN else DETAILS_CLOSED) {
        toggle()
        render()
    }

    private fun containerCard(probe: Probe?): JComponent = card {
        val root = service.root()
        val cli = probe?.containerCli
        val config = probe?.containerConfig
        val problems = probe?.containerProblems.orEmpty()
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

    /** A subaba Skills, montada uma vez: a busca e o filtro ficam, a lista se refaz a cada mudança. */
    private fun buildSkillsTab() {
        skillsTab.add(subtitle("Skills extras", AllIcons.Nodes.Plugin, IronHawkSystem.ATTACK))
        skillsTab.add(small("Cada skill adicionada vira um botão no Lançador. Vale só para você, nesta IDE."))
        skillsTab.add(skillSearch)
        skillsTab.add(row(*skillFilters.toTypedArray()))
        skillsTab.add(skillFeedback)
        skillsTab.add(skillList)
        skillsTab.add(TitledSeparator("Pastas de skills"))
        skillsTab.add(skillFolders)
    }

    /** Refaz a lista de skills com a busca e o filtro de agora e a última leitura do disco. */
    private fun renderSkills() {
        val probe = probe
        val found = probe?.skills.orEmpty()
        val chosen = ExtraSkills.selected
        val query = skillSearch.text.trim()
        fun matches(name: String, description: String) =
            query.isEmpty() || name.contains(query, ignoreCase = true) || description.contains(query, ignoreCase = true)
        skillList.removeAll()
        when {
            probe == null -> skillList.add(small("Procurando as skills…"))
            found.isEmpty() && chosen.isEmpty() ->
                skillList.add(small("Nenhuma skill em ${ExtraSkills.FOLDERS.joinToString(", ")}. Indique a pasta delas abaixo."))
            else -> {
                val rows = buildList {
                    found.filter { skill ->
                        val added = skill.name in chosen
                        matches(skill.name, skill.description) && when (skillFilter) {
                            SkillFilter.ALL -> true
                            SkillFilter.ADDED -> added
                            SkillFilter.AVAILABLE -> !added
                        }
                    }.forEach { skill -> add(skillRow(skill.name, skill.description.ifEmpty { "Sem descrição." }, skill.scope.label, skill.name in chosen, missing = false)) }
                    // Adicionada antes e não achada agora: continua na lista para poder sair do Lançador.
                    if (skillFilter != SkillFilter.AVAILABLE) {
                        chosen.filter { name -> found.none { it.name == name } && matches(name, "") }.sorted().forEach { name ->
                            add(skillRow(name, "Não encontrada no projeto nem nesta máquina: o botão fica desligado no Lançador.", null, added = true, missing = true))
                        }
                    }
                }
                if (rows.isEmpty()) skillList.add(small(if (query.isEmpty()) "Nenhuma skill com esse filtro." else "Nenhuma skill encontrada para \"$query\"."))
                rows.forEach(skillList::add)
            }
        }
        skillFolders.removeAll()
        ExtraSkills.customFolders.forEach { folder ->
            skillFolders.add(row(small(folder), ActionLink("Remover") {
                ExtraSkills.customFolders = ExtraSkills.customFolders - folder
                ExtraSkills.notifyChanged()
            }))
        }
        skillFolders.add(row(JButton("Adicionar Pasta de Skills…", AllIcons.General.Add).apply {
            toolTipText = "Uma pasta com várias skills, ou a pasta de uma skill com o SKILL.md"
            addActionListener { addSkillsFolder() }
        }))
        listOf(skillList, skillFolders).forEach {
            it.revalidate()
            it.repaint()
        }
    }

    /** Uma skill: o nome, de onde vem, se está no Lançador e o botão que muda isso. */
    private fun skillRow(name: String, description: String, scope: String?, added: Boolean, missing: Boolean): JComponent =
        JPanel(BorderLayout(JBUI.scale(8), 0)).apply {
            isOpaque = false
            add(JPanel(VerticalLayout(JBUI.scale(2), VerticalLayout.FILL)).apply {
                isOpaque = false
                add(row(*listOfNotNull(
                    JBLabel(name).apply { font = JBFont.label().asBold() },
                    scope?.let { Pill(it, Tone.GRAY) },
                    when {
                        missing -> Pill("Não encontrada", Tone.RED)
                        added -> Pill("✓ No Lançador", Tone.GREEN)
                        else -> null
                    },
                ).toTypedArray()))
                add(small(description.take(SKILL_DESCRIPTION_MAX)).apply { if (missing) foreground = JBColor.RED })
            }, BorderLayout.CENTER)
            add(JPanel(BorderLayout()).apply {
                isOpaque = false
                add(
                    JButton(if (added) "Remover" else "Adicionar", if (added) AllIcons.General.Remove else AllIcons.General.Add).apply {
                        toolTipText = if (added) "Tira o botão da skill do Lançador" else "Põe um botão da skill no Lançador"
                        addActionListener { toggleSkill(name, !added) }
                    },
                    BorderLayout.NORTH,
                )
            }, BorderLayout.EAST)
        }

    /** Adiciona ou remove a skill do Lançador e confirma embaixo da busca. */
    private fun toggleSkill(name: String, on: Boolean) {
        ExtraSkills.selected = if (on) ExtraSkills.selected + name else ExtraSkills.selected - name
        status(skillFeedback, Status(if (on) "Skill $name adicionada ao Lançador." else "Skill $name removida do Lançador.", if (on) true else null))
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

    private fun scroll(panel: JPanel): JComponent = ScrollPaneFactory.createScrollPane(FitWidthPanel(panel), true)

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
            append("  ${whenOf(value.path.fileName.toString())}", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
        }

        /** O carimbo do nome do registro (`20261118-072934-...`) como data e hora; o nome, se não for um carimbo. */
        private fun whenOf(name: String): String =
            STAMP.find(name)?.destructured?.let { (_, month, day, hour, minute) -> "$day/$month $hour:$minute" } ?: name.take(15)

        private companion object {
            val STAMP = Regex("""^(\d{4})(\d{2})(\d{2})-(\d{2})(\d{2})\d{2}""")
        }
    }

    companion object {
        internal const val MAX_RUNS = 30
        internal const val SKILL_DESCRIPTION_MAX = 160
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

        /** Na seção Defesa: a contenção, sempre ligada. */
        private const val CONTAINMENT = "sempre ligada. Antes de alterar, guarda uma foto do projeto; no fim, oferece Desfazer Alterações da IA."

        /** Nos detalhes das seções Defesa e Ataque. */
        private const val DEFENSE_FLAGS = "Na aba, nas tarefas que alteram arquivos: Cursor com --force, Claude Code com --permission-mode acceptEdits " +
            "(comandos só os liberados nele, mais git rm de arquivos) e Codex com --dangerously-bypass-approvals-and-sandbox. Nas de leitura, ninguém escreve."
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
