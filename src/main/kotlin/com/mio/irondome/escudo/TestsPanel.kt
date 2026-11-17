package com.mio.irondome.escudo

import com.intellij.execution.ExecutionListener
import com.intellij.execution.ExecutionManager
import com.intellij.execution.ProgramRunnerUtil
import com.intellij.execution.RunManager
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.junit.JUnitConfiguration
import com.intellij.execution.junit.JUnitConfigurationType
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.runners.ExecutionEnvironmentBuilder
import com.intellij.execution.runners.ProgramRunner
import com.intellij.execution.testframework.TestSearchScope
import com.intellij.execution.testframework.sm.runner.SMTRunnerEventsAdapter
import com.intellij.execution.testframework.sm.runner.SMTRunnerEventsListener
import com.intellij.execution.testframework.sm.runner.SMTestProxy
import com.intellij.execution.testframework.sm.runner.ui.SMTRunnerConsoleView
import com.intellij.execution.ui.RunContentDescriptor
import com.intellij.icons.AllIcons
import com.intellij.ide.CommonActionsManager
import com.intellij.ide.DefaultTreeExpander
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.DumbAwareToggleAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.util.text.StringUtil
import com.intellij.pom.Navigatable
import com.intellij.ui.ColorUtil
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.PopupHandler
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.panels.VerticalLayout
import com.intellij.util.Alarm
import com.intellij.util.ui.HTMLEditorKitBuilder
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.mio.irondome.base.IronDomeIcons
import com.mio.irondome.base.MechanismTree
import com.mio.irondome.base.RichTreeCellRenderer
import com.mio.irondome.base.Segment
import com.mio.irondome.base.Stat
import com.mio.irondome.base.SummaryHeader
import com.mio.irondome.base.Tone
import com.mio.irondome.base.moreActions
import com.mio.irondome.base.plural
import com.mio.irondome.base.rootPath
import com.mio.irondome.base.wrapping
import com.mio.irondome.interceptor.AgentProgressPanel
import com.mio.irondome.interceptor.AgentRunner
import com.mio.irondome.interceptor.FireWithAiAction
import java.awt.BorderLayout
import java.awt.Color
import java.awt.event.HierarchyEvent
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.nio.file.Path
import javax.swing.Icon
import javax.swing.JEditorPane
import javax.swing.JPanel
import javax.swing.SwingUtilities
import javax.swing.event.HyperlinkEvent
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath

/**
 * Aba Escudo: executa todos os testes JUnit do projeto pela configuração "All in project" da IDE e mostra o resultado
 * organizado pela `@Tag` dos testes ou pelos critérios de aceite do catálogo, junto com os cenários do último XML do
 * Cucumber, se houver. A execução também aparece na janela Run, com o console completo.
 */
class TestsPanel(private val project: Project) : SimpleToolWindowPanel(true, true), Disposable {

