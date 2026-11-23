package com.mio.tomahawk.ui

import com.intellij.icons.AllIcons
import com.intellij.ide.BrowserUtil
import com.intellij.ide.CommonActionsManager
import com.intellij.ide.DefaultTreeExpander
import com.intellij.ide.ui.laf.darcula.ui.DarculaButtonUI
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
import com.intellij.ui.ColorUtil
import com.intellij.ui.JBColor
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.panels.VerticalLayout
import com.intellij.util.ui.HTMLEditorKitBuilder
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.intellij.util.ui.WrapLayout
import com.intellij.util.ui.tree.TreeUtil
import com.mio.irondome.base.FitWidthPanel
import com.mio.irondome.base.MechanismTree
import com.mio.irondome.base.NoticeBox
import com.mio.irondome.base.Pill
import com.mio.irondome.base.RichTreeCellRenderer
import com.mio.irondome.base.Segment
import com.mio.irondome.base.Stat
import com.mio.irondome.base.SummaryHeader
import com.mio.irondome.base.Tone
import com.mio.irondome.base.WrapText
import com.mio.irondome.base.moreActions
import com.mio.irondome.base.wrapping
import com.mio.tomahawk.base.AttackTabs
import com.mio.tomahawk.base.TomahawkIcons
import com.mio.tomahawk.delivery.PrRadioService
import com.mio.tomahawk.delivery.PrThread
import com.mio.tomahawk.delivery.PullRequestService
import com.mio.tomahawk.delivery.RadioSnapshot
import com.mio.tomahawk.delivery.ThreadKind
import com.mio.tomahawk.delivery.WatchedPr
import com.mio.tomahawk.run.TomahawkSettings
import com.mio.tomahawk.view.ArtifactHtml
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.swing.JButton
import javax.swing.JEditorPane
import javax.swing.JPanel
import javax.swing.event.HyperlinkEvent
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath

/**
 * A aba Rádio: os PRs abertos pelo usuário e as conversas de cada um, com o que é novo e o que espera resposta.
 * Escolhida uma conversa, embaixo aparecem o trecho do diff, os comentários e a caixa de resposta, que a IA pode
 * escrever a partir da conversa e do código. O [PrRadioService] relê os PRs a cada 3 minutos; a barra mostra o fluxo
 * principal e o resto fica atrás do ⋮.
 */
class RadioPanel(private val project: Project) : SimpleToolWindowPanel(true, true), Disposable {

    private enum class Filter(val label: String, val description: String) {
        PENDING("Aguardando Resposta", "Só as conversas em que a última palavra é de outra pessoa"),
        OTHERS("De Outros Devs", "Só as conversas com comentários de outras pessoas"),
        ALL("Todas", "Todas as conversas dos PRs"),
    }

    private class PrNode(val pr: WatchedPr)

    private class ThreadNode(val pr: WatchedPr, val thread: PrThread)

    private val radio = PrRadioService.getInstance(project)
    private val header = SummaryHeader(TomahawkIcons.radio, Tone.PURPLE)
    private val root = DefaultMutableTreeNode()
    private val model = DefaultTreeModel(root)
    private val tree = MechanismTree(model, TomahawkIcons.radio, Tone.PURPLE).apply {
        isRootVisible = false
        showsRootHandles = true
        cellRenderer = Renderer()
        addTreeSelectionListener { showSelected() }
    }
    private var filter = Filter.PENDING
    /** O que o usuário escreveu em cada conversa, pela chave dela. */
    private val replies = mutableMapOf<String, String>()

