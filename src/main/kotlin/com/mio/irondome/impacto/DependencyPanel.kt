package com.mio.irondome.impacto

import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.pom.Navigatable
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.table.JBTable
import com.intellij.util.concurrency.AppExecutorUtil
import com.mio.irondome.base.IronDomeIcons
import com.mio.irondome.base.Stat
import com.mio.irondome.base.SummaryHeader
import com.mio.irondome.base.Tone
import com.mio.irondome.base.wrapping
import java.awt.BorderLayout
import java.awt.Color
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.util.concurrent.Callable
import javax.swing.JPanel
import javax.swing.ListSelectionModel
import javax.swing.table.AbstractTableModel

/**
 * Aba "Raio de Impacto": a classe mirada e seus vizinhos num diagrama de classes (atributos, operações,
 * herança, realização, associação e uso), com a tabela de quantas classes dependem de cada uma e de
 * quantas ela depende.
 */
class DependencyPanel(private val project: Project, @Suppress("UNUSED_PARAMETER") toolWindow: ToolWindow) : JPanel(BorderLayout()), Disposable {

    private val diagram = DiagramBrowser(this, ::onDiagramMessage)
    private val header = SummaryHeader(IronDomeIcons.impacto, Tone.BLUE)
    private val classModel = ClassTableModel()
    private val classTable = table(classModel)

    @Volatile
    private var running: ProgressIndicator? = null
    @Volatile
    private var generations = 0
    private var syncingSelection = false

    init {
        val actions = DefaultActionGroup(
            object : DumbAwareAction("Mirar Classe Atual", "Desenha o diagrama da classe aberta no editor e das ligadas a ela", IronDomeIcons.impacto) {
                override fun getActionUpdateThread() = ActionUpdateThread.BGT
                override fun actionPerformed(e: AnActionEvent) = analyzeCurrentEditor()
            },
        )
        val toolbar = ActionManager.getInstance().createActionToolbar("IronDome.DependenciesToolbar", actions, true).wrapping()
        toolbar.targetComponent = this

        installTableNavigation()
        val splitter = OnePixelSplitter(true, "IronDome.DependenciesSplitter", 0.7f).apply {
            firstComponent = diagram.component
            secondComponent = JBScrollPane(classTable)
        }
        add(JPanel(BorderLayout()).apply {
            add(header, BorderLayout.NORTH)
            add(toolbar.component, BorderLayout.CENTER)
        }, BorderLayout.NORTH)
        add(splitter, BorderLayout.CENTER)
        updateStatus(null, null)
    }

    override fun dispose() {
        running?.cancel()
    }

    fun analyzeCurrentEditor() {
        val editor = FileEditorManager.getInstance(project).selectedTextEditor
        val file = editor?.let { PsiDocumentManager.getInstance(project).getPsiFile(it.document) }
        if (editor == null || file == null) {
            updateStatus(null, "Abra uma classe Java ou Kotlin no editor.", Tone.YELLOW.accent)
            return
        }
        analyzeAt(file, editor.caretModel.offset)
    }

    private fun analyzeAt(file: PsiFile, offset: Int?) {
        run("Calculando o raio de impacto") {
            ReadAction.nonBlocking(Callable { DependencyAnalyzer.classAt(file, offset) }).inSmartMode(project).executeSynchronously()
        }
    }

    private fun analyze(qualifiedName: String) {
        run("Calculando o raio de impacto de ${DependencyAnalyzer.simpleName(qualifiedName)}") { qualifiedName }
    }

    /** Só o resultado da análise mais recente aparece; as anteriores são canceladas. */
    private fun run(title: String, resolveFocus: () -> String?) {
        running?.cancel()
        val generation = ++generations
        updateStatus(null, "Varrendo as dependências…", Tone.BLUE.accent)
        object : Task.Backgroundable(project, title, true) {
            private var graph: DependencyGraph? = null
            private var target: String? = null

            override fun run(indicator: ProgressIndicator) {
                if (generation != generations) return
                running = indicator
                val name = resolveFocus() ?: return
                target = name
                graph = DependencyAnalyzer(project, indicator).analyze(name)
            }

            override fun onSuccess() {
                if (generation != generations) return
                val result = graph
                if (result == null) {
                    val text = if (target == null) "Nenhuma classe Java ou Kotlin na posição do cursor." else "Classe não encontrada no projeto."
                    updateStatus(null, text, Tone.YELLOW.accent)
                    return
                }
                show(result)
            }

            override fun onCancel() {
                if (generation == generations) updateStatus(null, "Análise cancelada.", Tone.GRAY.accent)
            }

            override fun onThrowable(error: Throwable) {
                if (error is ProcessCanceledException || generation != generations) return
                updateStatus(null, "Falha na análise: ${error.message ?: error.javaClass.simpleName}", Tone.RED.accent)
            }
        }.queue()
    }

