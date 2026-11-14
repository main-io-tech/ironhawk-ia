package com.mio.ironhawk

import com.intellij.icons.AllIcons
import com.intellij.ide.ui.laf.darcula.ui.DarculaButtonUI
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.ApplicationManager
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
import com.intellij.ui.TitledSeparator
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBTextField
import com.intellij.ui.components.panels.VerticalLayout
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
import com.mio.tomahawk.run.Guardrails
import com.mio.tomahawk.run.LaunchTerminal
import com.mio.tomahawk.run.MissionRunner
import com.mio.tomahawk.run.SpecKitSetup
import com.mio.tomahawk.run.TomahawkSettings
import com.mio.tomahawk.speckit.SddStep
import com.mio.tomahawk.speckit.SpecKitService
import com.mio.tomahawk.ui.SpecKitSetupPanel
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
 * O Arsenal, um só para os dois sistemas, organizado por assunto: a IA que os dois usam (configuração automática, o
 * CLI, onde ele está, o executável, o modelo, o tempo, os argumentos, a chave do Cursor e os testes); onde rodar a IA,
 * uma escolha só; as proteções; o que a IA libera e o Spec Kit; e as execuções recentes. O que vale só para um sistema
 * leva a etiqueta dele e some com ele desligado.
 */
class ArsenalPanel(private val project: Project) : SimpleToolWindowPanel(true, true), Disposable {

    private class Status(val text: String, val ok: Boolean?)

    /** Uma execução da IA: um pedido da Defesa ou uma missão do Ataque. */
    private class Run(val path: Path, val title: String, val status: String, val ok: Boolean, val system: IronHawkSystem, val time: Long)

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
    /** O CLI achado em cada distribuição, por `provedor|distribuição`: procurar roda o wsl.exe. */
    private val wslFound = mutableMapOf<String, String?>()
    private val executable = TextFieldWithBrowseButton().apply {
        addBrowseFolderListener(project, FileChooserDescriptorFactory.createSingleFileNoJarsDescriptor().withTitle("Executável do CLI de IA"))
    }
    private val found = small("")
    private val model = JBTextField().apply { emptyText.text = "Padrão do CLI" }
    private val timeout = JSpinner(SpinnerNumberModel(AgentSettings.DEFAULT_TIMEOUT_MINUTES, 1, AgentSettings.MAX_TIMEOUT_MINUTES, 5))
    private val extraArgs = JBTextField().apply { emptyText.text = "Ex.: --approve-mcps" }
    private val cursorKey = JBPasswordField()
    private val clearKey = ActionLink("Remover a chave salva") {
        ApplicationManager.getApplication().executeOnPooledThread { AgentSettings.cursorApiKey = null }
        cursorKey.text = ""
    }
    private val providerNotice = NoticeBox(AllIcons.General.Warning, Tone.YELLOW)
    private val hangNotice = NoticeBox(AllIcons.General.Warning, Tone.YELLOW).apply {
        text = "O agy -p pode travar quando chamado por outro programa (issue 318 do antigravity-cli); o tempo máximo interrompe."
    }
    private val saveTest = JButton("Salvar e Testar", AllIcons.Actions.Execute).apply {
        putClientProperty(DarculaButtonUI.DEFAULT_STYLE_KEY, true)
        addActionListener { save(test = true) }
    }
    private val saveOnly = JButton("Salvar").apply { addActionListener { save(test = false) } }
    private val terminalTest = JButton("Testar no Terminal", AllIcons.Debugger.Console).apply { addActionListener { testTerminal() } }
    private val testLine = small("")
    private val terminalLine = small("")
    private val autoButton = JButton("Configurar Automaticamente", IronDomeIcons.ia).apply {
        putClientProperty(DarculaButtonUI.DEFAULT_STYLE_KEY, true)
        addActionListener { autoSetup() }
    }
    private val autoLine = small("")
    private val autoNotice = NoticeBox(AllIcons.General.Warning, Tone.YELLOW).apply { isVisible = false }

