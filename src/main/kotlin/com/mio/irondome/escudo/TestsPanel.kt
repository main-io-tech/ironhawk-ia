package com.mio.irondome.escudo

import com.intellij.psi.util.PsiModificationTracker
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
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.DumbAwareAction
import org.jetbrains.annotations.TestOnly
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.pom.Navigatable
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.PopupHandler
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.components.panels.VerticalLayout
import com.intellij.util.Alarm
import com.intellij.util.ui.HTMLEditorKitBuilder
import com.intellij.util.ui.JBUI
import com.mio.irondome.base.IronDomeIcons
import com.mio.irondome.base.MechanismTree
import com.mio.irondome.base.Stat
import com.mio.irondome.base.SummaryHeader
import com.mio.irondome.base.Tone
import com.mio.irondome.base.plural
import com.mio.irondome.base.wrapping
import com.mio.irondome.interceptor.AgentProgressPanel
import com.mio.irondome.interceptor.AgentRunner
import com.mio.irondome.interceptor.FireWithAiAction
import java.awt.BorderLayout
import java.awt.event.HierarchyEvent
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JEditorPane
import javax.swing.JPanel
import javax.swing.SwingUtilities
import javax.swing.event.HyperlinkEvent

/**
 * Aba Escudo: executa todos os testes JUnit do projeto pela configuração "All in project" da IDE e mostra o resultado
 * numa árvore pela `@Tag` dos testes, com o `@DisplayName` de cada um como está no código. A ficha da linha escolhida
 * traz o resultado e o que o teste verifica de fato, lido do código, para validar o comportamento sem abri-lo. A
 * execução também aparece na janela Run, com o console completo.
 */
class TestsPanel(private val project: Project) : SimpleToolWindowPanel(true, true), Disposable {