    private fun show(graph: DependencyGraph) {
        diagram.show(graph)
        classModel.update(graph.nodes)
        updateStatus(graph, null)
    }

    private fun updateStatus(graph: DependencyGraph?, message: String?, color: Color? = null) {
        if (graph == null) {
            header.update(
                "Raio de Impacto",
                color,
                message ?: "Mire uma classe para ver quem depende dela e de quem ela depende.",
                stats = emptyList(),
            )
            return
        }
        header.update(
            DependencyAnalyzer.simpleName(graph.focus),
            null,
            graph.focus.substringBeforeLast('.', "").takeIf { it.isNotEmpty() } ?: "pacote padrão",
            stats = listOf(
                Stat(graph.nodes.size, "classes no raio", Tone.BLUE),
                Stat(graph.edges.size, if (graph.edges.size == 1) "ligação" else "ligações", Tone.GRAY),
            ),
            warning = if (graph.truncated) "O raio parou em ${DependencyAnalyzer.MAX_NODES} classes." else null,
        )
    }

    private fun onDiagramMessage(message: String) {
        val name = message.substringAfter(':')
        when (message.substringBefore(':')) {
            "open" -> open(name)
            "focus" -> analyze(name)
            "select" -> selectRow(name)
        }
    }

    private fun selectRow(qualifiedName: String) {
        val row = classModel.indexOf(qualifiedName).takeIf { it >= 0 } ?: return
        val viewRow = classTable.convertRowIndexToView(row)
        syncingSelection = true
        try {
            classTable.selectionModel.setSelectionInterval(viewRow, viewRow)
            classTable.scrollRectToVisible(classTable.getCellRect(viewRow, 0, true))
        } finally {
            syncingSelection = false
        }
    }

    /** Resolve a classe fora da EDT (em Kotlin isso pode parsear o arquivo) e navega quando pronto. */
    private fun open(qualifiedName: String) {
        ReadAction.nonBlocking(Callable {
            JavaPsiFacade.getInstance(project).findClass(qualifiedName, GlobalSearchScope.projectScope(project))
                ?.navigationElement as? Navigatable
        })
            .inSmartMode(project)
            .expireWith(this)
            .finishOnUiThread(ModalityState.defaultModalityState()) { target -> target?.takeIf { it.canNavigate() }?.navigate(true) }
            .submit(AppExecutorUtil.getAppExecutorService())
    }

    private fun installTableNavigation() {
        classTable.selectionModel.addListSelectionListener { event ->
            if (event.valueIsAdjusting || syncingSelection) return@addListSelectionListener
            val row = classTable.selectedRow.takeIf { it >= 0 } ?: return@addListSelectionListener
            diagram.select(classModel.nameAt(classTable.convertRowIndexToModel(row)))
        }
        classTable.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount < 2) return
                val row = classTable.rowAtPoint(e.point).takeIf { it >= 0 } ?: return
                open(classModel.nameAt(classTable.convertRowIndexToModel(row)))
            }
        })
    }

    private fun table(model: ClassTableModel) = JBTable(model).apply {
        emptyText.text = "Nenhuma classe no raio ainda"
        emptyText.appendSecondaryText("Mirar Classe Atual", SimpleTextAttributes.LINK_PLAIN_ATTRIBUTES) { analyzeCurrentEditor() }
        autoCreateRowSorter = true
        setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
        setShowGrid(false)
    }

    private class ClassTableModel : AbstractTableModel() {
        private val columns = listOf("Classe", "Pacote", "Aferentes (dependem dela)", "Eferentes (ela depende de)")
        private var rows: List<ClassNode> = emptyList()

        fun update(nodes: List<ClassNode>) {
            rows = nodes
            fireTableDataChanged()
        }

        fun indexOf(qualifiedName: String) = rows.indexOfFirst { it.qualifiedName == qualifiedName }

        fun nameAt(row: Int) = rows[row].qualifiedName

        override fun getRowCount() = rows.size
        override fun getColumnCount() = columns.size
        override fun getColumnName(column: Int) = columns[column]
        override fun getColumnClass(column: Int): Class<*> = if (column >= 2) Integer::class.java else String::class.java

        override fun getValueAt(row: Int, column: Int): Any = rows[row].let {
            when (column) {
                0 -> it.name
                1 -> it.packageName
                2 -> it.afferent
                else -> it.efferent
            }
        }
    }
}