    private val threadTitle = WrapText(font = JBFont.label().asBold())
    private val threadPills = JPanel(WrapLayout(FlowLayout.LEFT, JBUI.scale(6), JBUI.scale(3))).apply { isOpaque = false }
    private val fileLink = ActionLink("") { selectedThread()?.let { (_, thread) -> openPath(thread) } }
    private val hunk = HunkView(project).also { Disposer.register(this, it) }
    private val comments = JEditorPane().apply {
        editorKit = HTMLEditorKitBuilder().withWordWrapViewFactory().build()
        isEditable = false
        isOpaque = false
        // Os comentários são de terceiros: só links da web abrem, nunca `file:` nem outro esquema.
        addHyperlinkListener { event ->
            if (event.eventType == HyperlinkEvent.EventType.ACTIVATED) {
                event.url?.takeIf { it.protocol == "http" || it.protocol == "https" }?.let(BrowserUtil::browse)
            }
        }
    }
    private val status = NoticeBox(AllIcons.General.Information, Tone.BLUE).apply { isVisible = false }
    private val reply = JBTextArea(5, 40).apply {
        lineWrap = true
        wrapStyleWord = true
        font = JBFont.label()
        emptyText.text = "Escreva a resposta, ou orientações para a IA"
    }
    private val aiButton = JButton("Responder com IA", AllIcons.Actions.Lightning).apply { addActionListener { draftWithAi() } }
    private val seenButton = JButton("Marcar como Lida", AllIcons.Actions.Checked).apply {
        addActionListener { selectedThread()?.let { (_, thread) -> radio.markSeen(listOf(thread)) } }
    }
    private val sendButton = JButton("Enviar Resposta", AllIcons.Actions.Execute).apply {
        putClientProperty(DarculaButtonUI.DEFAULT_STYLE_KEY, true)
        addActionListener { send() }
    }
    private val detail = JPanel(VerticalLayout(JBUI.scale(8), VerticalLayout.FILL)).apply {
        border = JBUI.Borders.empty(10, 14)
        add(threadTitle)
        add(threadPills)
        add(fileLink)
        add(hunk)
        add(comments)
        add(status)
        add(ScrollPaneFactory.createScrollPane(reply))
        add(JPanel(WrapLayout(FlowLayout.LEFT, JBUI.scale(6), 0)).apply {
            isOpaque = false
            add(sendButton)
            add(aiButton)
            add(seenButton)
        })
    }
    private val empty = WrapText(
        "Escolha uma conversa acima para ver o código, os comentários e responder.",
        JBFont.label(),
        UIUtil.getContextHelpForeground(),
    ).apply { border = JBUI.Borders.empty(12, 14) }
    private val bottom = JPanel(BorderLayout())