    private val escudoTree = EscudoTree { lookup }
    private val tree: MechanismTree get() = escudoTree.tree
    private val header = SummaryHeader(IronDomeIcons.escudo, Tone.GREEN)
    private val details = JEditorPane().apply {
        editorKit = HTMLEditorKitBuilder().withWordWrapViewFactory().build()
        isEditable = false
        border = JBUI.Borders.empty(10, 14)
    }
    private val displayNames = JUnitDisplayNames(project)
    private val refreshAlarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, this)
    private val reloadAlarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, this)

    @Volatile
    private var environment: ExecutionEnvironment? = null
    @Volatile
    private var testsRoot: SMTestProxy.SMRootTestProxy? = null
    @Volatile
    private var process: ProcessHandler? = null
    @Volatile
    private var notStarted = false
    /** As classes de teste da execução do JUnit pela aba. */
    private var results: List<TestNode> = emptyList()
    /** A prova de cada teste, pelo identificador, lida do código em segundo plano quando a execução acaba. */
    private var evidence: Map<String, TestEvidence> = emptyMap()
    private var lookup = EscudoLookup { null }
    private val detailsOf = EscudoDetails({ lookup }, ::canExplain)
    /** A leitura da prova mais nova pedida, só na EDT: uma leitura que termina depois de outra mais nova é descartada. */
    private var evidenceGeneration = 0
    /** O que o projeto tinha gravado na última leitura: sem mudança, voltar à aba não relê nada. */
    private var loadedStamp = -1L
    private var wasRunning = false

    private val running: Boolean
        get() = environment != null && !notStarted && process?.isProcessTerminated != true

    init {
        val expander = DefaultTreeExpander { tree }
        val openAction = object : DumbAwareAction("Ver o Teste", "Abre a classe ou o método de teste selecionado", AllIcons.Actions.EditSource) {
            override fun getActionUpdateThread() = ActionUpdateThread.EDT
            override fun update(e: AnActionEvent) {
                e.presentation.isEnabled = selectedItem()?.let(::urlOf) != null
            }
            override fun actionPerformed(e: AnActionEvent) = open(selectedItem())
        }
        val explainAction = object : DumbAwareAction("Explicar Falha com IA", EXPLAIN_DESCRIPTION, IronDomeIcons.ia) {
            override fun getActionUpdateThread() = ActionUpdateThread.EDT
            override fun update(e: AnActionEvent) {
                val runner = AgentRunner.getInstance(project)
                e.presentation.isEnabled = selectedItem()?.let(::canExplain) == true
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
        toolbar = ActionManager.getInstance().createActionToolbar(
            "IronDome.TestsToolbar",
            DefaultActionGroup(
                runAction,
                CommonActionsManager.getInstance().createExpandAllAction(expander, tree),
                CommonActionsManager.getInstance().createCollapseAllAction(expander, tree),
            ),
            true,
        ).apply { targetComponent = tree }.wrapping().component

        installNavigation(DefaultActionGroup(openAction, explainAction))
        tree.addTreeSelectionListener { updateDetails() }
        tree.emptyText.text = IDLE
        val splitter = OnePixelSplitter(true, "IronDome.TestsSplitter", 0.5f).apply {
            firstComponent = escudoTree.component
            secondComponent = ScrollPaneFactory.createScrollPane(details, true)
        }
        setContent(JPanel(BorderLayout()).apply {
            add(JPanel(VerticalLayout(0)).apply {
                add(header)
                add(AgentProgressPanel(project, TestsTab.TITLE, this@TestsPanel))
            }, BorderLayout.NORTH)
            add(splitter, BorderLayout.CENTER)
        })
        details.addHyperlinkListener { event ->
            if (event.eventType != HyperlinkEvent.EventType.ACTIVATED) return@addHyperlinkListener
            val link = event.description.orEmpty()
            when {
                link == EscudoDetails.EXPLAIN_LINK -> explain(selectedItem())
                link.startsWith(EscudoHtml.OPEN_LINK) -> openUrl(link.removePrefix(EscudoHtml.OPEN_LINK))
                // Abrir as asserções de um passo refaz a ficha: ela fica onde estava, sem voltar ao topo.
                detailsOf.toggle(link) -> updateDetails(keepPosition = true)
            }
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
        // Os testes mudam enquanto a aba está escondida (a IA escreve, o usuário edita): quando ela volta a aparecer,
        // os nomes, as tags e a prova são relidos, se algo mudou no projeto.
        addHierarchyListener { event ->
            if (event.changeFlags and HierarchyEvent.SHOWING_CHANGED.toLong() != 0L && isShowing) scheduleReload()
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
        escudoTree.reset()
        loadedStamp = projectStamp()
        refresh()
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
        results = testsRoot?.let { TestResults.build(it, displayNames::of, displayNames::tagsOf) }.orEmpty()
        val nowRunning = running
        // A execução acabou: o código dos testes que rodaram é lido para dizer o que cada um verifica.
        val finished = wasRunning && !nowRunning
        if (finished) loadEvidence()
        wasRunning = nowRunning
        redraw()
        if (finished) escudoTree.revealFailure()
    }

    /** Refaz o que depende da prova, sem reler nada. */
    private fun redraw() {
        lookup = buildLookup()
        updateHeader()
        showTree()
        updateDetails()
    }

    /** Um objeto novo a cada mudança da prova: é por ele que a árvore sabe que as linhas guardadas deixaram de valer. */
    private fun buildLookup(): EscudoLookup {
        val evidence = evidence
        return EscudoLookup { evidence[it] }
    }

    /** Mostra [root] como a execução terminada, com a prova [evidence] já lida, sem rodar nem ler nada. */
    @TestOnly
    internal fun preview(root: SMTestProxy.SMRootTestProxy, evidence: Map<String, TestEvidence>) {
        reloadAlarm.cancelAllRequests()
        evidenceGeneration++
        testsRoot = root
        this.evidence = evidence
        refresh()
        escudoTree.revealFailure()
    }

    @TestOnly
    internal fun tree(): MechanismTree = tree

    /** Pedidos seguidos, como a aba aparecendo várias vezes, viram um só depois de [RELOAD_DELAY_MS]. */
    private fun scheduleReload() {
        // Nada foi gravado no projeto desde a última leitura: o que está na tela continua valendo.
        if (results.isEmpty() || projectStamp() == loadedStamp) return
        reloadAlarm.cancelAllRequests()
        reloadAlarm.addRequest({ reload() }, RELOAD_DELAY_MS)
    }

    /** Muda quando um arquivo é gravado ou o código é editado, mesmo sem salvar. */
    private fun projectStamp(): Long =
        VirtualFileManager.getInstance().modificationCount * 31 + PsiModificationTracker.getInstance(project).modificationCount

    /** O código pode ter mudado desde a execução (alguém renomeou um teste, pôs uma tag): nomes, tags e prova são relidos. */
    private fun reload() {
        loadedStamp = projectStamp()
        displayNames.clear()
        refresh()
        if (!running) loadEvidence()
    }

    /** Lê do código o que cada teste executado verifica, fora da EDT, e redesenha quando termina. Na EDT. */
    private fun loadEvidence() {
        val urls = results.flatMap { it.tests }.mapNotNull { it.locationUrl }.distinct()
        val generation = ++evidenceGeneration
        if (urls.isEmpty()) return
        ApplicationManager.getApplication().executeOnPooledThread {
            val read = runCatching { EvidenceReader.read(project, urls) }
                .onFailure { LOG.warn("Escudo: não consegui ler o código dos testes", it) }
                .getOrNull() ?: return@executeOnPooledThread
            ApplicationManager.getApplication().invokeLater({
                if (generation != evidenceGeneration) return@invokeLater
                evidence = read
                redraw()
            }, ModalityState.any(), project.disposed)
        }
    }

    private fun updateHeader() {
        val tests = results.flatMap { it.tests }
        val classes = results.flatMap { it.classes }
        val failed = tests.count { it.outcome == TestOutcome.FAILED }
        val context = listOfNotNull(
            plural(classes.size, "classe", "classes"),
            testsRoot?.duration?.let(TestResults::formatDuration),
        ).joinToString(" · ")
        val stats = statsOf(tests, running)
        val bar = EscudoRenderer.barOf(tests)
        when {
            testsRoot == null && environment == null -> header.update("Escudo pronto", subtitle = IDLE)
            notStarted -> header.update(
                "Os testes não chegaram a rodar",
                Tone.RED.accent,
                "Confira os erros de compilação ou de configuração nas janelas Build e Run.",
            )
            running && tests.isEmpty() -> header.update("Preparando os testes…", subtitle = "Compilando o projeto e procurando os testes JUnit")
            running -> {
                val done = tests.count { it.outcome != TestOutcome.RUNNING && it.outcome != TestOutcome.NOT_RUN }
                header.update("Executando os testes…", Tone.BLUE.accent, "$done de ${tests.size} testes · $context", stats, bar)
            }
            tests.isEmpty() -> header.update("Nenhum teste JUnit executado", Tone.YELLOW.accent, "Veja o console na janela Run.")
            failed > 0 -> header.update(
                "Escudo rompido",
                Tone.RED.accent,
                "${plural(failed, "teste falhou", "testes falharam")} de ${tests.size} · $context",
                stats, bar,
            )
            tests.any { it.outcome == TestOutcome.INTERRUPTED } ->
                header.update("Execução interrompida", Tone.ORANGE.accent, "${tests.size} testes · $context", stats, bar)
            else -> header.update(
                "Escudo íntegro",
                Tone.GREEN.accent,
                "${if (tests.size == 1) "O teste passou" else "Todos os ${tests.size} testes passaram"} · $context",
                stats, bar,
            )
        }
    }

    /** A árvore pela `@Tag`: com poucos grupos, ela abre inteira; com muitos, só o que falhou abre sozinho. */
    private fun showTree() {
        val nodes = TestResults.byTagOrClasses(results)
        tree.emptyText.text = when {
            running -> "Aguardando os testes…"
            testsRoot == null && environment == null -> IDLE
            else -> "Nenhum teste executado"
        }
        val few = nodes.size <= FEW_GROUPS
        escudoTree.show(nodes) { item, level -> level == 1 && few || lookup.attention(item) }
    }

    private fun canExplain(item: TestNode): Boolean {
        val runner = AgentRunner.getInstance(project)
        return item.outcome == TestOutcome.FAILED && runner.running == null && runner.available()
    }

    private fun explain(item: TestNode?) {
        if (item != null && canExplain(item)) FailureExplanation.explain(project, item)
    }

    private fun updateDetails(keepPosition: Boolean = false) {
        val item = selectedItem()
        val position = details.visibleRect.location
        details.text = EscudoHtml.html(if (item == null) EscudoHtml.gray(DETAILS_HINT) else detailsOf.of(item))
        details.caretPosition = 0
        if (keepPosition) SwingUtilities.invokeLater { details.scrollRectToVisible(java.awt.Rectangle(position, details.visibleRect.size)) }
    }

    /** O teste de um item: o dele ou, num grupo, o que falhou e, sem falha, o primeiro. */
    private fun urlOf(item: TestNode): String? =
        item.locationUrl ?: item.tests.sortedBy { it.outcome != TestOutcome.FAILED }.firstNotNullOfOrNull { it.locationUrl }

    private fun open(item: TestNode?) = openUrl(item?.let(::urlOf))

    private fun openUrl(url: String?) {
        if (url == null) return
        val target = ReadAction.compute<Navigatable?, RuntimeException> {
            JUnitDisplayNames.resolve(project, url)?.navigationElement as? Navigatable
        }
        target?.takeIf { it.canNavigate() }?.navigate(true)
    }

    private fun installNavigation(popup: DefaultActionGroup) {
        tree.addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) {
                if (!SwingUtilities.isRightMouseButton(e)) return
                tree.getPathForLocation(e.x, e.y)?.let { tree.selectionPath = it }
            }

            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount != 2 || !SwingUtilities.isLeftMouseButton(e)) return
                val item = EscudoTree.itemOf(tree.getPathForLocation(e.x, e.y)?.lastPathComponent) ?: return
                if (item.kind == TestKind.TEST) open(item)
            }
        })
        tree.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                if (e.keyCode != KeyEvent.VK_ENTER) return
                val item = escudoTree.selected() ?: return
                if (item.kind == TestKind.TEST) {
                    open(item)
                } else {
                    val path = tree.selectionPath ?: return
                    if (tree.isExpanded(path)) tree.collapsePath(path) else tree.expandPath(path)
                }
            }
        })
        PopupHandler.installPopupMenu(tree, popup, "IronDome.TestsPopup")
    }

    private fun selectedItem() = escudoTree.selected()

    private companion object {
        val LOG = Logger.getInstance(TestsPanel::class.java)
        const val RUN_NAME = "IronHawk IA: todos os testes"
        const val REFRESH_DELAY_MS = 200
        const val RELOAD_DELAY_MS = 300
        const val IDLE = "Execute os testes para ver o resultado."
        const val DETAILS_HINT = "Selecione um teste para ver o resultado e o que ele verifica."
        /** Até quantos grupos a árvore abre inteira sozinha. */
        const val FEW_GROUPS = 3
        const val RUN_DESCRIPTION = "Compila o projeto e executa todos os testes JUnit"
        const val EXPLAIN_DESCRIPTION = "A IA explica a falha e propõe a correção, sem alterar arquivos"

        /** Os números do cabeçalho, que somem quando são zero: o que passou e, em destaque, o que falhou ou não terminou. */
        fun statsOf(tests: List<TestNode>, running: Boolean): List<Stat> {
            val counts = tests.groupingBy { it.outcome }.eachCount()
            fun count(outcome: TestOutcome) = counts[outcome] ?: 0
            val passed = count(TestOutcome.PASSED)
            val failed = count(TestOutcome.FAILED)
            val ignored = count(TestOutcome.IGNORED)
            val unfinished = count(TestOutcome.RUNNING) + count(TestOutcome.NOT_RUN) + count(TestOutcome.INTERRUPTED)
            return listOf(
                Stat(passed, if (passed == 1) "teste passou" else "testes passaram", Tone.GREEN),
                Stat(failed, if (failed == 1) "falhou" else "falharam", Tone.RED),
                Stat(ignored, if (ignored == 1) "ignorado" else "ignorados", Tone.GRAY),
                if (running) Stat(unfinished, "na fila", Tone.GRAY)
                else Stat(unfinished, if (unfinished == 1) "não concluído" else "não concluídos", Tone.ORANGE),
            )
        }
    }
}