    private val hostRow = labeled("Onde está o CLI", host)
    private val distroRow = labeled("Distribuição do WSL", distro)
    private val keyRow = JPanel(VerticalLayout(JBUI.scale(3), VerticalLayout.FILL)).apply {
        isOpaque = false
        add(labeled("Chave de API do Cursor", cursorKey))
        add(small("Opcional: sem ela, vale o agent login. Crie em cursor.com/dashboard › Integrations. Fica no cofre de senhas da IDE."))
        add(clearKey)
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
        add(labeled("Tempo máximo (min)", timeout))
        add(small("Uma tarefa ou missão que passar disso é interrompida."))
        add(labeled("Argumentos extras", extraArgs))
        add(small("Vão para o CLI antes do pedido, separados por espaço; aspas agrupam."))
        add(keyRow)
        add(hangNotice)
        add(row(saveTest, saveOnly, terminalTest))
        add(testLine)
        add(terminalLine)
    }
    private val autoCard = JPanel(VerticalLayout(JBUI.scale(8), VerticalLayout.FILL)).apply {
        isOpaque = false
        add(subtitle("Configuração automática", AllIcons.Actions.Lightning))
        add(small(
            "Procura o ${AgentProvider.entries.joinToString(", ") { "${it.title} CLI" }} instalados" +
                (if (AgentLaunch.wslSupported) ", nesta máquina e no WSL," else "") + " e testa cada um, com o modelo padrão, até um responder.",
        ))
        add(row(autoButton))
        add(autoLine)
        add(autoNotice)
    }

    /** Os cartões que se montam de novo a cada mudança: onde rodar, proteções, recursos, Spec Kit e execuções. */
    private val dynamic = JPanel(VerticalLayout(JBUI.scale(12), VerticalLayout.FILL)).apply { isOpaque = false }
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