    init {
        val splitter = OnePixelSplitter(true, 0.42f).apply {
            firstComponent = ScrollPaneFactory.createScrollPane(tree, true)
            secondComponent = bottom
        }
        setContent(JPanel(BorderLayout()).apply {
            add(header, BorderLayout.NORTH)
            add(splitter, BorderLayout.CENTER)
        })
        val expander = DefaultTreeExpander(tree)
        val actions = CommonActionsManager.getInstance()
        toolbar = ActionManager.getInstance().createActionToolbar(
            "Tomahawk.RadioToolbar",
            DefaultActionGroup(
                object : DumbAwareAction("Abrir no GitHub", "Abre o PR ou o comentário selecionado no GitHub", AllIcons.Ide.External_link_arrow) {
                    override fun actionPerformed(e: AnActionEvent) {
                        selectedUrl()?.let(BrowserUtil::browse)
                    }
                    override fun update(e: AnActionEvent) {
                        e.presentation.isEnabled = selectedUrl() != null
                    }
                    override fun getActionUpdateThread() = ActionUpdateThread.EDT
                },
                object : DumbAwareAction("Marcar Tudo como Lido", "Marca como lidos os comentários de todos os PRs", AllIcons.Actions.Checked) {
                    override fun actionPerformed(e: AnActionEvent) {
                        radio.snapshot?.let { snap -> radio.markSeen(snap.prs.flatMap { it.threads }) }
                    }
                    override fun update(e: AnActionEvent) {
                        val snap = radio.snapshot
                        e.presentation.isEnabled = snap != null && snap.prs.any { pr -> pr.threads.any { radio.unread(it, snap.me) > 0 } }
                    }
                    override fun getActionUpdateThread() = ActionUpdateThread.EDT
                },
                Separator.getInstance(),
                filterActions(),
                object : DumbAwareAction("Entrar no GitHub", "Entra na conta do GitHub da IDE", AllIcons.General.User) {
                    override fun actionPerformed(e: AnActionEvent) = login()
                    override fun update(e: AnActionEvent) {
                        e.presentation.isVisible = radio.gateway != null && radio.snapshot == null && !radio.loading
                    }
                    override fun getActionUpdateThread() = ActionUpdateThread.EDT
                },
                moreActions(
                    object : DumbAwareAction("Ler os PRs de Novo", "Lê de novo os PRs e os comentários no GitHub, sem esperar os 3 minutos", AllIcons.Vcs.Fetch) {
                        override fun actionPerformed(e: AnActionEvent) = radio.refresh()
                        override fun update(e: AnActionEvent) {
                            e.presentation.isEnabled = radio.gateway != null && !radio.loading
                        }
                        override fun getActionUpdateThread() = ActionUpdateThread.EDT
                    },
                    null,
                    actions.createExpandAllAction(expander, tree),
                    actions.createCollapseAllAction(expander, tree),
                ),
            ),
            true,
        ).apply { targetComponent = this@RadioPanel }.wrapping().component
        tree.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2) selectedThread()?.let { (_, thread) -> openPath(thread) }
            }
        })
        project.messageBus.connect(this).subscribe(
            PrRadioService.CHANGED,
            PrRadioService.Listener { ApplicationManager.getApplication().invokeLater({ reload() }, project.disposed) },
        )
        ApplicationManager.getApplication().messageBus.connect(this).subscribe(
            TomahawkSettings.Listener.TOPIC,
            TomahawkSettings.Listener { ApplicationManager.getApplication().invokeLater({ updateButtons() }, project.disposed) },
        )
        if (radio.snapshot == null && !radio.loading) radio.refresh()
        reload()
    }

    override fun dispose() = Unit

    private fun login() {
        PullRequestService.getInstance(project).login()
        radio.refresh()
    }

    private fun filterActions() = DefaultActionGroup("Filtrar", true).apply {
        templatePresentation.icon = AllIcons.General.Filter
        templatePresentation.description = "Escolhe quais conversas aparecem"
        Filter.entries.forEach { option ->
            add(object : DumbAwareToggleAction(option.label, option.description, null) {
                override fun getActionUpdateThread() = ActionUpdateThread.EDT
                override fun isSelected(e: AnActionEvent) = filter == option
                override fun setSelected(e: AnActionEvent, state: Boolean) {
                    if (!state || filter == option) return
                    filter = option
                    reload()
                }
            })
        }
    }

    private fun shown(thread: PrThread, me: String) = when (filter) {
        Filter.PENDING -> thread.awaitsReply(me)
        Filter.OTHERS -> thread.fromOthers(me).isNotEmpty()
        Filter.ALL -> true
    }

    private fun reload() {
        val selectedKey = selectedThread()?.second?.key
        val selectedPr = (selectedNode() as? PrNode)?.pr?.ref?.number
        val firstLoad = root.childCount == 0
        val collapsed = (0 until root.childCount).map { root.getChildAt(it) as DefaultMutableTreeNode }
            .filter { !tree.isExpanded(TreePath(it.path)) }
            .mapNotNull { (it.userObject as? PrNode)?.pr?.ref?.number }.toSet()
        root.removeAllChildren()
        val snap = radio.snapshot
        snap?.prs?.forEach { pr ->
            val node = DefaultMutableTreeNode(PrNode(pr))
            pr.threads.filter { shown(it, snap.me) }.forEach { node.add(DefaultMutableTreeNode(ThreadNode(pr, it))) }
            root.add(node)
        }
        tree.emptyText.clear()
        when {
            radio.gateway == null -> tree.emptyText.text = "Os plugins Git e GitHub da IDE estão desligados."
            snap == null && radio.loading -> tree.emptyText.text = "Sintonizando os PRs no GitHub…"
            snap == null -> {
                tree.emptyText.text = "Sem PRs: o projeto precisa de remote do GitHub e da conta da IDE."
                tree.emptyText.appendLine("Entrar no GitHub", SimpleTextAttributes.LINK_PLAIN_ATTRIBUTES) { login() }
            }
            snap.prs.isEmpty() -> {
                tree.emptyText.text = "Nenhum PR aberto por você."
                tree.emptyText.appendLine("Abrir a Entrega no Lançador", SimpleTextAttributes.LINK_PLAIN_ATTRIBUTES) {
                    AttackTabs.showTab(project, AttackTabs.LAUNCHER)
                }
            }
            else -> tree.emptyText.text = "Nenhuma conversa com esse filtro."
        }
        model.reload()
        (0 until root.childCount).map { root.getChildAt(it) as DefaultMutableTreeNode }.forEach { node ->
            val number = (node.userObject as PrNode).pr.ref.number
            if (firstLoad || number !in collapsed) tree.expandPath(TreePath(node.path))
        }
        val again = TreeUtil.treeNodeTraverser(root).filter(DefaultMutableTreeNode::class.java).firstOrNull { node ->
            when (val value = node.userObject) {
                is ThreadNode -> value.thread.key == selectedKey
                is PrNode -> selectedKey == null && value.pr.ref.number == selectedPr
                else -> false
            }
        }
        if (again != null) tree.selectionPath = TreePath(again.path) else showSelected()
        updateHeader(snap)
    }

    private fun updateHeader(snap: RadioSnapshot?) {
        if (radio.gateway == null) {
            header.update("Comentários dos seus PRs", Tone.GRAY.accent, "Ligue os plugins Git e GitHub da IDE para usar o Rádio.")
            return
        }
        if (snap == null) {
            header.update(
                "Comentários dos seus PRs",
                Tone.GRAY.accent,
                if (radio.loading) "Sintonizando os PRs no GitHub…" else "Entre na conta do GitHub da IDE para vigiar os seus PRs.",
                warning = radio.problem?.let { "O GitHub não respondeu: $it" },
            )
            return
        }
        val pending = snap.prs.sumOf { pr -> pr.threads.count { it.awaitsReply(snap.me) } }
        val unread = snap.prs.sumOf { pr -> pr.threads.sumOf { radio.unread(it, snap.me) } }
        val resolved = snap.prs.sumOf { pr -> pr.threads.count { it.resolved } }
        val total = snap.prs.sumOf { it.threads.size }
        val checked = if (radio.checkedAt > 0) " · checado às ${TIME.format(Instant.ofEpochMilli(radio.checkedAt).atZone(ZoneId.systemDefault()))}" else ""
        header.update(
            if (pending > 0) "$pending conversa(s) aguardando sua resposta" else "Nenhuma conversa aguardando resposta",
            (if (pending > 0) Tone.YELLOW else Tone.GREEN).accent,
            "@${snap.me} · ${snap.prs.size} PR(s) abertos em ${snap.repo}$checked" + if (radio.loading) " · atualizando…" else "",
            stats = listOf(
                Stat(unread, "novos", Tone.PURPLE),
                Stat(pending, "aguardando", Tone.YELLOW),
                Stat(resolved, "resolvidas", Tone.GREEN),
            ),
            bar = listOf(
                Segment(pending, Tone.YELLOW.accent),
                Segment(resolved, Tone.GREEN.accent),
                Segment(total - pending - resolved, Tone.GRAY.accent),
            ),
            warning = radio.problem?.let { "A última leitura falhou: $it" }
                ?: if (!TomahawkSettings.ready) "Teste a integração com a IA no Arsenal para responder com IA." else null,
        )
    }

    private fun selectedNode(): Any? = (tree.lastSelectedPathComponent as? DefaultMutableTreeNode)?.userObject

    private fun selectedThread(): Pair<WatchedPr, PrThread>? = (selectedNode() as? ThreadNode)?.let { it.pr to it.thread }

    private fun selectedUrl(): String? = when (val node = selectedNode()) {
        is PrNode -> node.pr.ref.url
        is ThreadNode -> node.thread.last.url.ifBlank { node.pr.ref.url }
        else -> null
    }?.takeIf { it.isNotBlank() }

    private var shownKey: String? = null

    private fun showSelected() {
        shownKey?.let { replies[it] = reply.text }
        val selected = selectedThread()
        val snap = radio.snapshot
        bottom.removeAll()
        if (selected == null || snap == null) {
            shownKey = null
            bottom.add(empty, BorderLayout.NORTH)
            bottom.revalidate()
            bottom.repaint()
            return
        }
        val (_, thread) = selected
        val switched = shownKey != thread.key
        shownKey = thread.key
        threadTitle.text = thread.kind.label + (thread.reviewLabel?.let { ": $it" }.orEmpty()) + " · @${thread.last.author}"
        threadPills.removeAll()
        val unread = radio.unread(thread, snap.me)
        if (unread > 0) threadPills.add(Pill("$unread novo(s)", Tone.PURPLE, bold = true))
        if (thread.awaitsReply(snap.me)) threadPills.add(Pill("Aguardando resposta", Tone.YELLOW))
        if (thread.resolved) threadPills.add(Pill("Resolvida", Tone.GREEN))
        if (thread.outdated) threadPills.add(Pill("Desatualizada", Tone.GRAY))
        threadPills.add(Pill(date(thread.last.createdAt), Tone.GRAY))
        threadPills.isVisible = threadPills.componentCount > 0
        fileLink.isVisible = thread.path != null
        fileLink.text = thread.path?.let { it + thread.line?.let { line -> ":$line" }.orEmpty() }.orEmpty()
        fileLink.toolTipText = "Abrir no editor"
        if (switched) hunk.show(thread.diffHunk?.takeIf { thread.kind == ThreadKind.CODE }, thread.path)
        comments.text = commentsHtml(thread, snap.me)
        comments.caretPosition = 0
        if (switched) reply.text = replies[thread.key].orEmpty()
        updateButtons()
        bottom.add(ScrollPaneFactory.createScrollPane(FitWidthPanel(detail), true), BorderLayout.CENTER)
        bottom.revalidate()
        bottom.repaint()
    }

    private fun updateButtons() {
        val (_, thread) = selectedThread() ?: return
        val me = radio.snapshot?.me ?: return
        val drafting = thread.key in radio.drafting
        val sending = thread.key in radio.sending
        val error = radio.errors[thread.key]
        status.isVisible = drafting || error != null
        status.text = error ?: "A IA está lendo a conversa e o código para escrever a resposta…"
        aiButton.text = if (drafting) "Escrevendo…" else "Responder com IA"
        aiButton.isEnabled = !drafting && !sending && TomahawkSettings.ready
        aiButton.toolTipText = when {
            !TomahawkSettings.ready -> "Teste a integração com a IA no Arsenal"
            else -> "A IA escreve a resposta; o texto da caixa vira a orientação dela"
        }
        seenButton.isEnabled = radio.unread(thread, me) > 0
        sendButton.text = if (sending) "Enviando…" else "Enviar Resposta"
        sendButton.isEnabled = !sending && !drafting
        sendButton.toolTipText = if (thread.kind == ThreadKind.CODE) "Responde na conversa da linha" else "Responde na conversa do PR, citando @${thread.last.author}"
        reply.isEditable = !drafting && !sending
    }

    private fun draftWithAi() {
        val (pr, thread) = selectedThread() ?: return
        val notes = reply.text
        replies[thread.key] = notes
        radio.draftReply(pr.ref.number, thread.key, notes) { answer ->
            replies[thread.key] = answer
            if (shownKey == thread.key) reply.text = answer
        }
        updateButtons()
    }

    private fun send() {
        val (pr, thread) = selectedThread() ?: return
        val text = reply.text
        if (text.isBlank()) {
            status.isVisible = true
            status.text = "Escreva a resposta antes de enviar."
            return
        }
        replies[thread.key] = text
        radio.send(pr.ref.number, thread.key, text) {
            replies.remove(thread.key)
            if (shownKey == thread.key) reply.text = ""
        }
        updateButtons()
    }

    private fun openPath(thread: PrThread) {
        val base = project.basePath ?: return
        val path = thread.path ?: return
        openFile(project, Path.of(base).resolve(path), ((thread.line ?: 1) - 1).coerceAtLeast(0))
    }

    /** Os comentários da conversa, com o autor, a data e o Markdown formatado; os novos e os do usuário com uma marca. */
    private fun commentsHtml(thread: PrThread, me: String): String = buildString {
        val muted = ColorUtil.toHtmlColor(UIUtil.getContextHelpForeground())
        val line = ColorUtil.toHtmlColor(JBColor.border())
        val mine = ColorUtil.toHtmlColor(Tone.BLUE.foreground)
        val fresh = ColorUtil.toHtmlColor(Tone.PURPLE.foreground)
        append("<html><body style=\"margin:0\">")
        thread.comments.forEachIndexed { index, comment ->
            val own = comment.author.equals(me, ignoreCase = true)
            if (index > 0) append("<div style=\"border-top:1px solid $line;margin:8px 0 0 0;padding-top:8px\"></div>")
            append("<div><b>@").append(ArtifactHtml.esc(comment.author)).append("</b>")
            if (own) append(" <span style=\"color:$mine\">· você</span>")
            if (radio.isNew(comment, me)) append(" <span style=\"color:$fresh\"><b>· novo</b></span>")
            append(" <span style=\"color:$muted\">· ").append(ArtifactHtml.esc(date(comment.createdAt))).append("</span>")
            comment.url.takeIf { it.isNotBlank() }?.let { append(" <a href=\"").append(ArtifactHtml.esc(it)).append("\">↗ GitHub</a>") }
            append("</div><div>")
            append(if (comment.body.isBlank()) "<i>(sem texto)</i>" else ArtifactHtml.toHtml(comment.body))
            append("</div>")
        }
        append("</body></html>")
    }

    private fun date(iso: String): String =
        runCatching { DATE.format(Instant.parse(iso).atZone(ZoneId.systemDefault())) }.getOrDefault(iso)

    private fun peek(body: String): String {
        val text = body.replace(Regex("```[\\s\\S]*?(```|$)"), " [código] ").replace(Regex("[`*_>#~]+"), "").replace(Regex("\\s+"), " ").trim()
        return when {
            text.isEmpty() -> "(sem texto)"
            text.length > PEEK_CHARS -> text.take(PEEK_CHARS).trimEnd() + "…"
            else -> text
        }
    }

    private inner class Renderer : RichTreeCellRenderer() {
        override fun describe(row: Row, value: Any?) {
            val me = radio.snapshot?.me.orEmpty()
            when (val node = (value as? DefaultMutableTreeNode)?.userObject) {
                is PrNode -> {
                    val pr = node.pr
                    val unread = pr.threads.sumOf { radio.unread(it, me) }
                    val pending = pr.threads.count { it.awaitsReply(me) }
                    row.icon(AllIcons.Vcs.Vendors.Github)
                    row.text("#${pr.ref.number}", SimpleTextAttributes.GRAYED_BOLD_ATTRIBUTES)
                    row.text(pr.ref.title, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
                    if (pr.ref.draft) row.pill("Rascunho", Tone.GRAY)
                    if (unread > 0) row.pill("$unread novo(s)", Tone.PURPLE, bold = true)
                    row.pill(if (pending > 0) "$pending aguardando" else "Em dia", if (pending > 0) Tone.YELLOW else Tone.GREEN)
                    row.detail("${pr.head} → ${pr.ref.base} · ${pr.threads.size} conversa(s)")
                }
                is ThreadNode -> {
                    val thread = node.thread
                    row.icon(
                        when (thread.kind) {
                            ThreadKind.CODE -> AllIcons.Actions.Edit
                            ThreadKind.CONVERSATION -> AllIcons.General.Balloon
                            ThreadKind.REVIEW -> AllIcons.Actions.Preview
                        },
                    )
                    row.text(thread.kind.label + (thread.reviewLabel?.let { ": $it" }.orEmpty()), SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
                    thread.path?.let { row.text(it.substringAfterLast('/') + thread.line?.let { line -> ":$line" }.orEmpty(), SimpleTextAttributes.GRAYED_ATTRIBUTES) }
                    val unread = radio.unread(thread, me)
                    if (unread > 0) row.pill("$unread novo(s)", Tone.PURPLE, bold = true)
                    if (thread.awaitsReply(me)) row.pill("Aguardando", Tone.YELLOW)
                    if (thread.resolved) row.pill("Resolvida", Tone.GREEN)
                    if (thread.outdated) row.pill("Desatualizada", Tone.GRAY)
                    if (thread.key in radio.drafting) row.pill("IA escrevendo", Tone.BLUE)
                    row.detail("@${thread.last.author}: ${peek(thread.last.body)} · ${date(thread.last.createdAt)}")
                    row.tooltip(thread.path)
                }
            }
        }
    }

    private companion object {
        const val PEEK_CHARS = 140
        val DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM HH:mm")
        val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
    }
}