    private val model = DefaultTreeModel(DefaultMutableTreeNode())
    private val tree = MechanismTree(model, IronDomeIcons.escudo, Tone.GREEN).apply {
        cellRenderer = Renderer()
        emptyText.text = IDLE
    }
    private val header = SummaryHeader(IronDomeIcons.escudo, Tone.GREEN)
    private val details = JEditorPane().apply {
        editorKit = HTMLEditorKitBuilder().withWordWrapViewFactory().build()
        isEditable = false
        border = JBUI.Borders.empty(10, 14)
    }
    private val displayNames = JUnitDisplayNames(project)
    private val refreshAlarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, this)
    private val loadAlarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, this)

    @Volatile
    private var environment: ExecutionEnvironment? = null
    @Volatile
    private var testsRoot: SMTestProxy.SMRootTestProxy? = null
    @Volatile
    private var process: ProcessHandler? = null
    @Volatile
    private var notStarted = false
    /** As classes de teste da execução do JUnit pela aba: é o que decide o veredito do cabeçalho. */
    private var junit: List<TestNode> = emptyList()
    /** As classes do JUnit e as funcionalidades do Cucumber de fora: o que a árvore e os critérios mostram. */
    private var results: List<TestNode> = emptyList()
    private var onlyFailures = false
    /** O catálogo de critérios e as tags que o código cita, lidos em segundo plano. */
    @Volatile
    private var catalog: CriteriaCatalog = CriteriaCatalog.EMPTY
    @Volatile
    private var sourceReferences: Set<String> = emptySet()
    private var report: CriteriaReport? = null
    /** O último resultado do Cucumber fora do runner do JUnit, lido do XML dele. */
    @Volatile
    private var cucumber: CucumberRun? = null
    /** A leitura mais nova pedida, só na EDT: uma leitura que termina depois de outra mais nova é descartada. */
    private var loadGeneration = 0
    /** Nós com falha já abertos sozinhos uma vez: se o usuário os recolher, ficam recolhidos. */
    private val autoExpanded = mutableSetOf<String>()

    private val running: Boolean
        get() = environment != null && !notStarted && process?.isProcessTerminated != true

    init {
        val expander = DefaultTreeExpander(tree)
        val openAction = object : DumbAwareAction("Ir para o Código", "Abre a classe ou o método de teste selecionado", AllIcons.Actions.EditSource) {
            override fun getActionUpdateThread() = ActionUpdateThread.EDT
            override fun update(e: AnActionEvent) {
                e.presentation.isEnabled = selectedItem()?.locationUrl != null
            }
            override fun actionPerformed(e: AnActionEvent) = open(selectedItem())
        }
        val explainAction = object : DumbAwareAction("Explicar Falha com IA", EXPLAIN_DESCRIPTION, IronDomeIcons.ia) {
            override fun getActionUpdateThread() = ActionUpdateThread.EDT
            override fun update(e: AnActionEvent) {
                val runner = AgentRunner.getInstance(project)
                e.presentation.isEnabled = canExplain(selectedItem())
                e.presentation.description = if (runner.available()) EXPLAIN_DESCRIPTION else FireWithAiAction.NOT_READY
            }
            override fun actionPerformed(e: AnActionEvent) = explain(selectedItem())
        }
        val runAction = object : DumbAwareAction("Executar Todos os Testes", RUN_DESCRIPTION, AllIcons.Actions.Execute) {
            override fun getActionUpdateThread() = ActionUpdateThread.EDT
            override fun update(e: AnActionEvent) {
                // Um botão só: durante a execução, ele para os testes.
                if (running) {
                    e.presentation.text = "Parar os Testes"
                    e.presentation.description = "Interrompe a execução dos testes"
                    e.presentation.icon = AllIcons.Actions.Suspend
                    e.presentation.isEnabled = process != null
                } else {
                    e.presentation.text = "Executar Todos os Testes"
                    e.presentation.description = RUN_DESCRIPTION
                    e.presentation.icon = AllIcons.Actions.Execute
                    e.presentation.isEnabled = true
                }
            }
            override fun actionPerformed(e: AnActionEvent) {
                if (running) process?.destroyProcess() else runAll()
            }
        }
        // Sem catálogo, marca no Radar as classes sem @DisplayName; com catálogo, a IA liga os testes aos critérios.
        val standardizeAction = object : DumbAwareAction("Padronizar Testes", STANDARDIZE_DESCRIPTION, AllIcons.Actions.QuickfixBulb) {
            override fun getActionUpdateThread() = ActionUpdateThread.EDT
            override fun update(e: AnActionEvent) {
                if (catalog.isEmpty) {
                    e.presentation.description = "Marca no Radar as classes sem @DisplayName para a IA padronizar ao disparar"
                    e.presentation.isEnabled = outsideConvention().isNotEmpty() && !running
                } else {
                    val runner = AgentRunner.getInstance(project)
                    e.presentation.description = if (runner.available()) STANDARDIZE_DESCRIPTION else FireWithAiAction.NOT_READY
                    e.presentation.isEnabled = runner.running == null && runner.available() && !running
                }
            }
            override fun actionPerformed(e: AnActionEvent) {
                if (catalog.isEmpty) ConventionTargets.mark(project, outsideConvention()) else CriteriaActions.align(project, catalog)
            }
        }
        val generateAction = object : DumbAwareAction(
            "Gerar Critérios com IA",
            "A IA converte um texto, um spec.md ou uma issue no catálogo de critérios",
            IronDomeIcons.ia,
        ) {
            override fun getActionUpdateThread() = ActionUpdateThread.EDT
            override fun update(e: AnActionEvent) {
                val runner = AgentRunner.getInstance(project)
                e.presentation.isEnabled = runner.running == null && runner.available()
            }
            override fun actionPerformed(e: AnActionEvent) {
                val source = Messages.showMultilineInputDialog(
                    project,
                    "Cole a fonte dos critérios: um texto, o caminho de um arquivo do projeto (como specs/001/spec.md) " +
                        "ou o identificador de uma issue (Linear, Jira).",
                    "Gerar Critérios com IA",
                    "",
                    IronDomeIcons.escudo,
                    null,
                )?.takeIf { it.isNotBlank() } ?: return
                CriteriaActions.generate(project, source, catalog.criteria.map { it.id })
            }
        }
        val reloadAction = object : DumbAwareAction(
            "Recarregar Critérios e Cucumber",
            "Relê o catálogo, as tags do código e o XML do Cucumber",
            AllIcons.Actions.Refresh,
        ) {
            override fun getActionUpdateThread() = ActionUpdateThread.EDT
            override fun actionPerformed(e: AnActionEvent) = loadCriteria()
        }
        val onlyFailuresAction = object : DumbAwareToggleAction("Mostrar Só as Falhas", "Esconde os testes que passaram", AllIcons.General.Filter) {
            override fun getActionUpdateThread() = ActionUpdateThread.EDT
            override fun isSelected(e: AnActionEvent) = onlyFailures
            override fun setSelected(e: AnActionEvent, state: Boolean) {
                onlyFailures = state
                rebuildTree()
            }
        }
        toolbar = ActionManager.getInstance().createActionToolbar(
            "IronDome.TestsToolbar",
            DefaultActionGroup(
                runAction,
                standardizeAction,
                Separator.getInstance(),
                onlyFailuresAction,
                organizeActions(),
                moreActions(
                    generateAction,
                    reloadAction,
                    null,
                    CommonActionsManager.getInstance().createExpandAllAction(expander, tree),
                    CommonActionsManager.getInstance().createCollapseAllAction(expander, tree),
                ),
            ),
            true,
        ).apply { targetComponent = tree }.wrapping().component

        val splitter = OnePixelSplitter(true, "IronDome.TestsSplitter", 0.65f).apply {
            firstComponent = ScrollPaneFactory.createScrollPane(tree, true)
            secondComponent = ScrollPaneFactory.createScrollPane(details, true)
        }
        setContent(JPanel(BorderLayout()).apply {
            add(JPanel(VerticalLayout(0)).apply {
                add(header)
                add(AgentProgressPanel(project, TestsTab.TITLE, this@TestsPanel))
            }, BorderLayout.NORTH)
            add(splitter, BorderLayout.CENTER)
        })
        installNavigation(openAction, explainAction)
        tree.addTreeSelectionListener { updateDetails() }
        details.addHyperlinkListener { event ->
            if (event.eventType == HyperlinkEvent.EventType.ACTIVATED && event.description == EXPLAIN_LINK) explain(selectedItem())
        }

        val connection = project.messageBus.connect(this)
        connection.subscribe(SMTRunnerEventsListener.TEST_STATUS, object : SMTRunnerEventsAdapter() {
            override fun onTestingStarted(testsRoot: SMTestProxy.SMRootTestProxy) {
                if (this@TestsPanel.testsRoot == null && running) this@TestsPanel.testsRoot = testsRoot
                onEvent(testsRoot)
            }
            override fun onTestingFinished(testsRoot: SMTestProxy.SMRootTestProxy) = onEvent(testsRoot)
            override fun onSuiteTreeNodeAdded(testProxy: SMTestProxy) = onEvent(testProxy)
            override fun onSuiteStarted(suite: SMTestProxy) = onEvent(suite)
            override fun onSuiteFinished(suite: SMTestProxy) = onEvent(suite)
            override fun onTestStarted(test: SMTestProxy) = onEvent(test)
            override fun onTestFinished(test: SMTestProxy) = onEvent(test)
            override fun onTestFailed(test: SMTestProxy) = onEvent(test)
            override fun onTestIgnored(test: SMTestProxy) = onEvent(test)
        })
        connection.subscribe(ExecutionManager.EXECUTION_TOPIC, object : ExecutionListener {
            override fun processNotStarted(executorId: String, env: ExecutionEnvironment) {
                if (env !== environment) return
                notStarted = true
                scheduleRefresh()
            }
            override fun processStarted(executorId: String, env: ExecutionEnvironment, handler: ProcessHandler) {
                if (env !== environment) return
                process = handler
                scheduleRefresh()
            }
            override fun processTerminated(executorId: String, env: ExecutionEnvironment, handler: ProcessHandler, exitCode: Int) {
                if (env === environment) scheduleRefresh()
            }
        })
        refresh()
        // O XML do Cucumber muda fora da IDE (mvn verify): relê sempre que a aba volta a aparecer. Na abertura, o pedido
        // daqui e o da aba aparecendo caem no mesmo intervalo e viram uma leitura só.
        scheduleLoad()
        addHierarchyListener { event ->
            if (event.changeFlags and HierarchyEvent.SHOWING_CHANGED.toLong() != 0L && isShowing) scheduleLoad()
        }
    }

    override fun dispose() = Unit

    private fun runAll() {
        val configuration = JUnitConfiguration(RUN_NAME, project).apply {
            persistentData.TEST_OBJECT = JUnitConfiguration.TEST_PACKAGE
            persistentData.PACKAGE_NAME = ""
            persistentData.scope = TestSearchScope.WHOLE_PROJECT
        }
        val settings = RunManager.getInstance(project)
            .createConfiguration(configuration, JUnitConfigurationType.getInstance().factory)
            .apply { isActivateToolWindowBeforeRun = false }
        val builder = ExecutionEnvironmentBuilder.createOrNull(DefaultRunExecutor.getRunExecutorInstance(), settings)
        if (builder == null) {
            header.update("Não foi possível executar", Tone.RED.accent, "A IDE não encontrou como executar testes JUnit neste projeto.")
            return
        }
        val env = builder.build(ProgramRunner.Callback { attach(it) })
        environment = env
        testsRoot = null
        process = null
        notStarted = false
        displayNames.clear()
        autoExpanded.clear()
        refresh()
        loadCriteria()
        ProgramRunnerUtil.executeConfiguration(env, false, true)
    }

    private fun attach(descriptor: RunContentDescriptor?) {
        (descriptor?.executionConsole as? SMTRunnerConsoleView)?.resultsViewer?.testsRootNode?.let { testsRoot = it }
        descriptor?.processHandler?.let { process = it }
        scheduleRefresh()
    }

    private fun onEvent(proxy: SMTestProxy) {
        val root = testsRoot ?: return
        if ((proxy as? SMTestProxy.SMRootTestProxy ?: proxy.root) === root) scheduleRefresh()
    }

    /** Os eventos chegam a cada teste: a árvore é redesenhada no máximo a cada [REFRESH_DELAY_MS]. */
    private fun scheduleRefresh() {
        if (refreshAlarm.activeRequestCount > 0) return
        refreshAlarm.addRequest({ refresh() }, REFRESH_DELAY_MS)
    }

    private fun refresh() {
        junit = testsRoot?.let { TestResults.build(it, displayNames::of, displayNames::tagsOf) }.orEmpty()
        results = junit + cucumber?.features.orEmpty()
        report = catalog.takeUnless { it.isEmpty }?.let { Criteria.report(it, results.flatMap { node -> node.tests }, sourceReferences) }
        updateHeader()
        rebuildTree()
    }

    /** Pedidos seguidos de leitura, como a aba aparecendo várias vezes, viram um só depois de [LOAD_DELAY_MS]. */
    private fun scheduleLoad() {
        loadAlarm.cancelAllRequests()
        loadAlarm.addRequest({ loadCriteria() }, LOAD_DELAY_MS)
    }

    /** Lê o catálogo, as tags do código e o XML do Cucumber fora da EDT e refaz a tela quando termina. Na EDT. */
    private fun loadCriteria() {
        loadAlarm.cancelAllRequests()
        val generation = ++loadGeneration
        ApplicationManager.getApplication().executeOnPooledThread {
            val loaded = runCatching { CriteriaFiles.load(CriteriaFiles.roots(project)) }.getOrDefault(CriteriaCatalog.EMPTY)
            val references = if (loaded.isEmpty) emptySet() else runCatching { CriteriaFiles.sourceReferences(project) }.getOrDefault(emptySet())
            // Sem as raízes dos módulos, vale a pasta do projeto; um erro aqui não pode esconder os cenários em silêncio.
            val roots = runCatching { CriteriaFiles.roots(project) }
                .onFailure { LOG.warn("Escudo: não consegui listar as raízes do projeto", it) }
                .getOrNull().orEmpty()
                .ifEmpty { listOfNotNull(project.rootPath()).map { Path.of(it) } }
            val scenarios = runCatching { CucumberResults.load(roots) }
                .onFailure { LOG.warn("Escudo: não consegui ler o resultado do Cucumber", it) }
                .getOrNull()
            LOG.info("Escudo: Cucumber ${scenarios?.tests?.size ?: 0} cenários, raízes $roots")
            ApplicationManager.getApplication().invokeLater({
                // Uma leitura mais nova já foi pedida: ela é que vale, mesmo que termine depois desta.
                if (generation != loadGeneration) return@invokeLater
                catalog = loaded
                sourceReferences = references
                cucumber = scenarios
                refresh()
            }, ModalityState.any(), project.disposed)
        }
    }

    /** A organização escolhida; sem catálogo, só a por tag existe. */
    private val mode: EscudoView
        get() = EscudoView.of(PropertiesComponent.getInstance().getValue(VIEW_KEY), hasCatalog = !catalog.isEmpty)

    /** Um menu só para organizar a árvore: por tag ou por critério. */
    private fun organizeActions() = object : DefaultActionGroup("Organizar por", true) {
        init {
            templatePresentation.icon = AllIcons.Actions.GroupBy
        }
        override fun getActionUpdateThread() = ActionUpdateThread.EDT
        override fun update(e: AnActionEvent) {
            e.presentation.text = "Organizar por: ${mode.label}"
        }
    }.apply {
        EscudoView.entries.forEach { option ->
            add(object : DumbAwareToggleAction(option.label) {
                override fun getActionUpdateThread() = ActionUpdateThread.EDT
                override fun update(e: AnActionEvent) {
                    super.update(e)
                    val available = option == EscudoView.TAG || !catalog.isEmpty
                    e.presentation.isEnabled = available
                    e.presentation.description = if (available) null else "Precisa do catálogo de critérios em ${CriteriaActions.FOLDER}/"
                }
                override fun isSelected(e: AnActionEvent) = mode == option
                override fun setSelected(e: AnActionEvent, state: Boolean) {
                    PropertiesComponent.getInstance().setValue(VIEW_KEY, option.name)
                    rebuildTree()
                }
            })
        }
    }

    /** A árvore na organização escolhida. */
    private fun view(): List<TestNode> = when (mode) {
        EscudoView.CRITERIA -> report?.let { CriteriaView.build(it, results) }.orEmpty()
        EscudoView.TAG -> TestResults.byTag(results)
    }

    /** O veredito conta só a execução do JUnit pela aba; o Cucumber de fora aparece à parte, com a idade do XML. */
    private fun updateHeader() {
        val tests = junit.flatMap { it.tests }
        val classes = junit.flatMap { it.classes }
        val failed = tests.count { it.outcome == TestOutcome.FAILED }
        val criteria = report
        val scoreboard = criteria?.let(::scoreboardOf)
        val external = cucumber?.let(::cucumberNote)
        val context = listOfNotNull(
            plural(classes.size, "classe", "classes"),
            scoreboard,
            testsRoot?.duration?.let(TestResults::formatDuration),
            external,
        ).joinToString(" · ")
        val outside = classes.count { it.convention != Convention.OK }
        val warning = when {
            !catalog.isEmpty || catalog.errors.isNotEmpty() -> criteriaWarning(criteria)
            outside == 0 -> null
            else -> "${plural(outside, "classe de teste", "classes de teste")} sem @DisplayName: " +
                "Padronizar Testes as marca no Radar para a IA corrigir."
        }
        val stats = statsOf(tests, running)
        val bar = barOf(tests)
        when {
            environment == null -> header.update(
                "Escudo pronto",
                subtitle = listOfNotNull(scoreboard, external, IDLE).joinToString(" · "),
                warning = warning,
            )
            notStarted -> header.update(
                "Os testes não chegaram a rodar",
                Tone.RED.accent,
                "Confira os erros de compilação ou de configuração nas janelas Build e Run.",
            )
            running && tests.isEmpty() -> header.update("Preparando os testes…", subtitle = "Compilando o projeto e procurando os testes JUnit")
            running -> {
                val done = tests.count { it.outcome != TestOutcome.RUNNING && it.outcome != TestOutcome.NOT_RUN }
                header.update("Executando os testes…", Tone.BLUE.accent, "$done de ${tests.size} testes · $context", stats, bar, warning)
            }
            tests.isEmpty() -> header.update(
                "Nenhum teste JUnit executado",
                Tone.YELLOW.accent,
                listOfNotNull("Veja o console na janela Run.", external).joinToString(" · "),
                warning = warning,
            )
            failed > 0 -> header.update(
                "Escudo rompido",
                Tone.RED.accent,
                "${plural(failed, "teste falhou", "testes falharam")} de ${tests.size} · $context",
                stats, bar, warning,
            )
            tests.any { it.outcome == TestOutcome.INTERRUPTED } ->
                header.update("Execução interrompida", Tone.ORANGE.accent, "${tests.size} testes · $context", stats, bar, warning)
            else -> header.update(
                "Escudo íntegro",
                Tone.GREEN.accent,
                "${if (tests.size == 1) "O teste passou" else "Todos os ${tests.size} testes passaram"} · $context",
                stats, bar, warning,
            )
        }
    }

    /** O Cucumber de fora, com a idade do XML, como "Cucumber (XML há 5 min): 12 cenários, 1 falhando". */
    private fun cucumberNote(run: CucumberRun): String {
        val failing = run.tests.count { it.outcome == TestOutcome.FAILED }
        return "Cucumber (XML ${CucumberResults.age(run.writtenAt)}): ${plural(run.tests.size, "cenário", "cenários")}" +
            if (failing > 0) ", $failing falhando" else ""
    }

    /** Quantos critérios estão atendidos e o que pede atenção, como "18 de 24 critérios atendidos · 3 sem teste". */
    private fun scoreboardOf(report: CriteriaReport): String {
        val total = report.coverages.size
        return listOfNotNull(
            "${report.count(CriterionStatus.MET)} de $total ${if (total == 1) "critério atendido" else "critérios atendidos"}",
            report.count(CriterionStatus.FAILING).takeIf { it > 0 }?.let { "$it falhando" },
            report.count(CriterionStatus.PARTIAL).takeIf { it > 0 }?.let { if (it == 1) "1 parcial" else "$it parciais" },
            report.count(CriterionStatus.UNTESTED).takeIf { it > 0 }?.let { "$it sem teste" },
        ).joinToString(" · ")
    }

    /** Os erros do catálogo e as tags órfãs, que o contrato pede para corrigir, em poucas palavras. */
    private fun criteriaWarning(report: CriteriaReport?): String? {
        val parts = buildList {
            val errors = catalog.errors
            if (errors.isNotEmpty()) {
                add("${plural(errors.size, "erro", "erros")} no catálogo de critérios, como ${errors.first()}")
            }
            report?.orphans?.takeIf { it.isNotEmpty() }?.let { orphans ->
                val ids = orphans.take(3).joinToString(", ") { it.id } + if (orphans.size > 3) ", …" else ""
                add("${plural(orphans.size, "tag aponta", "tags apontam")} para critério que o catálogo não tem: $ids")
            }
        }
        return parts.takeIf { it.isNotEmpty() }?.joinToString(". ")
    }

    private fun rebuildTree() {
        val expanded = tree.getExpandedDescendants(TreePath(model.root))?.toList().orEmpty()
            .mapNotNull { itemOf(it.lastPathComponent)?.key }
            .toSet()
        val selected = selectedItem()?.key

        val root = DefaultMutableTreeNode()
        visible(view()).forEach { root.add(treeNode(it)) }
        model.setRoot(root)
        tree.emptyText.text = when {
            onlyFailures && results.isNotEmpty() -> "Nenhuma falha"
            environment == null -> IDLE
            running -> "Aguardando os testes…"
            else -> "Nenhum teste executado"
        }

        var selection: TreePath? = null
        for (node in root.preorderEnumeration().toList().drop(1).map { it as DefaultMutableTreeNode }) {
            val item = node.userObject as TestNode
            val path = TreePath(node.path)
            if (item.key == selected) selection = path
            if (item.kind == TestKind.TEST) continue
            val opensAlone = item.kind == TestKind.TAG || item.kind == TestKind.SUBJECT || item.outcome == TestOutcome.FAILED
            if (item.key in expanded || (opensAlone && autoExpanded.add(item.key))) tree.expandPath(path)
        }
        selection?.let { tree.selectionPath = it }
        updateDetails()
    }

    /** As classes fora da convenção da seleção; sem nenhuma nela, as de toda a execução. */
    private fun outsideConvention(): List<TestNode> {
        // Por tag, a mesma classe aparece em mais de um grupo.
        fun outside(nodes: List<TestNode>) =
            nodes.flatMap { it.classes }.filter { it.convention != Convention.OK }.distinctBy { it.locationUrl ?: it.key }
        return outside(listOfNotNull(selectedItem())).ifEmpty { outside(junit) }
    }

    private fun canExplain(item: TestNode?): Boolean {
        val runner = AgentRunner.getInstance(project)
        return item?.outcome == TestOutcome.FAILED && runner.running == null && runner.available()
    }

    private fun explain(item: TestNode?) {
        if (item != null && canExplain(item)) FailureExplanation.explain(project, item)
    }

    private fun visible(nodes: List<TestNode>): List<TestNode> =
        if (!onlyFailures) nodes
        else nodes.filter { it.outcome == TestOutcome.FAILED || it.outcome == TestOutcome.INTERRUPTED }

    private fun treeNode(item: TestNode): DefaultMutableTreeNode =
        DefaultMutableTreeNode(item).apply { visible(item.children).forEach { add(treeNode(it)) } }

    private fun updateDetails() {
        val item = selectedItem()
        details.text = html(if (item == null) gray(DETAILS_HINT) else detailsOf(item))
        details.caretPosition = 0
    }

    private fun detailsOf(item: TestNode): String = buildString {
        if (item.kind == TestKind.SUBJECT || item.kind == TestKind.CRITERION) {
            append(criteriaDetails(item))
            return@buildString
        }
        val name = if (item.kind == TestKind.TAG) null else DisplayNameFormat.parse(item.label)
        val title = name?.let(::nameHtml) ?: escape(item.tags.firstOrNull()?.let { "@$it" } ?: TestResults.NO_TAG)
        if (title.isNotBlank()) append("<h2 style='margin: 0 0 6px 0'>$title</h2>")
        append("<p>${pill("${symbolOf(item.outcome)} ${item.outcome.label}", toneOf(item.outcome))}")
        TestResults.formatDuration(item.durationMillis)?.let { append("&nbsp;&nbsp;${gray(it)}") }
        item.convention?.problem?.let { append("&nbsp;&nbsp;${pill("⚠ $it", Tone.YELLOW)}") }
        if (item.cucumber) append("&nbsp;&nbsp;${pill("Cucumber", Tone.TEAL)}")
        if (item.kind != TestKind.TAG) item.tags.forEach { append("&nbsp;&nbsp;${pill("@$it", tagTone(it))}") }
        append("</p>")
        item.codeReference?.let { append("<p>${gray("<code>${escape(it)}</code>")}</p>") }
        if (item.cucumber) cucumber?.let { append("<p>${gray("Do XML do Cucumber gravado ${CucumberResults.age(it.writtenAt)}.")}</p>") }
        name?.takeIf { it.structured }?.let { appendScenario(it) }
        if (item.kind == TestKind.TEST) {
            item.errorMessage?.let { append(section("Motivo")).append("<p>${escape(it).replace("\n", "<br>")}</p>") }
            appendExplainLink(item)
            item.stacktrace?.let { trace ->
                val lines = trace.lines()
                val shown = lines.take(STACKTRACE_LINES).joinToString("\n") + if (lines.size > STACKTRACE_LINES) "\n…" else ""
                append(section("Pilha")).append(code(shown))
            }
            return@buildString
        }
        append("<p>${countsOf(item)}</p>")
        appendConvention(item)
        val failures = item.tests.filter { it.outcome == TestOutcome.FAILED }
        if (failures.isNotEmpty()) {
            append(section("Falhas")).append("<ul>")
            failures.forEach { failure ->
                append("<li>${color(Tone.RED.accent, "<b>${escape(failure.label)}</b>")}")
                failure.errorMessage?.lineSequence()?.firstOrNull()?.let { append("<br>${gray(escape(it))}") }
                append("</li>")
            }
            append("</ul>")
            appendExplainLink(item)
        }
    }

    /** O atalho para a IA explicar as falhas do item, à vista nos detalhes. */
    private fun StringBuilder.appendExplainLink(item: TestNode) {
        if (canExplain(item)) append("<p><a href='$EXPLAIN_LINK'>Explicar falha com IA</a></p>")
    }

    private fun criteriaDetails(item: TestNode): String = buildString {
        val coverage = item.coverage
        if (item.kind == TestKind.SUBJECT) {
            append("<h2 style='margin: 0 0 6px 0'>${escape(item.label)}</h2>")
            val criteria = item.children.mapNotNull { it.coverage }
            if (criteria.isEmpty()) {
                append("<p>${gray(plural(item.tests.size, "teste", "testes"))}</p>")
                when (item.label) {
                    CriteriaView.WITHOUT -> append("<p>Estes testes não têm <code>@Tag(\"criterio:…\")</code> e rodam normalmente. " +
                        "<b>Padronizar Testes</b> pede para a IA ligar cada um ao critério que ele prova.</p>")
                    CriteriaView.NOT_APPLICABLE -> append("<p>Testes marcados com <code>criterio:nenhum</code>: nenhum critério de aceite se aplica a eles.</p>")
                    else -> append("<p>Estas tags <code>criterio:</code> apontam para um ID que o catálogo não tem. Corrija a tag ou acrescente o critério.</p>")
                }
            } else {
                append("<p>${CriterionStatus.entries.mapNotNull { status ->
                    criteria.count { it.status == status }.takeIf { it > 0 }?.let { pill("$it ${status.label.lowercase()}", statusTone(status)) }
                }.joinToString("&nbsp;&nbsp;")}</p>")
            }
            return@buildString
        }
        val id = item.criterionId.orEmpty()
        append("<h2 style='margin: 0 0 6px 0'>${escape(id)}${if (coverage != null) " · ${escape(item.label)}" else ""}</h2>")
        if (coverage == null) {
            append("<p>${pill("órfã", Tone.YELLOW)}&nbsp;&nbsp;${gray("Nenhum critério ${escape(id)} no catálogo")}</p>")
            append("<p>Corrija a tag do teste ou acrescente o critério em <code>${escape(CriteriaActions.FOLDER)}/</code>.</p>")
        } else {
            val criterion = coverage.criterion
            append("<p>${pill("${coverage.status.symbol} ${coverage.status.label}", statusTone(coverage.status))}")
            Level.entries.forEach { level ->
                when {
                    level in coverage.levels -> append("&nbsp;&nbsp;${pill(level.label, Tone.GREEN)}")
                    level in criterion.requires -> append("&nbsp;&nbsp;${pill("falta ${level.label.lowercase()}", Tone.YELLOW)}")
                }
            }
            append("</p>")
            criterion.origin?.let { append("<p>${gray("Origem: ${escape(it)}")}</p>") }
            append("<p>${gray("<code>${escape(criterion.file)}:${criterion.line}</code>")}</p>")
            if (criterion.requires.isNotEmpty()) {
                append("<p>${gray("Exige prova em: ${criterion.requires.joinToString(", ") { it.label.lowercase() }}")}</p>")
            }
            if (coverage.status == CriterionStatus.UNTESTED) {
                append("<p>Nenhum teste nem cenário aponta para este critério. Acrescente <code>@Tag(\"criterio:${escape(criterion.id)}\")</code> ao teste que o prova.</p>")
            }
        }
        if (item.tests.isNotEmpty()) {
            append(section("Testes (${item.tests.size})")).append("<ul>")
            item.tests.forEach { test ->
                val level = CriterionTags.of(test.tags).level?.let { "&nbsp;${pill(it.label, Tone.GRAY)}" }.orEmpty()
                append("<li>${color(toneOf(test.outcome).accent, symbolOf(test.outcome))} ${escape(test.label)}$level")
                if (test.outcome == TestOutcome.FAILED) test.errorMessage?.lineSequence()?.firstOrNull()?.let { append("<br>${gray(escape(it))}") }
                append("</li>")
            }
            append("</ul>")
        }
    }

    /** As etapas dado/quando/então numa tabela e os parâmetros da execução, cada um com o seu valor. */
    private fun StringBuilder.appendScenario(name: DisplayNameParts) {
        if (name.steps.any { it.role != null }) {
            append(section("Cenário")).append("<table cellpadding='3' cellspacing='0'>")
            for (step in name.steps) {
                val label = step.role?.let { pill(it.label.uppercase(), toneOf(it)) }.orEmpty()
                append("<tr><td valign='top' nowrap>$label</td><td>${fragmentsHtml(step.fragments)}</td></tr>")
            }
            append("</table>")
        }
        if (name.parameters.isNotEmpty()) {
            append(section("Parâmetros desta execução")).append("<table cellpadding='3' cellspacing='0'>")
            name.parameters.forEachIndexed { i, parameter ->
                val label = parameter.name ?: "argumento ${i + 1}"
                append("<tr><td valign='top' nowrap>${gray("<b>${escape(label)}</b>")}</td><td>${codeSpan(parameter.value, Tone.PURPLE)}</td></tr>")
            }
            append("</table>")
        }
    }

    private fun countsOf(item: TestNode): String {
        val parts = statsOf(item.tests).filter { it.count > 0 }.map { pill("${it.count} ${it.label}", it.tone) }.toMutableList()
        if (item.kind == TestKind.TAG) parts += gray(plural(item.classes.size, "classe", "classes"))
        return parts.joinToString("&nbsp;&nbsp;")
    }

    /** Classes fora da convenção, com o que falta em cada uma e um exemplo do padrão. */
    private fun StringBuilder.appendConvention(item: TestNode) {
        val outside = item.classes.filter { it.convention != Convention.OK }
        if (outside.isEmpty()) return
        append(section("⚠ Fora da convenção"))
        append(
            "<p>Coloque na classe de teste um <code>@DisplayName</code> que diga o que ela testa. " +
                "Os testes ficam em Dado/Quando/Então, com <code>@Tag(\"criterio:ID\")</code>:</p>",
        )
        append(code("@DisplayName(\"Cálculo do frete\")\nclass FreteServiceTest { … }"))
        append("<ul>")
        for (testClass in outside) {
            val name = testClass.codeReference?.substringAfterLast('.')?.substringAfterLast('$') ?: testClass.label
            append("<li><b>${escape(name)}</b>&nbsp;&nbsp;${pill(testClass.convention?.problem.orEmpty(), Tone.YELLOW)}</li>")
        }
        append("</ul>")
        append("<p>${gray("<b>Padronizar Testes</b>, na barra, deixa estas classes no padrão com a IA.")}</p>")
    }

    private fun open(item: TestNode?) {
        val url = item?.locationUrl ?: return
        val target = ReadAction.compute<Navigatable?, RuntimeException> {
            JUnitDisplayNames.resolve(project, url)?.navigationElement as? Navigatable
        }
        target?.takeIf { it.canNavigate() }?.navigate(true)
    }

    private fun installNavigation(openAction: DumbAwareAction, explainAction: DumbAwareAction) {
        tree.addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) {
                if (!SwingUtilities.isRightMouseButton(e)) return
                tree.getPathForLocation(e.x, e.y)?.let { tree.selectionPath = it }
            }

            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount != 2 || !SwingUtilities.isLeftMouseButton(e)) return
                val item = itemOf(tree.getPathForLocation(e.x, e.y)?.lastPathComponent) ?: return
                if (item.kind == TestKind.TEST) open(item)
            }
        })
        tree.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                if (e.keyCode != KeyEvent.VK_ENTER) return
                val item = selectedItem() ?: return
                if (item.kind == TestKind.TEST) {
                    open(item)
                } else {
                    val path = tree.selectionPath ?: return
                    if (tree.isExpanded(path)) tree.collapsePath(path) else tree.expandPath(path)
                }
            }
        })
        PopupHandler.installPopupMenu(tree, DefaultActionGroup(openAction, explainAction), "IronDome.TestsPopup")
    }

    private fun itemOf(value: Any?) = (value as? DefaultMutableTreeNode)?.userObject as? TestNode

    private fun selectedItem() = itemOf(tree.lastSelectedPathComponent)

    private class Renderer : RichTreeCellRenderer() {

        override fun describe(row: Row, value: Any?) {
            val item = (value as? DefaultMutableTreeNode)?.userObject as? TestNode ?: return
            val failed = item.outcome == TestOutcome.FAILED
            when (item.kind) {
                TestKind.TAG -> {
                    val tag = item.tags.firstOrNull()
                    if (tag == null) {
                        row.icon(AllIcons.General.Information)
                        row.pill(TestResults.NO_TAG, Tone.GRAY, bold = true)
                    } else {
                        row.icon(iconOf(item.outcome))
                        row.pill("@$tag", tagTone(tag), bold = true)
                    }
                    row.text(
                        "${plural(item.classes.size, "classe", "classes")} · ${plural(item.tests.size, "teste", "testes")}",
                        SimpleTextAttributes.GRAYED_ATTRIBUTES,
                    )
                    progress(row, item)
                }
                TestKind.SUBJECT -> {
                    val criteria = item.children.mapNotNull { it.coverage }
                    if (criteria.isEmpty()) {
                        row.icon(if (item.label == CriteriaView.WITHOUT || item.label == CriteriaView.ORPHANS) AllIcons.General.Warning else AllIcons.General.Information)
                        row.pill(item.label, if (item.label == CriteriaView.NOT_APPLICABLE) Tone.GRAY else Tone.YELLOW, bold = true)
                        row.text(plural(item.tests.size, "teste", "testes"), SimpleTextAttributes.GRAYED_ATTRIBUTES)
                    } else {
                        row.icon(subjectIcon(criteria))
                        row.pill(item.label, Tone.forKey(item.label), bold = true)
                        val met = criteria.count { it.status == CriterionStatus.MET }
                        row.text("$met/${criteria.size} atendidos", if (criteria.any { it.status == CriterionStatus.FAILING }) FAILED_PLAIN else SimpleTextAttributes.GRAYED_ATTRIBUTES)
                        criteria.count { it.status == CriterionStatus.FAILING }.takeIf { it > 0 }?.let { row.pill("✖ $it", Tone.RED, bold = true) }
                        criteria.count { it.status == CriterionStatus.UNTESTED }.takeIf { it > 0 }?.let { row.pill("∅ $it", Tone.ORANGE) }
                    }
                }
                TestKind.CRITERION -> {
                    val coverage = item.coverage
                    if (coverage == null) {
                        row.icon(AllIcons.General.Warning)
                        row.pill(item.criterionId.orEmpty(), Tone.YELLOW, bold = true)
                        row.text("tag órfã: o catálogo não tem este critério", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                    } else {
                        row.icon(statusIcon(coverage.status))
                        row.pill(coverage.criterion.id, Tone.forKey(coverage.criterion.id), bold = true)
                        name(row, coverage.criterion.title, if (coverage.status == CriterionStatus.FAILING) FAILED_PLAIN else SimpleTextAttributes.REGULAR_ATTRIBUTES)
                        row.pill("${coverage.status.symbol} ${coverage.status.label}", statusTone(coverage.status), bold = true)
                        Level.entries.forEach { level ->
                            when {
                                level in coverage.levels -> row.pill(level.initial, Tone.GREEN)
                                level in coverage.criterion.requires -> row.pill("falta ${level.initial}", Tone.YELLOW)
                            }
                        }
                        if (item.tests.isNotEmpty()) row.text(plural(item.tests.size, "teste", "testes"), SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
                    }
                }
                TestKind.CLASS -> {
                    row.icon(iconOf(item.outcome))
                    name(row, item.label, if (failed) FAILED_BOLD else SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
                    item.codeName?.let { row.text(it, SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES) }
                    if (item.cucumber) row.pill("Cucumber", Tone.TEAL)
                    item.convention?.problem?.let { row.pill("⚠ $it", Tone.YELLOW) }
                    progress(row, item)
                }
                TestKind.GROUP -> {
                    row.icon(iconOf(item.outcome))
                    name(row, item.label, if (failed) FAILED_PLAIN else SimpleTextAttributes.REGULAR_ATTRIBUTES)
                    progress(row, item)
                }
                TestKind.TEST -> {
                    row.icon(iconOf(item.outcome))
                    name(row, item.label, if (failed) FAILED_PLAIN else SimpleTextAttributes.REGULAR_ATTRIBUTES)
                    item.codeName?.let { row.text(it, SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES) }
                    CriterionTags.of(item.tags).level?.let { row.pill(it.label, Tone.GRAY) }
                    if (item.outcome == TestOutcome.IGNORED) row.pill("ignorado", Tone.YELLOW)
                    TestResults.formatDuration(item.durationMillis)?.let { row.text(it, SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES) }
                    item.errorMessage?.lineSequence()?.firstOrNull { it.isNotBlank() }?.let { row.detail(it.trim().take(ERROR_PREVIEW)) }
                }
            }
            row.tooltip(item.errorMessage?.lineSequence()?.firstOrNull() ?: item.codeReference)
        }

        /**
         * O nome com as partes destacadas: número da execução, etiquetas das etapas dado/quando/então e, no texto,
         * verbo de expectativa, código, números, exceções e o resultado depois da seta.
         */
        private fun name(row: Row, label: String, base: SimpleTextAttributes) {
            val name = DisplayNameFormat.parse(label)
            name.index?.let { row.pill("#$it", Tone.GRAY, bold = true) }
            for (step in name.steps) {
                step.role?.let { row.pill(step.keyword.orEmpty().uppercase(), toneOf(it), bold = true) }
                row.styled(step.fragments.map { fragmentText(it) to attributesOf(it.style, base) })
            }
            row.styled(
                name.parameters.flatMapIndexed { i, parameter ->
                    listOfNotNull(
                        ("  ·  " to SimpleTextAttributes.GRAYED_ATTRIBUTES).takeIf { i > 0 },
                        parameter.name?.let { "$it = " to SimpleTextAttributes.GRAYED_ATTRIBUTES },
                        padded(parameter.value) to attributesOf(FragmentStyle.CODE, base),
                    )
                },
            )
        }

        /** Barra com a proporção de cada resultado, quantos passaram e os destaques de falhas e ignorados. */
        private fun progress(row: Row, item: TestNode) {
            val tests = item.tests
            if (tests.isEmpty()) return
            val passed = item.count(TestOutcome.PASSED)
            val failed = item.count(TestOutcome.FAILED)
            val ignored = item.count(TestOutcome.IGNORED)
            row.bar(barOf(tests))
            row.text("$passed/${tests.size}", if (failed > 0) FAILED_PLAIN else SimpleTextAttributes.GRAYED_ATTRIBUTES)
            if (failed > 0) row.pill("✖ $failed", Tone.RED, bold = true)
            if (ignored > 0) row.pill("⊘ $ignored", Tone.YELLOW)
            TestResults.formatDuration(item.durationMillis)?.let { row.text(it, SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES) }
        }

        private companion object {
            const val ERROR_PREVIEW = 140
            val FAILED_BOLD = SimpleTextAttributes(SimpleTextAttributes.STYLE_BOLD, Tone.RED.accent)
            val FAILED_PLAIN = SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, Tone.RED.accent)
        }
    }

    private companion object {
        val LOG = Logger.getInstance(TestsPanel::class.java)
        const val RUN_NAME = "IronHawk IA: todos os testes"
        const val REFRESH_DELAY_MS = 200
        const val LOAD_DELAY_MS = 300
        const val STACKTRACE_LINES = 40
        const val IDLE = "Clique em Executar Todos os Testes para pôr o escudo à prova."
        const val VIEW_KEY = "IronDome.Escudo.View"
        const val DETAILS_HINT = "Selecione um item para ver os detalhes; duplo clique num teste abre o código."
        const val RUN_DESCRIPTION = "Compila o projeto e executa todos os testes JUnit"
        const val EXPLAIN_DESCRIPTION = "A IA explica por que o teste falhou e propõe a correção, sem alterar arquivos"
        const val STANDARDIZE_DESCRIPTION = "A IA põe Dado/Quando/Então e as tags criterio: nos testes, sem mexer na lógica"
        const val EXPLAIN_LINK = "explicar"

        fun statusTone(status: CriterionStatus): Tone = when (status) {
            CriterionStatus.MET -> Tone.GREEN
            CriterionStatus.PARTIAL -> Tone.YELLOW
            CriterionStatus.FAILING -> Tone.RED
            CriterionStatus.NOT_RUN -> Tone.GRAY
            CriterionStatus.UNTESTED -> Tone.ORANGE
        }

        fun statusIcon(status: CriterionStatus): Icon = when (status) {
            CriterionStatus.MET -> AllIcons.RunConfigurations.TestPassed
            CriterionStatus.PARTIAL -> AllIcons.RunConfigurations.TestIgnored
            CriterionStatus.FAILING -> AllIcons.RunConfigurations.TestFailed
            CriterionStatus.NOT_RUN -> AllIcons.RunConfigurations.TestNotRan
            CriterionStatus.UNTESTED -> AllIcons.General.Warning
        }

        /** O pior status do grupo manda no ícone. */
        fun subjectIcon(criteria: List<CriterionCoverage>): Icon {
            val statuses = criteria.map { it.status }.toSet()
            return statusIcon(
                listOf(CriterionStatus.FAILING, CriterionStatus.UNTESTED, CriterionStatus.PARTIAL, CriterionStatus.NOT_RUN)
                    .firstOrNull { it in statuses } ?: CriterionStatus.MET,
            )
        }

        /** Até quatro números: passaram, falharam, ignorados e os não concluídos (na fila, durante a execução). */
        fun statsOf(tests: List<TestNode>, running: Boolean = false): List<Stat> {
            fun count(outcome: TestOutcome) = tests.count { it.outcome == outcome }
            val unfinished = count(TestOutcome.RUNNING) + count(TestOutcome.NOT_RUN) + count(TestOutcome.INTERRUPTED)
            return listOf(
                Stat(count(TestOutcome.PASSED), if (count(TestOutcome.PASSED) == 1) "passou" else "passaram", Tone.GREEN),
                Stat(count(TestOutcome.FAILED), if (count(TestOutcome.FAILED) == 1) "falhou" else "falharam", Tone.RED),
                Stat(count(TestOutcome.IGNORED), if (count(TestOutcome.IGNORED) == 1) "ignorado" else "ignorados", Tone.YELLOW),
                if (running) Stat(unfinished, "na fila", Tone.GRAY)
                else Stat(unfinished, if (unfinished == 1) "não concluído" else "não concluídos", Tone.ORANGE),
            )
        }

        fun barOf(tests: List<TestNode>): List<Segment> {
            fun count(outcome: TestOutcome) = tests.count { it.outcome == outcome }
            return listOf(
                Segment(count(TestOutcome.PASSED), Tone.GREEN.accent),
                Segment(count(TestOutcome.FAILED), Tone.RED.accent),
                Segment(count(TestOutcome.IGNORED), Tone.YELLOW.accent),
                Segment(count(TestOutcome.INTERRUPTED), Tone.ORANGE.accent),
                Segment(count(TestOutcome.RUNNING) + count(TestOutcome.NOT_RUN), Tone.GRAY.background),
            )
        }

        fun iconOf(outcome: TestOutcome): Icon = when (outcome) {
            TestOutcome.RUNNING -> AllIcons.RunConfigurations.TestState.Run
            TestOutcome.FAILED -> AllIcons.RunConfigurations.TestFailed
            TestOutcome.INTERRUPTED -> AllIcons.RunConfigurations.TestTerminated
            TestOutcome.IGNORED -> AllIcons.RunConfigurations.TestIgnored
            TestOutcome.PASSED -> AllIcons.RunConfigurations.TestPassed
            TestOutcome.NOT_RUN -> AllIcons.RunConfigurations.TestNotRan
        }

        /** A mesma cor para a mesma tag, separada das cores dos grupos de critério. */
        fun tagTone(tag: String): Tone = Tone.forKey("@$tag")

        fun toneOf(role: StepRole): Tone = when (role) {
            StepRole.GIVEN -> Tone.BLUE
            StepRole.WHEN -> Tone.PURPLE
            StepRole.THEN -> Tone.TEAL
        }

        fun attributesOf(style: FragmentStyle, base: SimpleTextAttributes): SimpleTextAttributes {
            val bold = base.style or SimpleTextAttributes.STYLE_BOLD
            return when (style) {
                FragmentStyle.TEXT -> base
                FragmentStyle.VERB -> base.derive(bold, null, null, null)
                FragmentStyle.NEGATION -> SimpleTextAttributes(bold, Tone.ORANGE.foreground)
                FragmentStyle.CODE -> SimpleTextAttributes(Tone.PURPLE.background, Tone.PURPLE.foreground, null, base.style or SimpleTextAttributes.STYLE_OPAQUE)
                FragmentStyle.ERROR -> SimpleTextAttributes(Tone.RED.background, Tone.RED.foreground, null, base.style or SimpleTextAttributes.STYLE_OPAQUE)
                FragmentStyle.NUMBER -> SimpleTextAttributes(bold, Tone.BLUE.foreground)
                FragmentStyle.ARROW -> SimpleTextAttributes(bold, Tone.GRAY.accent)
                FragmentStyle.RESULT -> SimpleTextAttributes(bold, Tone.GREEN.foreground)
            }
        }

        /** Código e exceções ganham um respiro dentro do fundo colorido. */
        fun fragmentText(fragment: Fragment) =
            if (fragment.style == FragmentStyle.CODE || fragment.style == FragmentStyle.ERROR) padded(fragment.text) else fragment.text

        fun padded(text: String) = " $text "

        /** Etapas dado/quando/então e parâmetros ficam de fora: já têm a sua seção nos detalhes. */
        fun nameHtml(name: DisplayNameParts): String = buildString {
            name.index?.let { append(pill("#$it", Tone.GRAY)).append("&nbsp;") }
            if (name.steps.none { it.role != null }) append(name.steps.joinToString(" ") { fragmentsHtml(it.fragments) })
        }

        fun fragmentsHtml(fragments: List<Fragment>): String = fragments.joinToString("") { fragment ->
            val text = escape(fragment.text)
            when (fragment.style) {
                FragmentStyle.TEXT -> text
                FragmentStyle.VERB -> "<b>$text</b>"
                FragmentStyle.NEGATION -> color(Tone.ORANGE.foreground, "<b>$text</b>")
                FragmentStyle.CODE -> codeSpan(fragment.text, Tone.PURPLE)
                FragmentStyle.ERROR -> codeSpan(fragment.text, Tone.RED)
                FragmentStyle.NUMBER -> color(Tone.BLUE.foreground, "<b>$text</b>")
                FragmentStyle.ARROW -> color(Tone.GRAY.accent, "<b>&nbsp;→&nbsp;</b>")
                FragmentStyle.RESULT -> color(Tone.GREEN.foreground, "<b>$text</b>")
            }
        }

        fun codeSpan(text: String, tone: Tone) =
            "<span style='background-color:${hex(tone.background)}; color:${hex(tone.foreground)}; font-family: monospace'>&nbsp;${escape(text)}&nbsp;</span>"

        fun toneOf(outcome: TestOutcome): Tone = when (outcome) {
            TestOutcome.PASSED -> Tone.GREEN
            TestOutcome.FAILED -> Tone.RED
            TestOutcome.IGNORED -> Tone.YELLOW
            TestOutcome.INTERRUPTED -> Tone.ORANGE
            TestOutcome.RUNNING -> Tone.BLUE
            TestOutcome.NOT_RUN -> Tone.GRAY
        }

        fun symbolOf(outcome: TestOutcome) = when (outcome) {
            TestOutcome.PASSED -> "✔"
            TestOutcome.FAILED -> "✖"
            TestOutcome.IGNORED -> "⊘"
            TestOutcome.INTERRUPTED -> "■"
            else -> "…"
        }

        fun escape(text: String): String = StringUtil.escapeXmlEntities(text)

        fun hex(color: Color) = "#${ColorUtil.toHex(color)}"

        fun color(color: Color, html: String) = "<span style='color:${hex(color)}'>$html</span>"

        fun gray(html: String) = color(UIUtil.getContextHelpForeground(), html)

        fun pill(text: String, tone: Tone) =
            "<span style='background-color:${hex(tone.background)}; color:${hex(tone.foreground)}'>&nbsp;<b>${escape(text)}</b>&nbsp;</span>"

        fun section(title: String) = "<p style='margin-top: 10px'><b>${escape(title)}</b></p>"

        fun code(text: String) =
            "<pre style='background-color:${hex(Tone.GRAY.background)}; padding: 6px; margin: 4px 0'>${escape(text)}</pre>"

        fun html(body: String) = "<html><body>$body</body></html>"
    }
}
