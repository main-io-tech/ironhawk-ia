package com.mio.tomahawk.ui

import com.intellij.execution.filters.TextConsoleBuilderFactory
import com.intellij.execution.ui.ConsoleView
import com.intellij.execution.ui.ConsoleViewContentType
import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.DumbAwareToggleAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.util.Disposer
import com.intellij.ui.JBColor
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.panels.VerticalLayout
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.mio.irondome.base.FlowRow
import com.mio.irondome.base.MechanismList
import com.mio.irondome.base.Pill
import com.mio.irondome.base.Segment
import com.mio.irondome.base.Stat
import com.mio.irondome.base.SummaryHeader
import com.mio.irondome.base.Tone
import com.mio.irondome.base.WrapText
import com.mio.irondome.base.wrapping
import com.mio.tomahawk.base.AttackTabs
import com.mio.tomahawk.base.TomahawkIcons
import com.mio.tomahawk.run.LogKind
import com.mio.tomahawk.run.LogLine
import com.mio.tomahawk.run.MissionProgress
import com.mio.tomahawk.run.MissionRunner
import com.mio.tomahawk.run.RunRecord
import com.mio.tomahawk.run.RunStatus
import com.mio.tomahawk.run.Verdict
import com.mio.tomahawk.speckit.Snapshot
import com.mio.tomahawk.speckit.SpecKitService
import com.mio.tomahawk.view.ArtifactView
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Component
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.swing.DefaultListModel
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListCellRenderer
import javax.swing.ListSelectionModel
import javax.swing.Timer

/**
 * A aba Telemetria: a missão em voo, as durações das últimas execuções e o histórico por dia, com a situação, o
 * veredito e o tempo de cada uma. Embaixo, o log ao vivo no console da IDE ou o registro da execução selecionada,
 * formatado num navegador embutido que só é criado na primeira vez que um registro é escolhido; o duplo clique abre
 * o registro no editor.
 */
class TelemetryPanel(private val project: Project) : SimpleToolWindowPanel(true, true), Disposable {

    private val service = SpecKitService.getInstance(project)
    private val runner = MissionRunner.getInstance(project)
    private val header = SummaryHeader(TomahawkIcons.telemetria, Tone.BLUE)
    private val runsModel = DefaultListModel<RunRecord>()
    private val runs = MechanismList(runsModel, TomahawkIcons.telemetria, Tone.BLUE, fitWidth = true).apply {
        selectionMode = ListSelectionModel.SINGLE_SELECTION
        cellRenderer = RunRenderer()
        addListSelectionListener { if (!it.valueIsAdjusting) showSelected() }
        emptyText.text = "Nenhuma missão lançada neste projeto ainda."
        emptyText.appendLine("Abrir o Lançador", SimpleTextAttributes.LINK_PLAIN_ATTRIBUTES) { AttackTabs.showTab(project, AttackTabs.LAUNCHER) }
    }
    private val chart = DurationChart { pick(it) }
    private val chartTitle = JBLabel().apply {
        font = JBFont.small()
        foreground = UIUtil.getContextHelpForeground()
    }
    private val chartBox = JPanel(VerticalLayout(JBUI.scale(6), VerticalLayout.FILL)).apply {
        border = JBUI.Borders.compound(JBUI.Borders.customLineBottom(JBColor.border()), JBUI.Borders.empty(8, 14, 6, 14))
        add(chartTitle)
        add(chart)
    }
    private val viewerTitle = WrapText(font = JBFont.small().asBold()).apply { border = JBUI.Borders.empty(6, 10, 4, 10) }
    private val console: ConsoleView = TextConsoleBuilderFactory.getInstance().createBuilder(project).apply { setViewer(true) }.console

    /** O registro formatado: o navegador embutido nasce só quando o usuário escolhe um registro. */
    private var preview: ArtifactView? = null
    private val cards = CardLayout()
    private val bottomCards = JPanel(cards)
    private val timer = Timer(LIVE_TICK_MS) { if (runs.selectedValue == null) showLive() }

    private var onlyActive = false
    private val hidden = mutableSetOf<RunStatus>()
    private var dayCounts: Map<String, Int> = emptyMap()

    /** A missão cujo log está no console e quantas linhas dela já foram escritas. */
    private var shown: MissionProgress? = null
    private var printed = -1L