    init {
        val content = JPanel(VerticalLayout(JBUI.scale(12), VerticalLayout.FILL)).apply {
            border = JBUI.Borders.empty(10, 14, 16, 14)
            add(TitledSeparator("A IA · comum aos dois sistemas"))
            add(autoCard)
            add(cliCard)
            add(dynamic)
        }
        setContent(JPanel(BorderLayout()).apply {
            add(header, BorderLayout.NORTH)
            add(ScrollPaneFactory.createScrollPane(FitWidthPanel(content), true), BorderLayout.CENTER)
        })
        toolbar = ActionManager.getInstance().createActionToolbar(
            "IronHawk.ArsenalToolbar",
            DefaultActionGroup(refreshAction {
                loadDefenseRuns()
                service.refresh(0)
                SpecKitSetup.check(force = true)
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
        app.messageBus.connect(this).subscribe(IronHawkSystem.TOPIC, IronHawkSystem.Listener { app.invokeLater({ refreshCards() }, project.disposed) })
        app.messageBus.connect(this).subscribe(SpecKitSetup.Listener.TOPIC, SpecKitSetup.Listener { app.invokeLater({ refreshCards() }, project.disposed) })
        project.messageBus.connect(this).subscribe(SpecKitService.CHANGED, SpecKitService.Listener { refreshCards() })
        project.messageBus.connect(this).subscribe(AgentRunner.RUNS, AgentRunner.Listener { app.invokeLater({ loadDefenseRuns() }, project.disposed) })
        project.messageBus.connect(this).subscribe(MissionRunner.RUNS, MissionRunner.Listener { app.invokeLater({ refreshCards() }, project.disposed) })
        loadFields()
        loadDistros()
        loadDefenseRuns()
        refreshCards()
        if (!AgentSettings.ready && !AgentSettings.autoSetupTried && AgentSettings.untouched) app.invokeLater({ autoSetup() }, project.disposed)
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
        ApplicationManager.getApplication().executeOnPooledThread {
            val key = AgentSettings.cursorApiKey.orEmpty()
            ApplicationManager.getApplication().invokeLater({ cursorKey.text = key }, project.disposed)
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
        clearKey.isVisible = AgentSettings.hasCursorApiKey
        hangNotice.isVisible = chosen == AgentProvider.ANTIGRAVITY
        providerNotice.isVisible = chosen == AgentProvider.ANTIGRAVITY
        providerNotice.text = "O Antigravity não está no devcontainer: com ele, a IA só roda no terminal da IDE." +
            if (IronHawkSystem.ATTACK.enabled) " Ele também não lança as missões do Ataque, porque o Spec Kit não tem integração para ele." else ""
        hostRow.isVisible = AgentLaunch.wslSupported
        val inWsl = host.selectedItem == AgentHost.WSL && AgentLaunch.wslSupported
        distroRow.isVisible = inWsl
        executable.isEnabled = true
        val configured = executable.text.trim().ifEmpty { null }
        if (!inWsl) {
            showFound(AgentSettings.resolveExecutable(chosen, configured)?.toString(), chosen)
            return
        }
        val name = (distro.selectedItem as? String)?.trim()?.ifEmpty { null }
        when {
            name == null -> showFound(null, chosen, "Escolha a distribuição do WSL.")
            configured != null -> showFound("WSL $name: $configured", chosen)
            else -> {
                val key = "${chosen.name}|$name"
                if (key in wslFound) return showFound(wslFound[key]?.let { "WSL $name: $it" }, chosen)
                found.text = "Procurando o ${chosen.title} CLI no WSL $name…"
                found.foreground = UIUtil.getContextHelpForeground()
                ApplicationManager.getApplication().executeOnPooledThread {
                    val path = AgentLaunch.findInWsl(chosen, name)
                    ApplicationManager.getApplication().invokeLater({
                        wslFound[key] = path
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
        val key = String(cursorKey.password)
        testStatus = Status(if (test) "Testando o ${AgentSettings.provider.title}…" else "Salvo.", if (test) null else true)
        refreshCards()
        ApplicationManager.getApplication().executeOnPooledThread {
            if (key.isNotBlank()) AgentSettings.cursorApiKey = key
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
                refreshCards()
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

    /** O cabeçalho, os botões do CLI e os cartões de baixo, com o estado de agora. */
    private fun refreshCards() {
        updateHeader()
        val busy = AgentAutoSetup.running || AgentVerifier.testing
        autoButton.isEnabled = !busy
        autoButton.text = if (AgentAutoSetup.running) "Configurando…" else "Configurar Automaticamente"
        saveTest.isEnabled = !busy
        saveTest.text = if (AgentVerifier.testing) "Testando…" else "Salvar e Testar"
        saveOnly.isEnabled = !busy
        val terminal = AgentTerminal.available() != null
        terminalTest.isEnabled = terminal && !AgentVerifier.testingTerminal && AgentSettings.launch() != null
        terminalTest.text = if (AgentVerifier.testingTerminal) "Esperando o Terminal…" else "Testar no Terminal"
        terminalTest.toolTipText = if (terminal) "Abre o terminal da IDE e roda o CLI com a configuração salva" else "O plugin de terminal da IDE está desligado"
        status(testLine, testStatus ?: AgentSettings.verifiedAt?.takeIf { AgentSettings.ready }?.let { Status("Testada em $it.", true) })
        status(terminalLine, terminalStatus ?: AgentSettings.terminalVerifiedAt?.let { Status("Terminal testado em $it.", true) })
        cliUses.text = "O agente que executa " + listOfNotNull(
            "as ordens de interceptação e as revisões da Defesa".takeIf { IronHawkSystem.DEFENSE.enabled },
            "as missões do Spec Kit do Ataque".takeIf { IronHawkSystem.ATTACK.enabled },
        ).joinToString(" e ") + "."
        updateProviderFields()

        dynamic.removeAll()
        dynamic.add(TitledSeparator("Execução e proteções"))
        dynamic.add(runCard())
        dynamic.add(containerCard())
        if (IronHawkSystem.DEFENSE.enabled) dynamic.add(containmentCard())
        if (IronHawkSystem.ATTACK.enabled) dynamic.add(guardCard())
        dynamic.add(TitledSeparator("Recursos da IA"))
        dynamic.add(capsCard())
        if (IronHawkSystem.ATTACK.enabled) dynamic.add(specKitCard())
        dynamic.add(TitledSeparator("Execuções recentes"))
        dynamic.add(small("O pedido, os passos, os arquivos alterados e a resposta de cada execução: as da Defesa em ironhawk/iron-dome/ia/ e as missões do Ataque em ironhawk/tomahawk/execucoes/. Duplo clique abre o registro."))
        runsModel.clear()
        runs().forEach(runsModel::addElement)
        dynamic.add(ScrollPaneFactory.createScrollPane(runsList).apply { preferredSize = JBUI.size(120, 220) })
        dynamic.revalidate()
        dynamic.repaint()
    }

    private fun updateHeader() {
        val launch = AgentSettings.launch()
        val chosen = AgentSettings.provider
        val working = agent.running
        val subtitle = if (launch == null) chosen.installHint
        else "${chosen.title} CLI${launch.where} · ${AgentSettings.model?.let { "modelo $it" } ?: "modelo padrão"}" +
            AgentSettings.verifiedAt?.let { " · testada em $it" }.orEmpty()
        when {
            AgentAutoSetup.running -> header.update("Configurando a IA…", Tone.YELLOW.accent, autoLine.text)
            AgentVerifier.testing -> header.update("Testando a integração…", Tone.YELLOW.accent, subtitle)
            launch == null -> header.update("IA não configurada", Tone.GRAY.accent, subtitle,
                warning = if (AgentSettings.host == AgentHost.WSL) "Escolha a distribuição do WSL." else null)
            !AgentSettings.ready -> header.update("Teste a integração", Tone.YELLOW.accent, subtitle,
                warning = "Os botões de IA ficam desligados até Salvar e Testar passar.")
            working != null -> header.update("IA trabalhando: ${working.title}", Tone.GREEN.accent, subtitle)
            missions.progress != null -> header.update("Missão em voo", Tone.GREEN.accent, subtitle)
            else -> header.update("IA pronta para os dois sistemas", Tone.GREEN.accent, subtitle)
        }
    }

    /** Onde rodar a IA, uma escolha só para os dois sistemas, e o que cada um faz com ela. */
    private fun runCard(): JComponent = card {
        val defense = IronHawkSystem.DEFENSE.enabled
        val attack = IronHawkSystem.ATTACK.enabled
        add(subtitle("Onde rodar a IA", AllIcons.Actions.Execute))
        add(small(if (defense && attack) "Uma escolha só para a Defesa e o Ataque." else "Quando um botão chama a IA."))
        add(labeled("Rodar", ComboBox(AgentRunMode.entries.toTypedArray()).apply {
            renderer = SimpleListCellRenderer.create("") { it.label }
            selectedItem = AgentSettings.runMode
            addActionListener { (selectedItem as? AgentRunMode)?.let { if (it != AgentSettings.runMode) AgentSettings.runMode = it } }
        }))
        if (defense) add(tagged(IronHawkSystem.DEFENSE, RUN_MODE_HINT))
        if (attack) add(tagged(IronHawkSystem.ATTACK, ATTACK_RUN_MODE_HINT))
        if (defense) {
            add(NoticeBox(AllIcons.General.Information, Tone.BLUE).apply {
                text = "Defesa na aba: só no devcontainer do projeto. Nas tarefas que alteram arquivos, como Disparar com IA, não há janela para aprovar " +
                    "cada passo: o Cursor roda com --force (edita e roda comandos sem perguntar), o Claude Code com --permission-mode acceptEdits " +
                    "(edita sem perguntar; comandos de terminal só os liberados nas configurações dele ou com --allowedTools nos argumentos extras) e o " +
                    "Antigravity com --dangerously-skip-permissions. " +
                    (if (AgentSettings.confirmWrites) "Por isso o plugin pergunta antes de cada uma. " else "Você desligou a pergunta antes de cada uma. ") +
                    "No terminal, a IA pede aprovação a cada passo. Nas de leitura, como revisões e explicações, nenhum deles recebe permissão para escrever."
            })
            if (!AgentSettings.confirmWrites) add(ActionLink("Voltar a perguntar antes de alterar arquivos") { AgentSettings.confirmWrites = true })
        }
        if (attack) {
            add(NoticeBox(AllIcons.General.Warning, Tone.YELLOW).apply {
                text = "Missões do Ataque na aba: só no devcontainer do projeto, a única forma de a IA rodar sem perguntar. Lá o Cursor roda com " +
                    "--force --trust e o Claude Code com --permission-mode bypassPermissions, mas o container só enxerga a pasta do projeto.\n" +
                    "No terminal da IDE: modo agente sem permissão total, nesta máquina: a IA edita o projeto e pede a sua aprovação antes de rodar comandos.\n" +
                    "Textos (commit, PR, respostas do Rádio): só leitura, para um comentário malicioso num PR não dar à IA permissão para alterar arquivos."
            })
        }
        if (LaunchTerminal.available() == null) add(small("O plugin de terminal da IDE está desligado: a IA só roda na aba.").apply { foreground = JBColor.RED })
    }

    private fun containerCard(): JComponent = card {
        val root = service.root()
        val cli = DevContainer.cli()
        val config = root?.let(DevContainer::config)?.takeIf { Files.isRegularFile(it) }
        val problems = config?.let { file -> DevContainer.audit(runCatching { Files.readString(file) }.getOrDefault("")) }.orEmpty()
        add(subtitle("Devcontainer", AllIcons.Nodes.Deploy))
        add(small("Na aba, a IA dos dois sistemas roda num container que só enxerga a pasta do projeto, a única forma de ela rodar sem perguntar. " +
            "Sem ele, a IA só roda no terminal da IDE, com você aprovando os passos."))
        add(pills(
            if (cli != null) Pill("devcontainer CLI ✓", Tone.GREEN) else Pill("devcontainer CLI não instalado", Tone.RED),
            when {
                config == null -> Pill("projeto sem devcontainer", Tone.YELLOW)
                problems.isEmpty() -> Pill("isolamento conferido ✓", Tone.GREEN)
                else -> Pill("${problems.size} problema(s) de isolamento", Tone.RED)
            },
        ))
        if (cli == null) add(NoticeBox(AllIcons.General.Warning, Tone.YELLOW).apply { text = DevContainer.CLI_HINT })
        if (problems.isNotEmpty()) {
            add(NoticeBox(AllIcons.General.Error, Tone.RED).apply {
                text = "O ${DevContainer.CONFIG} não isola a máquina:\n" + problems.joinToString("\n") { "• $it" }
            })
        }
        caps(
            listOf(
                "Só o projeto" to "o container não vê as suas pastas, as suas credenciais nem o Docker da máquina.",
                "Git protegido" to "o .git/config e os hooks ficam só leitura lá dentro, porque o Git da sua máquina roda o que estiver neles.",
                "Conferido a cada missão" to "o Tomahawk recusa o container privilegiado, com rede ou processos da máquina, ou com pastas da máquina montadas.",
            ).map { Triple(true, it.first, it.second) },
        )
        if (AgentSettings.provider == AgentProvider.CURSOR) {
            add(small("No container, o Cursor CLI entra pela chave de API do cartão CLI de IA.").apply {
                if (!AgentSettings.hasCursorApiKey) foreground = JBColor.RED
            })
        } else {
            add(small("No container, o Claude Code usa o login feito lá dentro (guardado num volume) ou o ANTHROPIC_API_KEY desta máquina. Para entrar uma vez: " +
                "devcontainer exec --workspace-folder . --config ${DevContainer.CONFIG} claude"))
        }
        if (config == null) {
            add(row(primary("Criar o Devcontainer") { createContainer() }.apply {
                isEnabled = root != null
                toolTipText = "Grava ${DevContainer.CONFIG} com os CLIs de IA e o isolamento"
            }))
        } else {
            add(row(
                JButton("Abrir devcontainer.json", AllIcons.Actions.EditSource).apply { addActionListener { root?.let { missions.open(DevContainer.config(it)) } } },
                JButton(if (containerBusy) "Subindo…" else "Subir e Conferir", AllIcons.Actions.Execute).apply {
                    isEnabled = cli != null && !containerBusy && missions.progress == null
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
                Status("Criado em ${DevContainer.CONFIG}. Acrescente as ferramentas do projeto em features, se a IA precisar delas.", true)
            },
            onFailure = { Status("Não deu para criar: ${it.message ?: it.javaClass.simpleName}", false) },
        )
        refreshCards()
    }

    private fun containerUp(root: Path) {
        containerBusy = true
        containerStatus = Status("Subindo o devcontainer (a primeira vez demora)…", null)
        refreshCards()
        var shown = 0L
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = runCatching {
                DevContainer.up(root, { line ->
                    val now = System.currentTimeMillis()
                    if (now - shown > 1_000) {
                        shown = now
                        ApplicationManager.getApplication().invokeLater({
                            if (containerBusy) containerStatus = Status("Subindo: $line", null)
                            refreshCards()
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

    private fun containmentCard(): JComponent = card {
        add(subtitle("Contenção da IA", AllIcons.General.Warning, IronHawkSystem.DEFENSE))
        add(small(
            "Sempre ligada: antes de cada tarefa que altera arquivos, uma foto do projeto no git, sem mexer no seu índice nem na branch (sem git, no " +
                "Local History), e o aviso do fim traz Desfazer Alterações da IA; na aba, a IA roda no devcontainer, sem git push e só com a credencial " +
                "do CLI; o Claude Code recusa comandos destrutivos e mexer no .git; e a IA é parada na hora se tentar apagar pastas, descartar alterações " +
                "do git, reescrever o histórico, rodar como administrador, formatar disco, baixar e executar scripts, ou escrever fora do projeto ou no .git.",
        ))
    }

    private fun guardCard(): JComponent = card {
        val on = TomahawkSettings.guardrails
        val cursor = AgentSettings.provider == AgentProvider.CURSOR
        add(subtitle("Proteções das missões", AllIcons.Actions.Lightning, IronHawkSystem.ATTACK))
        add(small("Para a IA rodando sozinha nas missões. Dá para desligar."))
        add(JBCheckBox("Proteções ligadas", on).apply {
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
                text = "Com as proteções desligadas, a IA roda sem as regras do Tomahawk (no devcontainer e no terminal) e as missões não têm ponto de restauração."
            })
        }
        caps(
            listOf(
                "Barrado antes de rodar" to "apagar pastas inteiras, formatar discos, desligar a máquina, pedir administrador, mudar o sistema, instalar " +
                    "programas, rodar script baixado da internet, git push/reset --hard/clean. " +
                    if (cursor) "Fica no .cursor/cli.json do projeto." else "Vai no --settings do Claude Code, que também não escreve fora do projeto.",
                "Vigia" to "o Tomahawk lê cada passo da IA e aborta a missão num comando perigoso, numa escrita fora do projeto ou dentro do .git, ou numa " +
                    "leitura de credenciais (~/.ssh, ~/.aws…).",
                "Ponto de restauração" to "antes de cada missão, o projeto inteiro vai para o Git (em refs/tomahawk/, sem mexer na sua branch), e dá para desfazer.",
                "Sem segredos" to "tokens e chaves do ambiente (GitHub, nuvem, npm) não chegam ao processo da IA.",
            ).map { Triple(on, it.first, it.second) },
        )
        add(row(JButton("Desfazer a Última Missão", AllIcons.Actions.Rollback).apply {
            isEnabled = missions.progress == null
            toolTipText = "Volta o projeto para o ponto de restauração da última missão, depois de mostrar o que mudou"
            addActionListener { missions.undo() }
        }))
    }

    /** O que a IA libera nos dois sistemas, numa lista só, com a etiqueta de cada um. */
    private fun capsCard(): JComponent = card {
        val ready = AgentSettings.ready
        add(subtitle("O que a IA libera", AllIcons.Actions.Lightning))
        if (IronHawkSystem.DEFENSE.enabled) {
            add(pills(systemPill(IronHawkSystem.DEFENSE)))
            caps(DEFENSE_CAPABILITIES.map { Triple(ready, it.first, it.second) })
            if (!ready) add(small("Desligadas até a integração ser testada com a configuração atual."))
        }
        if (IronHawkSystem.ATTACK.enabled) {
            val attack = TomahawkSettings.ready
            val terminal = LaunchTerminal.available() != null
            add(pills(systemPill(IronHawkSystem.ATTACK)))
            caps(
                listOf(
                    Triple(attack, "Lançador", "as nove etapas do SDD na aba, monitoradas, ou no terminal"),
                    Triple(attack && terminal, "Terminal", "conversar com a IA nas etapas que fazem perguntas"),
                    Triple(attack, "Avaliação", "Simulação de Rota (analyze) e Avaliação de Danos (converge) num clique"),
                    Triple(attack, "Extração e Rádio", "a mensagem do commit, o texto do PR e as respostas aos comentários"),
                    Triple(true, "Telemetria", "cada passo da IA ao vivo e o registro de cada missão"),
                ),
            )
            if (!attack) {
                add(small(if (!TomahawkSettings.supported) "Desligadas: o CLI escolhido não lança missões do Spec Kit." else "Desligadas até a integração ser testada com a configuração atual."))
            }
        }
    }

    private fun specKitCard(): JComponent = card {
        val s = service.snapshot
        val specify = TomahawkSettings.resolveSpecify()
        val info = SpecKitSetup.infoFor(specify)
        val installing = SpecKitSetup.job != null
        val integration = TomahawkSettings.provider.integration
        add(subtitle("Spec Kit", AllIcons.Nodes.PpLib, IronHawkSystem.ATTACK))
        add(small("O CLI specify nesta máquina e os comandos instalados neste projeto."))
        add(pills(*listOfNotNull(
            when {
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
                text = "O projeto foi inicializado para $key, mas a IA configurada é o ${TomahawkSettings.provider.title}, e os comandos dele podem não estar instalados " +
                    "(specify integration install $integration)."
            })
            add(row(JButton("Instalar a Integração $integration").apply {
                isEnabled = specify != null && !installing && info?.broken != true
                addActionListener { SpecKitSetup.installIntegration(project) }
            }))
        }
        if (!s.initialized) add(small("Este projeto ainda não tem o Spec Kit (a pasta .specify/)."))
        specKit.withInit = !s.initialized
        specKit.update()
        add(specKit)
        val buttons = listOfNotNull(
            JButton("Verificar de Novo", AllIcons.Actions.Refresh).apply {
                toolTipText = "Procura o specify de novo, relê a versão e o projeto"
                addActionListener {
                    SpecKitSetup.recheck()
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

    private fun primary(text: String, action: () -> Unit) = JButton(text).apply {
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

    internal companion object {
        const val MAX_RUNS = 30
        const val RUN_MODE_HINT = "No terminal, a IA roda à vista numa aba do terminal da IDE, onde dá para acompanhar e conversar com ela " +
            "(o Cursor abre interativo, com o login do agent login). Ela grava a resposta final em ironhawk/iron-dome/ia/respostas/, " +
            "e o plugin segue dali, como na aba. Testar no Terminal confere esse caminho com a configuração salva, no Windows ou no WSL."
        const val ATTACK_RUN_MODE_HINT = "Nas missões do Ataque, na aba, a IA roda sem perguntar no devcontainer do projeto e a Telemetria " +
            "acompanha cada passo; no terminal da IDE, você conversa com a IA e aprova os comandos dela. Cada etapa do Lançador pode " +
            "trocar na hora de lançar."

        val DEFENSE_CAPABILITIES = listOf(
            "Radar e barra do editor" to "Disparar Todos com IA, ou Disparar Selecionados e Disparar no cartão do alvo: a IA executa a ordem de interceptação e marca os alvos atendidos",
            "Patrulha" to "Revisar com IA (no modo revisão): comentários da IA como rascunhos do PR",
            "Escudo" to "Explicar Falha com IA e, na resposta, Corrigir com IA",
            "Raio de Impacto" to "Analisar Impacto com IA: as classes que os comentários e as alterações afetam",
            "Caixa-Preta" to "Analisar com IA, no bug gerado e em Ver Logs de Erro",
        )
    }
}
