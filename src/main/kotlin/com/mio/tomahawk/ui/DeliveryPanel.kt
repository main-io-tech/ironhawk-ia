package com.mio.tomahawk.ui

import com.intellij.icons.AllIcons
import com.intellij.ide.BrowserUtil
import com.intellij.ide.ui.laf.darcula.ui.DarculaButtonUI
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.ui.components.panels.VerticalLayout
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.intellij.util.ui.WrapLayout
import com.mio.irondome.base.NoticeBox
import com.mio.irondome.base.Pill
import com.mio.irondome.base.Tone
import com.mio.irondome.base.WrapText
import com.mio.tomahawk.base.AttackTabs
import com.mio.tomahawk.delivery.DeliveryMode
import com.mio.tomahawk.delivery.DeliveryRequest
import com.mio.tomahawk.delivery.DeliveryWriter
import com.mio.tomahawk.delivery.PrRadioService
import com.mio.tomahawk.delivery.PullRequestDraft
import com.mio.tomahawk.delivery.PullRequestService
import com.mio.tomahawk.delivery.WriteTarget
import com.mio.tomahawk.run.TomahawkSettings
import com.mio.tomahawk.speckit.FeatureReport
import com.mio.tomahawk.speckit.SddStep
import com.mio.tomahawk.speckit.SpecKitLayout
import com.mio.tomahawk.speckit.SpecKitService
import com.mio.tomahawk.speckit.StepState
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.nio.file.Path
import javax.swing.DefaultComboBoxModel
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.event.DocumentEvent
import javax.swing.text.JTextComponent

/**
 * A Entrega (a Extração): commit, push e pull request, quando o projeto está num repositório do GitHub. Os campos
 * começam com o que sai da spec e das tarefas da feature que [featureOf] devolve (a escolhida no Lançador); o que o
 * usuário escreve fica até a entrega dar certo. Fica numa aba própria do Lançador; sem Git e GitHub, diz o que falta.
 */