    init {
        Disposer.register(this, console)
        bottomCards.add(console.component, LIVE_CARD)
        val bottom = JPanel(BorderLayout()).apply {
            add(viewerTitle, BorderLayout.NORTH)
            add(bottomCards, BorderLayout.CENTER)
        }
        val splitter = OnePixelSplitter(true, 0.5f).apply {
            firstComponent = JPanel(BorderLayout()).apply {
                add(chartBox, BorderLayout.NORTH)
                add(ScrollPaneFactory.createScrollPane(runs, true), BorderLayout.CENTER)
            }
            secondComponent = bottom
        }
        setContent(JPanel(BorderLayout()).apply {
            add(JPanel(VerticalLayout(0)).apply {
                add(header)
                add(MissionProgressPanel(project, this@TelemetryPanel))
            }, BorderLayout.NORTH)
            add(splitter, BorderLayout.CENTER)
        })
        toolbar = ActionManager.getInstance().createActionToolbar(
            "Tomahawk.TelemetryToolbar",
            DefaultActionGroup(
                object : DumbAwareAction("Log ao Vivo", "Mostra o log da missão em voo, ou da última", AllIcons.Debugger.Console) {
                    override fun actionPerformed(e: AnActionEvent) {
                        runs.clearSelection()
                        showLive()
                    }
                },
                object : DumbAwareAction("Abrir Registro", "Abre o registro da execução selecionada no editor", AllIcons.Actions.EditSource) {
                    override fun actionPerformed(e: AnActionEvent) {
                        runs.selectedValue?.let { runner.open(it.file) }
                    }
                    override fun update(e: AnActionEvent) {
                        e.presentation.isEnabled = runs.selectedValue != null
                    }
                    override fun getActionUpdateThread() = ActionUpdateThread.EDT
                },
                Separator.getInstance(),
                filterActions(),
            ),
            true,
        ).apply { targetComponent = this@TelemetryPanel }.wrapping().component
        runs.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2) runs.selectedValue?.let { runner.open(it.file) }
            }
        })
        project.messageBus.connect(this).subscribe(SpecKitService.CHANGED, SpecKitService.Listener { reload(it) })
        project.messageBus.connect(this).subscribe(
            MissionRunner.RUNS,
            MissionRunner.Listener {
                ApplicationManager.getApplication().invokeLater({
                    if (runner.progress != null) runs.clearSelection()
                    updateHeader(service.snapshot)
                    if (runs.selectedValue == null) showLive()
                }, project.disposed)
            },
        )
        timer.start()
        Disposer.register(this) { timer.stop() }
        reload(service.snapshot)
        showLive()
    }

    override fun dispose() = Unit

    private fun filterActions() = DefaultActionGroup("Filtrar", true).apply {
        templatePresentation.icon = AllIcons.General.Filter
        templatePresentation.description = "Filtra as execuções pela situação ou pela feature ativa"
        RunStatus.entries.forEach { status ->
            add(object : DumbAwareToggleAction(status.label.replaceFirstChar { it.uppercase() }, "Mostra as execuções ${status.label}s", null) {
                override fun getActionUpdateThread() = ActionUpdateThread.EDT
                override fun isSelected(e: AnActionEvent) = status !in hidden
                override fun setSelected(e: AnActionEvent, state: Boolean) {
                    if (state) hidden -= status else hidden += status
                    reload(service.snapshot)
                }
            })
        }
        addSeparator()
        add(object : DumbAwareToggleAction("Só a Feature Ativa", "Mostra só as execuções da feature ativa", null) {
            override fun getActionUpdateThread() = ActionUpdateThread.EDT
            override fun isSelected(e: AnActionEvent) = onlyActive
            override fun setSelected(e: AnActionEvent, state: Boolean) {
                onlyActive = state
                reload(service.snapshot)
            }
        })
    }

    private fun records(snapshot: Snapshot) = if (onlyActive) snapshot.runsOf(snapshot.active) else snapshot.runs

    private fun reload(snapshot: Snapshot) {
        val selected = runs.selectedValue?.file
        val records = records(snapshot)
        val shownRuns = records.filter { it.status !in hidden }
        dayCounts = shownRuns.groupingBy { day(it.startedAt) }.eachCount()
        runsModel.clear()
        shownRuns.forEach(runsModel::addElement)
        if (records.isNotEmpty()) runs.emptyText.text = "Nenhuma execução com esse filtro."
        selected?.let { file -> shownRuns.indexOfFirst { it.file == file }.takeIf { it >= 0 }?.let { runs.selectedIndex = it } }
        val recent = records.take(CHART_SIZE).reversed()
        chart.runs = recent
        chartTitle.text = "Durações · últimas ${recent.size}"
        chartBox.isVisible = recent.isNotEmpty()
        updateHeader(snapshot)
    }

    private fun updateHeader(snapshot: Snapshot) {
        val records = records(snapshot)
        val counts = records.groupingBy { it.status }.eachCount()
        val flying = runner.progress
        val finished = records.filter { it.status == RunStatus.OK || it.status == RunStatus.FAILED }
        val ok = counts[RunStatus.OK] ?: 0
        val average = finished.takeIf { it.isNotEmpty() }?.map { it.durationSeconds }?.average()?.toLong()
        val title = when {
            flying != null -> "Em voo: ${flying.mission.step.codename}"
            records.isEmpty() -> "Sem telemetria ainda"
            else -> "Última: ${records.first().title.substringBefore(" (")} · ${records.first().status.label}"
        }
        val color = when {
            flying != null -> Tone.ORANGE
            records.firstOrNull()?.status == RunStatus.FAILED -> Tone.RED
            records.isEmpty() -> Tone.GRAY
            else -> Tone.GREEN
        }
        val subtitle = if (records.isEmpty()) {
            "Lance uma etapa no Lançador para acompanhar a missão aqui."
        } else {
            listOfNotNull(
                "${records.size} execução(ões)" + if (onlyActive) " da feature ativa" else "",
                finished.takeIf { it.isNotEmpty() }?.let { "taxa de sucesso ${ok * 100 / it.size}%" },
                average?.let { "duração média ${elapsed(it)}" },
                "convergiu".takeIf { records.any { it.verdict == Verdict.CONVERGED } },
            ).joinToString("  ·  ")
        }
        header.update(
            title,
            color.accent,
            subtitle,
            stats = listOf(
                Stat(ok, "concluídas", Tone.GREEN),
                Stat(counts[RunStatus.FAILED] ?: 0, "falharam", Tone.RED),
                Stat(counts[RunStatus.CANCELLED] ?: 0, "abortadas", Tone.GRAY),
                Stat(counts[RunStatus.TERMINAL] ?: 0, "no terminal", Tone.PURPLE),
            ),
            bar = RunStatus.entries.map { Segment(counts[it] ?: 0, it.tone.accent) },
        )
    }

    /** Uma barra do gráfico escolhe a execução na lista, mostrando todas se o filtro a escondia. */
    private fun pick(record: RunRecord) {
        if (record.status in hidden) {
            hidden -= record.status
            reload(service.snapshot)
        }
        val index = (0 until runsModel.size()).firstOrNull { runsModel[it].file == record.file } ?: return
        runs.selectedIndex = index
        runs.ensureIndexIsVisible(index)
    }

    /** O log da missão em voo (ou da última): só as linhas novas vão para o console, que guarda a rolagem. */
    private fun showLive() {
        cards.show(bottomCards, LIVE_CARD)
        chart.selected = null
        val progress = runner.progress ?: runner.latest
        if (progress !== shown || printed < 0 || (progress != null && progress.logged < printed)) {
            console.clear()
            shown = progress
            printed = 0L
            if (progress == null) {
                viewerTitle.text = "Log ao vivo"
                console.print("Nenhuma missão em voo. Escolha uma execução acima para ver o registro.\n", ConsoleViewContentType.SYSTEM_OUTPUT)
                return
            }
        }
        progress ?: return
        val prefix = if (progress === runner.progress) "Log ao vivo" else "Log da última missão"
        viewerTitle.text = "$prefix: ${progress.mission.step.codename} (${progress.invocation})"
        val (lines, total) = progress.logAfter(printed)
        lines.forEach(::print)
        printed = total
    }

    private fun print(line: LogLine) {
        val time = LocalDateTime.ofInstant(Instant.ofEpochMilli(line.at), ZoneId.systemDefault()).format(CLOCK)
        console.print("$time  ", ConsoleViewContentType.LOG_DEBUG_OUTPUT)
        val (tag, type) = when (line.kind) {
            LogKind.INFO -> "·" to ConsoleViewContentType.LOG_VERBOSE_OUTPUT
            LogKind.TOOL -> "→" to ConsoleViewContentType.LOG_INFO_OUTPUT
            LogKind.TEXT -> "IA" to ConsoleViewContentType.NORMAL_OUTPUT
            LogKind.ERROR -> "!!" to ConsoleViewContentType.ERROR_OUTPUT
            LogKind.RESULT -> "✓" to ConsoleViewContentType.SYSTEM_OUTPUT
        }
        console.print("$tag  ${line.text}\n", type)
    }

    private fun showSelected() {
        val record = runs.selectedValue ?: return
        chart.selected = record.file
        viewerTitle.text = "Registro: ${record.file.fileName}"
        val view = preview ?: ArtifactView(project, this).also {
            preview = it
            bottomCards.add(it, RUN_CARD)
        }
        cards.show(bottomCards, RUN_CARD)
        view.show(record.file)
    }

    private inner class RunRenderer : ListCellRenderer<RunRecord> {
        override fun getListCellRendererComponent(list: JList<out RunRecord>, value: RunRecord?, index: Int, selected: Boolean, focused: Boolean): Component {
            val panel = JPanel(VerticalLayout(JBUI.scale(2))).apply {
                isOpaque = true
                background = UIUtil.getListBackground(selected, focused)
                border = JBUI.Borders.empty(5, 10)
            }
            value ?: return panel
            val width = (list.width - JBUI.scale(20)).coerceAtLeast(JBUI.scale(120))
            val day = day(value.startedAt)
            val previous = if (index > 0) list.model.getElementAt(index - 1) else null
            if (previous == null || day(previous.startedAt) != day) {
                return JPanel(BorderLayout()).apply {
                    isOpaque = true
                    background = UIUtil.getListBackground()
                    add(JBLabel("$day · ${dayCounts[day] ?: 0}").apply {
                        font = JBFont.small().asBold()
                        foreground = UIUtil.getContextHelpForeground()
                        border = JBUI.Borders.compound(
                            if (index > 0) JBUI.Borders.customLineTop(JBColor.border()) else JBUI.Borders.empty(),
                            JBUI.Borders.empty(6, 10, 2, 10),
                        )
                    }, BorderLayout.NORTH)
                    add(fill(panel, value, width, selected, focused), BorderLayout.CENTER)
                }
            }
            return fill(panel, value, width, selected, focused)
        }

        private fun fill(panel: JPanel, value: RunRecord, width: Int, selected: Boolean, focused: Boolean): JPanel {
            val foreground = UIUtil.getListForeground(selected, focused)
            val muted = if (selected) foreground else UIUtil.getContextHelpForeground()
            val line = FlowRow(width)
            line.add(Pill(value.status.label, value.status.tone, bold = true))
            value.verdict?.let { line.add(Pill(it.label, it.tone)) }
            line.add(WrapText(value.title, JBFont.label().asBold(), foreground))
            panel.add(line)
            val meta = listOfNotNull(
                time(value.startedAt),
                value.feature?.substringAfterLast('/'),
                elapsed(value.durationSeconds).takeIf { value.status != RunStatus.TERMINAL },
                value.provider,
                "${value.filesChanged} arquivo(s)".takeIf { value.filesChanged > 0 },
            ).joinToString("  ·  ")
            panel.add(WrapText(meta, JBFont.small(), muted).fit(width))
            value.error?.let { panel.add(WrapText(it.take(300), JBFont.small(), if (selected) foreground else Tone.RED.foreground).fit(width)) }
            return panel
        }
    }

    private fun day(millis: Long): String {
        if (millis <= 0) return "Sem data"
        val date = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDate()
        val today = LocalDate.now()
        return when (date) {
            today -> "Hoje"
            today.minusDays(1) -> "Ontem"
            else -> date.format(DAY)
        }
    }

    private fun time(millis: Long): String? =
        if (millis <= 0) null else LocalDateTime.ofInstant(Instant.ofEpochMilli(millis), ZoneId.systemDefault()).format(HOUR)

    private companion object {
        const val LIVE_TICK_MS = 700
        const val CHART_SIZE = 30
        const val LIVE_CARD = "live"
        const val RUN_CARD = "run"
        val CLOCK: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")
        val HOUR: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
        val DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM/yyyy")
    }
}