class DeliveryPanel(
    private val project: Project,
    parent: Disposable,
    private val featureOf: () -> FeatureReport? = { SpecKitService.getInstance(project).snapshot.active },
) : JPanel(BorderLayout()) {

    private val pr = PullRequestService.getInstance(project)
    private val service = SpecKitService.getInstance(project)

    private val unavailable = WrapText(font = JBFont.label(), color = UIUtil.getContextHelpForeground())
    private val pills = wrapRow()
    private val notices = JPanel(VerticalLayout(JBUI.scale(6), VerticalLayout.FILL)).apply { isOpaque = false }
    private val mode = ComboBox(DefaultComboBoxModel(DeliveryMode.entries.toTypedArray())).apply {
        renderer = SimpleListCellRenderer.create("") { it.label }
        selectedItem = DeliveryMode.PULL_REQUEST
        addActionListener { if (!filling) update() }
    }
    private val branch = JBTextField()
    private val baseModel = DefaultComboBoxModel<String>()
    private val base = ComboBox(baseModel).apply { addActionListener { if (!filling) edited += BASE } }
    private val baseRow = labeled("Base do PR", base)
    private val branchHint = hint()
    private val commit = area(5)
    private val commitAi = aiLink(WriteTarget.COMMIT)
    private val changesLink = ActionLink("") { changesList.isVisible = !changesList.isVisible; revalidate() }
    private val changesList = WrapText(font = JBFont.small(), color = UIUtil.getContextHelpForeground()).apply { isVisible = false }
    private val commitBox = JPanel(VerticalLayout(JBUI.scale(4), VerticalLayout.FILL)).apply {
        isOpaque = false
        add(titleRow("Mensagem do commit", commitAi))
        add(ScrollPaneFactory.createScrollPane(commit))
        add(hint("A primeira linha é o título; o corpo vem depois de uma linha em branco."))
        add(changesLink)
        add(changesList)
    }
    private val title = JBTextField()
    private val body = area(8)
    private val bodyAi = aiLink(WriteTarget.PULL_REQUEST)
    private val prBox = JPanel(VerticalLayout(JBUI.scale(4), VerticalLayout.FILL)).apply {
        isOpaque = false
        add(labeled("Título do PR", title))
        add(titleRow("Descrição do PR", bodyAi))
        add(ScrollPaneFactory.createScrollPane(body))
    }
    private val draft = JBCheckBox("Abrir como rascunho")
    private val deliver = JButton("Commitar, enviar e abrir PR", AllIcons.Vcs.Push).apply {
        putClientProperty(DarculaButtonUI.DEFAULT_STYLE_KEY, true)
        addActionListener { deliver() }
    }
    private val form = JPanel(VerticalLayout(JBUI.scale(8), VerticalLayout.FILL)).apply {
        isOpaque = false
        add(labeled("O que fazer", mode))
        add(JPanel(VerticalLayout(JBUI.scale(2), VerticalLayout.FILL)).apply {
            isOpaque = false
            add(labeled("Branch", branch))
            add(branchHint)
        })
        add(baseRow)
        add(commitBox)
        add(prBox)
        add(wrapRow().apply {
            add(deliver)
            add(draft)
        })
    }

    /** Os campos que o usuário mudou: o redesenho não os troca pelos valores tirados da feature. */
    private val edited = mutableSetOf<String>()
    private var feature: Path? = null
    private var filling = false
    private var busy = false
    private var sentMode = DeliveryMode.PULL_REQUEST

    private val content = JPanel(VerticalLayout(JBUI.scale(8), VerticalLayout.FILL)).apply { isOpaque = false }

    init {
        isOpaque = false
        content.add(unavailable)
        content.add(pills)
        content.add(notices)
        content.add(form)
        add(content, BorderLayout.CENTER)
        track(branch, BRANCH)
        track(commit, COMMIT)
        track(title, TITLE)
        track(body, BODY)
        project.messageBus.connect(parent).subscribe(PullRequestService.CHANGED, PullRequestService.Listener {
            ApplicationManager.getApplication().invokeLater({ onDelivery() }, project.disposed)
        })
        project.messageBus.connect(parent).subscribe(PrRadioService.CHANGED, PrRadioService.Listener {
            ApplicationManager.getApplication().invokeLater({ update() }, project.disposed)
        })
        pr.refresh()
        update()
    }

    /** Depois de uma entrega sem erro, a mensagem do commit volta ao valor da feature; depois de um PR, os outros campos também. */
    private fun onDelivery() {
        val running = pr.step != null
        if (busy && !running && pr.lastError == null) {
            if (sentMode == DeliveryMode.PULL_REQUEST) {
                edited.clear()
                draft.isSelected = false
            } else {
                edited -= COMMIT
            }
        }
        busy = running
        update()
    }

    fun update() {
        val state = pr.state
        val local = state?.local
        // Na aba própria, sumir deixaria a aba em branco: o texto diz o que falta para a Entrega funcionar.
        unavailable.text = when {
            pr.gateway == null -> "A Entrega precisa dos plugins Git e GitHub da IDE. Ligue os dois em Settings > Plugins."
            state != null && local == null -> "A Entrega precisa de um repositório Git com remote do GitHub."
            else -> ""
        }
        unavailable.isVisible = unavailable.text.isNotEmpty()
        pills.removeAll()
        notices.removeAll()
        pills.isVisible = false
        notices.isVisible = false
        form.isVisible = false
        if (unavailable.isVisible) {
            revalidate()
            repaint()
            return
        }
        val active = featureOf()
        if (active?.dir != feature) {
            edited.clear()
            feature = active?.dir
        }
        if (local == null) {
            notices.add(WrapText("Lendo o repositório Git…", JBFont.label(), UIUtil.getContextHelpForeground()))
            notices.isVisible = true
            revalidate()
            repaint()
            return
        }
        form.isVisible = true
        pills.isVisible = true
        val remote = state.remote
        val open = remote?.openPr
        pills.add(Pill(local.repo, Tone.GRAY, bold = true))
        pills.add(Pill(local.branch ?: "sem branch", Tone.BLUE))
        pills.add(Pill("${local.changes.size} alteração(ões)", if (local.changes.isNotEmpty()) Tone.YELLOW else Tone.GRAY))
        pills.add(Pill("${local.ahead} commit(s) sem push", if (local.ahead > 0) Tone.BLUE else Tone.GRAY))
        pills.add(if (local.account != null) Pill("@${local.account}", Tone.GREEN) else Pill("GitHub: entrar", Tone.YELLOW))

        val chosen = mode.selectedItem as? DeliveryMode ?: DeliveryMode.PULL_REQUEST
        val opensPr = chosen == DeliveryMode.PULL_REQUEST
        if (local.account == null) {
            notices.add(notice(AllIcons.General.User, Tone.YELLOW, "Entre na conta do GitHub da IDE (${local.host}) para abrir PRs; commitar e enviar já funcionam.",
                button("Entrar no GitHub") { pr.login() }))
        } else {
            state.problem?.let { notices.add(notice(AllIcons.General.Error, Tone.RED, "O GitHub não respondeu: $it", button("Tentar de Novo") { pr.refresh(remote = true, delayMs = 0) })) }
            if (remote == null && pr.loading) notices.add(WrapText("Consultando o GitHub…", JBFont.label(), UIUtil.getContextHelpForeground()))
        }
        val step = pr.step
        val writing = pr.writing
        step?.let { notices.add(notice(AllIcons.Actions.Execute, Tone.BLUE, it)) }
        writing?.let { notices.add(notice(AllIcons.Actions.Lightning, Tone.BLUE, "A IA está escrevendo ${it.label}…")) }
        pr.lastError?.takeIf { step == null && writing == null }?.let { notices.add(notice(AllIcons.General.Error, Tone.RED, it)) }
        if (open != null) {
            val waiting = PrRadioService.getInstance(project).pendingCount(open.number)
            val text = "PR #${open.number} aberto: ${open.title} → ${open.base}" + (if (open.draft) " (rascunho)" else "") +
                if (waiting > 0) ". $waiting conversa(s) aguardando sua resposta." else ""
            notices.add(notice(AllIcons.General.InspectionsOK, Tone.GREEN, text, wrapRow().apply {
                add(button("Abrir no GitHub") { BrowserUtil.browse(open.url) })
                add(button(if (waiting > 0) "Ver os Comentários ($waiting)" else "Ver os Comentários") { AttackTabs.showTab(project, AttackTabs.RADIO) })
            }))
        }
        val converged = active != null && service.snapshot.statuses(active).any { it.step == SddStep.CONVERGE && it.state == StepState.DONE }
        if (opensPr && open == null && !converged) {
            notices.add(WrapText("A Avaliação de Danos ainda não convergiu: dá para abrir o PR como rascunho.", JBFont.small(), UIUtil.getContextHelpForeground()))
        }
        notices.isVisible = notices.componentCount > 0

        filling = true
        try {
            val specTitle = active?.spec?.file?.let { SpecKitLayout.read(it)?.lines() }?.let(PullRequestDraft::specTitle)
            if (BRANCH !in edited) branch.text = PullRequestDraft.branch(active, local.branch, remote?.defaultBranch)
            val currentBranch = branch.text.trim()
            branchHint.text = if (currentBranch != local.branch && currentBranch.isNotEmpty()) "Criada agora, a partir de ${local.branch ?: "HEAD"}." else ""
            branchHint.isVisible = branchHint.text.isNotEmpty()
            baseRow.isVisible = opensPr && open == null && remote != null
            if (remote != null) {
                val bases = (listOf(remote.defaultBranch, "develop") + remote.branches.sorted()).distinct().filter { it in remote.branches && it != currentBranch }
                val keep = base.selectedItem as? String
                if (baseModel.size != bases.size || (0 until baseModel.size).any { baseModel.getElementAt(it) != bases[it] }) {
                    baseModel.removeAllElements()
                    bases.forEach(baseModel::addElement)
                }
                base.selectedItem = if (BASE in edited && keep in bases) keep else PullRequestDraft.base(remote, currentBranch).takeIf { it in bases } ?: bases.firstOrNull()
            }
            val defaultTitle = PullRequestDraft.title(active, specTitle)
            if (TITLE !in edited) title.text = defaultTitle
            if (COMMIT !in edited) commit.text = title.text.ifBlank { defaultTitle }
            if (BODY !in edited) body.text = PullRequestDraft.body(active, specTitle) { service.snapshot.relative(it) }
        } finally {
            filling = false
        }
        commitBox.isVisible = local.changes.isNotEmpty()
        changesLink.text = "${local.changes.size} arquivo(s) no commit, fora ironhawk/ e .idea/"
        changesList.text = local.changes.take(MAX_FILES).joinToString("\n") + if (local.changes.size > MAX_FILES) "\ne mais ${local.changes.size - MAX_FILES}…" else ""
        prBox.isVisible = opensPr && open == null
        draft.isVisible = opensPr && open == null

        val aiWhy = when {
            step != null -> "Há uma entrega em andamento"
            writing != null -> "A IA já está escrevendo"
            !TomahawkSettings.ready -> "Teste a integração com a IA no Arsenal"
            else -> null
        }
        aiState(commitAi, WriteTarget.COMMIT, writing, aiWhy)
        aiState(bodyAi, WriteTarget.PULL_REQUEST, writing, aiWhy ?: if (selectedBase().isEmpty()) "Escolha a base do PR" else null)

        val changes = local.changes.isNotEmpty()
        deliver.text = when (chosen) {
            DeliveryMode.COMMIT -> "Commitar ${local.changes.size} arquivo(s)"
            DeliveryMode.PUSH -> if (changes) "Commitar e enviar" else "Enviar ${local.ahead} commit(s)"
            DeliveryMode.PULL_REQUEST -> when {
                open != null && changes -> "Commitar e enviar ao PR #${open.number}"
                open != null -> "Enviar ao PR #${open.number}"
                changes -> "Commitar, enviar e abrir PR"
                else -> "Enviar e abrir PR"
            }
        }
        val nothingToPush = !changes && local.ahead == 0 && local.upstream != null && currentBranch() == local.branch
        val why = when {
            step != null -> "Há uma entrega em andamento"
            writing != null -> "A IA está escrevendo o texto"
            chosen == DeliveryMode.COMMIT && !changes -> "Não há alterações para commitar"
            chosen == DeliveryMode.PUSH && nothingToPush -> "Não há alterações nem commits sem push"
            opensPr && (local.account == null || remote == null) -> if (local.account == null) "Entre na conta do GitHub para abrir o PR" else "O GitHub não respondeu"
            opensPr && open != null && nothingToPush -> "O PR já tem tudo: não há alterações nem commits sem push"
            else -> null
        }
        deliver.isEnabled = why == null
        deliver.toolTipText = why
        pills.revalidate()
        revalidate()
        repaint()
    }

    private fun currentBranch() = branch.text.trim()

    private fun selectedBase(): String = (base.selectedItem as? String).orEmpty().takeIf { baseRow.isVisible } ?: pr.state?.remote?.openPr?.base.orEmpty()

    private fun deliver() {
        val chosen = mode.selectedItem as? DeliveryMode ?: return
        sentMode = chosen
        pr.deliver(
            DeliveryRequest(
                branch = currentBranch(),
                base = selectedBase(),
                commitMessage = commit.text,
                title = title.text,
                body = body.text,
                draft = draft.isSelected,
                mode = chosen,
            ),
        )
    }

    private fun write(target: WriteTarget) {
        val active = featureOf()?.let { service.snapshot.relative(it.dir) }
        pr.write(target, selectedBase(), active) { text ->
            when (target) {
                WriteTarget.COMMIT -> {
                    commit.text = text
                    edited += COMMIT
                }
                WriteTarget.PULL_REQUEST -> {
                    val (newTitle, newBody) = DeliveryWriter.pullRequest(text)
                    if (newTitle.isNotBlank()) title.text = newTitle.also { edited += TITLE }
                    if (newBody.isNotBlank()) body.text = newBody.also { edited += BODY }
                }
            }
            update()
        }
    }

    private fun aiLink(target: WriteTarget) = ActionLink("Gerar com IA") { write(target) }.apply { setIcon(AllIcons.Actions.Lightning, false) }

    private fun aiState(link: ActionLink, target: WriteTarget, writing: WriteTarget?, why: String?) {
        link.text = if (writing == target) "Gerando…" else "Gerar com IA"
        link.isEnabled = why == null
        link.toolTipText = why ?: "A IA do Arsenal escreve ${target.label} a partir das alterações e do diff"
    }

    private fun track(field: JTextComponent, key: String) {
        field.document.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) {
                if (!filling) edited += key
            }
        })
    }

    private fun notice(icon: javax.swing.Icon, tone: Tone, text: String, action: JComponent? = null): JComponent {
        val box = NoticeBox(icon, tone).apply { this.text = text }
        if (action == null) return box
        return JPanel(VerticalLayout(JBUI.scale(4), VerticalLayout.FILL)).apply {
            isOpaque = false
            add(box)
            add(action)
        }
    }

    private fun button(text: String, action: () -> Unit) = JButton(text).apply { addActionListener { action() } }

    private fun labeled(label: String, field: JComponent) = JPanel(BorderLayout(0, JBUI.scale(3))).apply {
        isOpaque = false
        add(JBLabel(label).apply { font = JBFont.small().asBold(); foreground = UIUtil.getContextHelpForeground() }, BorderLayout.NORTH)
        add(field, BorderLayout.CENTER)
    }

    private fun titleRow(label: String, link: JComponent) = JPanel(BorderLayout()).apply {
        isOpaque = false
        add(JBLabel(label).apply { font = JBFont.small().asBold(); foreground = UIUtil.getContextHelpForeground() }, BorderLayout.WEST)
        add(link, BorderLayout.EAST)
    }

    private fun hint(text: String = "") = WrapText(text, JBFont.small(), UIUtil.getContextHelpForeground())

    private fun area(rows: Int) = JBTextArea(rows, 30).apply {
        lineWrap = true
        wrapStyleWord = true
        font = JBFont.label()
        minimumSize = Dimension(JBUI.scale(120), JBUI.scale(rows * 18))
    }

    private fun wrapRow() = JPanel(WrapLayout(FlowLayout.LEFT, JBUI.scale(6), JBUI.scale(3))).apply { isOpaque = false }

    private companion object {
        const val MAX_FILES = 40
        const val BRANCH = "branch"
        const val BASE = "base"
        const val COMMIT = "commit"
        const val TITLE = "title"
        const val BODY = "body"
    }
}
