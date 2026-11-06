package com.mio.tomahawk.ui.web

import com.google.gson.JsonObject
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.mio.tomahawk.base.AttackTabs
import com.mio.tomahawk.delivery.DeliveryMode
import com.mio.tomahawk.delivery.DeliveryRequest
import com.mio.tomahawk.delivery.DeliveryWriter
import com.mio.tomahawk.delivery.PrRadarService
import com.mio.tomahawk.delivery.PullRequestDraft
import com.mio.tomahawk.delivery.PullRequestService
import com.mio.tomahawk.delivery.WriteTarget
import com.mio.tomahawk.run.LaunchTerminal
import com.mio.tomahawk.run.Mission
import com.mio.tomahawk.run.MissionRunner
import com.mio.tomahawk.run.RunMode
import com.mio.tomahawk.run.SpecKitSetup
import com.mio.tomahawk.run.TomahawkSettings
import com.mio.tomahawk.speckit.FeatureReport
import com.mio.tomahawk.speckit.SddStep
import com.mio.tomahawk.speckit.Snapshot
import com.mio.tomahawk.speckit.SpecKitLayout
import com.mio.tomahawk.speckit.SpecKitService
import com.mio.tomahawk.speckit.StepState
import com.mio.tomahawk.speckit.StepStatus
import com.mio.tomahawk.ui.openArtifact
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.Timer

/**
 * O Lançador na versão web: o cabeçalho da missão com o anel das etapas, as features em cartões e a linha do tempo
 * das nove etapas, com a etapa escolhida aberta para escrever a entrada e lançar.
 */
class LauncherWeb(project: Project) : WebTab(project) {

    override val accent = "orange"

    private val service = SpecKitService.getInstance(project)
    private val runner = MissionRunner.getInstance(project)
    private var selected: SddStep? = null
    private var newFeature = false
    private var lastActive: Path? = null
    private val drafts = mutableMapOf<SddStep, String>()
    private val modes = mutableMapOf<SddStep, RunMode>()
    private val timer = Timer(TICK_MS) { runner.progress?.let { js("tmk.flight(${Flight.state(it)})") } }
    private val pr = PullRequestService.getInstance(project)
    /** O que o usuário escreveu no PR (branch, base, título, descrição, commit), por cima dos valores tirados da feature. */
    private val prFields = mutableMapOf<String, String>()
    private var prDraft = false
    private var prFeature: Path? = null
    private var prBusy = false
    private var prMode = DeliveryMode.PULL_REQUEST
    /** O modo da última entrega enviada: só a de PR usa o título e a descrição. */
    private var prSent = DeliveryMode.PULL_REQUEST

    init {
        project.messageBus.connect(this).subscribe(SpecKitService.CHANGED, SpecKitService.Listener { onSnapshot() })
        project.messageBus.connect(this).subscribe(PullRequestService.CHANGED, PullRequestService.Listener { onDelivery() })
        project.messageBus.connect(this).subscribe(PrRadarService.CHANGED, PrRadarService.Listener { refresh() })
        project.messageBus.connect(this).subscribe(
            MissionRunner.RUNS,
            MissionRunner.Listener { ApplicationManager.getApplication().invokeLater({ updateTimer(); refresh() }, project.disposed) },
        )
        project.messageBus.connect(this).subscribe(
            AttackTabs.GOTO,
            AttackTabs.GotoListener { step ->
                selected = step
                if (step == SddStep.SPECIFY && service.snapshot.active?.spec != null) newFeature = true
                refresh(force = true)
            },
        )
        ApplicationManager.getApplication().messageBus.connect(this).subscribe(
            TomahawkSettings.Listener.TOPIC,
            TomahawkSettings.Listener { ApplicationManager.getApplication().invokeLater({ refresh() }, project.disposed) },
        )
        ApplicationManager.getApplication().messageBus.connect(this).subscribe(
            SpecKitSetup.Listener.TOPIC,
            SpecKitSetup.Listener { ApplicationManager.getApplication().invokeLater({ refresh() }, project.disposed) },
        )
        Disposer.register(this) { timer.stop() }
        updateTimer()
        pr.refresh()
        refresh()
    }

    /**
     * Depois de uma entrega sem erro, a mensagem do commit volta ao valor da feature; depois de um PR, os outros
     * campos também, porque ele já está aberto.
     */
    private fun onDelivery() {
        val busy = pr.step != null
        if (prBusy && !busy && pr.lastError == null) {
            if (prSent == DeliveryMode.PULL_REQUEST) {
                prFields.clear()
                prDraft = false
            } else {
                prFields.remove("commit")
            }
        }
        prBusy = busy
        refresh()
    }

    private fun onSnapshot() {
        val active = service.snapshot.active?.dir
        if (newFeature && active != null && active != lastActive && lastActive != null) newFeature = false
        lastActive = active
        refresh()
    }

    private fun updateTimer() {
        if (runner.progress != null) timer.start() else timer.stop()
    }

    override fun body(): String {
        val s = service.snapshot
        if (s.layout == null) return WebUi.header("🚀", "Lançador", "Lendo o Spec Kit…", "Procurando .specify/ e specs/ no projeto.")
        val ready = TomahawkSettings.ready
        if (!s.initialized) return uninitialized(ready)
        val feature = if (newFeature) null else s.active
        val statuses = s.statuses(feature)
        val next = statuses.firstOrNull { it.next }
        val done = statuses.count { it.state == StepState.DONE }
        val current = selected?.takeIf { st -> statuses.any { it.step == st } } ?: next?.step ?: SddStep.CONSTITUTION
        return buildString {
            append(top(s, feature, statuses, next, done, ready))
            if (!ready) append(aiNotice())
            if (!s.constitutionReady && current != SddStep.CONSTITUTION) {
                append(alert("info", "📜", "A constituição ainda é o modelo: comece pelas <b>Regras de Engajamento</b>, que guiam as outras etapas.",
                    WebUi.button("Abrir", "step", "sm", arg = SddStep.CONSTITUTION.name)))
            }
            runner.progress?.let { append(Flight.card(it)) }
            append(features(s, feature))
            append("<div class=\"stepper ln-steps\">")
            statuses.forEach { append(step(it, it.step == current, s, feature, ready)) }
            append("</div>")
            append(delivery(s, statuses))
        }
    }

    /** A feature, a barra das nove etapas e uma linha com a próxima etapa, as tarefas e a IA. */
    private fun top(s: Snapshot, feature: FeatureReport?, statuses: List<StepStatus>, next: StepStatus?, done: Int, ready: Boolean): String = buildString {
        append("<header class=\"ln-top\"><div class=\"ln-head\"><div class=\"ln-titles\"><div class=\"ln-kicker\">🚀 Missão SDD</div>")
        append("<div class=\"ln-title\">").append(esc(feature?.name ?: "Nova feature")).append("</div></div>")
        append("<div class=\"ln-count\"><b>$done</b>/${statuses.size}<small>etapas</small></div></div>")
        append("<div class=\"ln-progress\">")
        statuses.forEach { status ->
            val cls = when {
                status.state == StepState.DONE -> "d"
                status.next -> "n"
                status.state == StepState.PARTIAL -> "p"
                status.state == StepState.READY -> "r"
                else -> ""
            }
            append("<i class=\"$cls\" title=\"").append(WebUi.attr("${status.step.codename}: ${status.state.label}")).append("\"></i>")
        }
        append("</div><div class=\"ln-meta\">")
        val parts = buildList {
            when {
                next != null -> add("Próxima: <b>${esc(next.step.codename)}</b>")
                statuses.any { it.step == SddStep.CONVERGE && it.state == StepState.DONE } -> add("🎯 <b>Missão cumprida</b>: a implementação convergiu")
                else -> add("Nenhuma etapa pendente")
            }
            feature?.tasks?.let { add("Tarefas <b>${it.done}/${it.total}</b>") }
            feature?.pending?.size?.takeIf { it > 0 }?.let { add("<span class=\"ln-warn\">$it a esclarecer</span>") }
        }
        append(parts.joinToString("<span class=\"ln-sep\">·</span>"))
        append("<span class=\"grow\"></span>")
        val ai = if (ready) TomahawkSettings.provider.title else "testar a IA"
        val where = s.integration.key?.let { " · integração $it" }.orEmpty()
        append("<span class=\"ln-ai").append(if (ready) "" else " off").append("\" data-cmd=\"arsenal\" title=\"").append(WebUi.attr("Abrir o Arsenal$where")).append("\">")
        append(esc(ai)).append("</span>")
        append("</div></header>")
    }

    private fun alert(kind: String, icon: String, html: String, action: String = ""): String =
        "<div class=\"ln-alert $kind\"><span>$icon</span><span class=\"grow\">$html</span>$action</div>"

    /** A Extração: commit, push e pull request da feature ativa, quando o projeto está num repositório do GitHub. */
    private fun delivery(s: Snapshot, statuses: List<StepStatus>): String = buildString {
        if (pr.gateway == null) return@buildString
        val state = pr.state
        val local = state?.local
        if (state != null && local == null) return@buildString
        val feature = s.active
        if (feature?.dir != prFeature) {
            prFields.clear()
            prFeature = feature?.dir
        }
        append(WebUi.section("Extração · Pull Request", WebUi.button("↻ Atualizar", "pr-refresh", "ghost", title = "Relê o repositório e o GitHub")))
        append("<div class=\"card2\" data-form=\"pr\">")
        append("<h3>🛬 Leve a missão para revisão</h3>")
        append("<div class=\"desc\">Só o commit, o commit com o push da branch ou tudo isso e o pull request no GitHub. A mensagem do commit e o texto do PR saem da spec e das tarefas, ou a IA escreve a partir do diff.</div>")
        if (local == null) {
            append("<div class=\"status-line\">Lendo o repositório Git…</div></div>")
            return@buildString
        }
        val remote = state.remote
        val open = remote?.openPr
        append("<div class=\"chips\" style=\"margin:0 0 10px\">")
        append(WebUi.chip("Repositório", esc(local.repo)))
        append(WebUi.chip("Branch", esc(local.branch ?: "sem branch")))
        append(WebUi.chip("Alterações", "${local.changes.size}", if (local.changes.isNotEmpty()) "yellow" else null))
        append(WebUi.chip("Sem push", "${local.ahead} commit(s)", if (local.ahead > 0) "blue" else null))
        append(if (local.account != null) WebUi.chip("GitHub", "@" + esc(local.account), "green") else WebUi.chip("GitHub", "entrar", "yellow", cmd = "pr-login"))
        append("</div>")

        val mode = prMode
        val opensPr = mode == DeliveryMode.PULL_REQUEST
        val canPr = local.account != null && remote != null
        if (local.account == null) {
            append(notice("warn", "🔑", "Para abrir pull requests, entre na conta do GitHub da IDE em <b>${esc(local.host)}</b>. Commitar e enviar já funcionam.",
                WebUi.button("🔑 Entrar no GitHub", "pr-login")))
        } else {
            state.problem?.let { append(notice("err", "⚠️", "O GitHub não respondeu: ${esc(it)}", WebUi.button("Tentar de novo", "pr-refresh"))) }
            if (remote == null && pr.loading) append("<div class=\"status-line\" style=\"margin:0 0 10px\">Consultando o GitHub…</div>")
        }
        val busy = pr.step
        val writing = pr.writing
        if (busy != null) append(notice("info", "🛰️", esc(busy)))
        if (writing != null) append(notice("info", "✨", "A IA está escrevendo ${esc(writing.label)}…"))
        pr.lastError?.takeIf { busy == null && writing == null }?.let { append(notice("err", "⚠️", esc(it))) }
        if (open != null) {
            val badge = if (open.draft) " <span class=\"tag\">rascunho</span>" else ""
            val waiting = PrRadarService.getInstance(project).pendingCount(open.number)
            val radio = if (waiting > 0) " · 💬 <b>$waiting conversa(s)</b> aguardando sua resposta" else ""
            append(notice("ok", "✅", "<b>PR #${open.number}</b> aberto: ${esc(open.title)} → <code>${esc(open.base)}</code>$badge$radio",
                WebUi.button("🌐 Abrir no GitHub", "pr-open", arg = open.url) + " " +
                    WebUi.button(if (waiting > 0) "📻 Ver os comentários ($waiting)" else "📻 Ver os comentários", "radio", if (waiting > 0) "primary" else "")))
        }
        if (opensPr && open == null && statuses.none { it.step == SddStep.CONVERGE && it.state == StepState.DONE }) {
            append("<div class=\"status-line\" style=\"margin:0 0 10px\">A Avaliação de Danos ainda não convergiu: dá para abrir o PR já, como rascunho, e completar depois.</div>")
        }

        val specTitle = feature?.spec?.file?.let { SpecKitLayout.read(it)?.lines() }?.let(PullRequestDraft::specTitle)
        val branch = prFields["branch"] ?: PullRequestDraft.branch(feature, local.branch, remote?.defaultBranch)
        val base = prFields["base"] ?: remote?.let { PullRequestDraft.base(it, branch) }.orEmpty()
        val title = prFields["title"] ?: PullRequestDraft.title(feature, specTitle)
        val commit = prFields["commit"] ?: title
        val text = prFields["body"] ?: PullRequestDraft.body(feature, specTitle) { s.relative(it) }
        val aiWhy = when {
            busy != null -> "Há uma entrega em andamento"
            writing != null -> "A IA já está escrevendo"
            !TomahawkSettings.ready -> "Teste a integração com a IA no Arsenal"
            else -> null
        }

        append("<div class=\"field\"><label>O que fazer</label>")
        append(WebUi.seg("mode", DeliveryMode.entries.map { it.name to modeLabel(it) }, mode.name, send = "pr-mode")).append("</div>")
        append("<div class=\"cols\">")
        append(input("Branch", "branch", branch, if (branch != local.branch && branch.isNotEmpty()) "Criada agora, a partir de ${esc(local.branch ?: "HEAD")}." else null))
        if (opensPr && open == null && remote != null) {
            val bases = (listOf(remote.defaultBranch, "develop") + remote.branches.sorted()).distinct().filter { it in remote.branches && it != branch }
            append("<div class=\"field\"><label>Base do PR</label><select class=\"in\" name=\"base\" data-change=\"pr-base\">")
            bases.forEach { append("<option value=\"${WebUi.attr(it)}\"").append(if (it == base) " selected" else "").append(">${esc(it)}</option>") }
            append("</select></div>")
        }
        append("</div>")
        if (local.changes.isNotEmpty()) {
            append("<div class=\"field\"><div class=\"fh\"><label>Mensagem do commit</label>")
            append(aiButton(WriteTarget.COMMIT, writing, aiWhy)).append("</div>")
            append("<textarea class=\"in\" name=\"commit\" data-draft=\"pr.commit\" style=\"min-height:110px\">").append(esc(commit)).append("</textarea>")
            append("<span class=\"hint\">A primeira linha é o título do commit; depois de uma linha em branco, o corpo com os detalhes.</span></div>")
            append("<details style=\"margin:-6px 0 12px\"><summary class=\"status-line\" style=\"cursor:pointer\">${local.changes.size} arquivo(s) no commit · fora tomahawk/ e .idea/</summary><div class=\"list\" style=\"margin-top:6px\">")
            local.changes.take(MAX_FILES).forEach { append("<div class=\"li\"><span class=\"ac-file\">").append(esc(it)).append("</span></div>") }
            if (local.changes.size > MAX_FILES) append("<div class=\"li meta\">e mais ${local.changes.size - MAX_FILES}…</div>")
            append("</div></details>")
        }
        if (opensPr && open == null) {
            append(input("Título do PR", "title", title, null))
            append("<div class=\"field\"><div class=\"fh\"><label>Descrição do PR</label>")
            append(aiButton(WriteTarget.PULL_REQUEST, writing, aiWhy ?: if (base.isEmpty()) "Escolha a base do PR" else null)).append("</div>")
            append("<textarea class=\"in\" name=\"body\" data-draft=\"pr.body\" style=\"min-height:160px\">").append(esc(text)).append("</textarea>")
            append("<span class=\"hint\">✨ Gerar com IA escreve o título e a descrição a partir dos commits e do diff contra a base.</span></div>")
        }

        append("<div class=\"st-tools\"><div class=\"row-btns\">")
        if (opensPr && open == null) {
            append("<label class=\"status-line\" style=\"margin:0;display:inline-flex;gap:6px;align-items:center\"><input type=\"checkbox\" name=\"draft\" data-change=\"pr-draft\"")
            append(if (prDraft) " checked" else "").append("> Abrir como rascunho</label>")
        }
        append("</div><div class=\"row-btns\">")
        val changes = local.changes.isNotEmpty()
        val label = when (mode) {
            DeliveryMode.COMMIT -> "💾 Commitar ${local.changes.size} arquivo(s)"
            DeliveryMode.PUSH -> if (changes) "⬆️ Commitar e enviar" else "⬆️ Enviar ${local.ahead} commit(s)"
            DeliveryMode.PULL_REQUEST -> when {
                open != null && changes -> "⬆️ Commitar e enviar ao PR #${open.number}"
                open != null -> "⬆️ Enviar ao PR #${open.number}"
                changes -> "🚀 Commitar, enviar e abrir PR"
                else -> "🚀 Enviar e abrir PR"
            }
        }
        val nothingToPush = !changes && local.ahead == 0 && local.upstream != null && branch == local.branch
        val why = when {
            busy != null -> "Há uma entrega em andamento"
            writing != null -> "A IA está escrevendo o texto"
            mode == DeliveryMode.COMMIT && !changes -> "Não há alterações para commitar"
            mode == DeliveryMode.PUSH && nothingToPush -> "Não há alterações nem commits sem push"
            opensPr && !canPr -> if (local.account == null) "Entre na conta do GitHub para abrir o PR" else "O GitHub não respondeu"
            opensPr && open != null && nothingToPush -> "O PR já tem tudo: não há alterações nem commits sem push"
            else -> null
        }
        append(WebUi.button(label, "pr", "primary big", enabled = why == null, title = why))
        append("</div></div>")
        append("<div class=\"status-line\">").append(plan(mode, local.changes.size, branch, local.branch, local.remote, base, open?.number)).append("</div>")
        append("</div>")
    }

    private fun modeLabel(mode: DeliveryMode) = when (mode) {
        DeliveryMode.COMMIT -> "💾 Só commitar"
        DeliveryMode.PUSH -> "⬆️ Commitar e enviar"
        DeliveryMode.PULL_REQUEST -> "🚀 Commitar, enviar e abrir PR"
    }

    private fun aiButton(target: WriteTarget, writing: WriteTarget?, why: String?): String =
        WebUi.button(
            if (writing == target) "⏳ Gerando…" else "✨ Gerar com IA",
            "pr-write",
            "ghost sm",
            arg = target.name,
            enabled = why == null,
            title = why ?: "A IA do Arsenal (${TomahawkSettings.provider.title}) escreve ${target.label} a partir das alterações",
        )

    private fun plan(mode: DeliveryMode, changes: Int, branch: String, current: String?, remote: String, base: String, open: Int?): String {
        val steps = buildList {
            if (branch.isNotEmpty() && branch != current) add("cria a branch <b>${esc(branch)}</b>")
            if (changes > 0) add("commita $changes arquivo(s)")
            if (mode != DeliveryMode.COMMIT) add("envia <b>${esc(branch)}</b> para <b>${esc(remote)}</b>")
            if (mode == DeliveryMode.PULL_REQUEST) add(if (open != null) "atualiza o PR #$open" else "abre o PR para <b>${esc(base.ifEmpty { "a base" })}</b>")
        }
        if (steps.isEmpty()) return "Nada a fazer."
        return steps.joinToString(", ").replaceFirstChar { it.uppercase() } + "."
    }

    private fun input(label: String, name: String, value: String, hint: String?): String = buildString {
        append("<div class=\"field\"><label>").append(label).append("</label>")
        append("<input class=\"in\" name=\"$name\" data-draft=\"pr.$name\" value=\"").append(WebUi.attr(value)).append("\">")
        hint?.let { append("<span class=\"hint\">").append(it).append("</span>") }
        append("</div>")
    }

    /** Guarda o que está nos campos do PR, para o redesenho não apagar. */
    private fun saveFields(data: JsonObject) {
        listOf("branch", "base", "title", "body", "commit").forEach { key -> data.str(key)?.let { prFields[key] = it } }
        data.get("draft")?.let { prDraft = data.str("draft") == "true" }
    }

    private fun notice(kind: String, icon: String, html: String, action: String = ""): String =
        "<div class=\"notice $kind\" style=\"margin:0 0 10px\"><span class=\"ni\">$icon</span><div class=\"nb\">$html" +
            (if (action.isNotEmpty()) "<div>$action</div>" else "") + "</div></div>"

    private fun aiChip(ready: Boolean) =
        if (ready) WebUi.chip("IA", "${TomahawkSettings.provider.title} ✓", "green", cmd = "arsenal")
        else WebUi.chip("IA", "testar integração", "yellow", cmd = "arsenal")

    private fun aiNotice(): String {
        val missing = TomahawkSettings.launch() == null
        val text = if (missing) "O ${TomahawkSettings.provider.title} CLI não foi encontrado. Configure a IA no Arsenal."
        else "Teste a integração com a IA no Arsenal: as missões só saem depois que o teste passa."
        return alert("warn", "⚠️", esc(text), WebUi.button("🛠️ Arsenal", "arsenal", "sm"))
    }

    private fun uninitialized(ready: Boolean): String = buildString {
        val specify = TomahawkSettings.resolveSpecify()
        append(WebUi.header("🚀", "Lançador", "Spec Kit não inicializado", "O Tomahawk dispara os comandos /speckit pelo ${esc(TomahawkSettings.provider.title)} CLI.", aiChip(ready)))
        if (!ready) append(aiNotice())
        append("<div class=\"empty2\"><div class=\"big\">🛰️</div><h3>Prepare a base de lançamento</h3>")
        append("<p>Este projeto ainda não tem o <b>.specify/</b>. Inicialize o Spec Kit para instalar os comandos de SDD do ")
        append(esc(TomahawkSettings.provider.title)).append(" no projeto.</p>")
        append(SpecKitWeb.initBox(specify))
        append("<div style=\"text-align:left;max-width:640px;margin:0 auto\">").append(SpecKitWeb.machine(specify)).append("</div>")
        append("</div>")
    }

    private fun features(s: Snapshot, chosen: FeatureReport?): String = buildString {
        append("<div class=\"ln-feats\">")
        s.features.reversed().forEach { f ->
            val on = !newFeature && f.dir == chosen?.dir
            val number = f.name.substringBefore('-').takeIf { it.all(Char::isDigit) }
            val label = if (number != null) f.name.substringAfter('-') else f.name
            val status = when {
                f.tasks != null -> "${f.tasks.done}/${f.tasks.total}"
                f.plan != null -> "plano"
                f.spec != null -> "spec"
                else -> "vazia"
            }
            val title = buildString {
                append(f.name)
                if (f.tasks != null) append(": ${f.tasks.done} de ${f.tasks.total} tarefas")
                if (f.dir == s.active?.dir) append(" (ativa)")
            }
            append("<button class=\"ln-feat").append(if (on) " on" else "").append("\" data-cmd=\"feature\" data-arg=\"").append(WebUi.attr(f.dir.toString()))
            append("\" title=\"").append(WebUi.attr(title)).append("\">")
            number?.let { append("<span class=\"num\">#$it</span>") }
            append("<span>").append(esc(label.replace('-', ' '))).append("</span><span class=\"ln-fs\">").append(status).append("</span></button>")
        }
        append("<button class=\"ln-feat add").append(if (newFeature) " on" else "").append("\" data-cmd=\"feature\" data-arg=\"NEW\" title=\"Começar uma feature nova pelo Designar Alvo\">＋ Nova</button>")
        append("</div>")
    }

    private fun step(status: StepStatus, open: Boolean, s: Snapshot, feature: FeatureReport?, ready: Boolean): String = buildString {
        val step = status.step
        val cls = listOfNotNull("ln-step", status.state.name.lowercase(), "next".takeIf { status.next }, "sel".takeIf { open }).joinToString(" ")
        append("<div class=\"$cls\"><div class=\"ln-row\" data-cmd=\"step\" data-arg=\"${step.name}\">")
        append("<span class=\"ln-dot\">").append(
            when (status.state) {
                StepState.DONE -> "✓"
                StepState.UNAVAILABLE -> "!"
                else -> step.number.toString()
            },
        ).append("</span>")
        append("<span class=\"ln-name\">").append(esc(step.codename)).append("</span>")
        append("<span class=\"ln-why\" title=\"").append(WebUi.attr(status.reason)).append("\">").append(esc(status.reason)).append("</span>")
        if (status.next) append("<span class=\"ln-state next\">Próxima</span>")
        else append("<span class=\"ln-state s-").append(tone(status.state)).append("\">").append(status.state.label).append("</span>")
        append("</div>")
        if (open) append(form(status, s, feature, ready))
        append("</div>")
    }

    private fun form(status: StepStatus, s: Snapshot, feature: FeatureReport?, ready: Boolean): String = buildString {
        val step = status.step
        val blocked = status.state == StepState.BLOCKED || status.state == StepState.UNAVAILABLE
        val flying = runner.progress != null
        val mode = modes[step] ?: if (step.interactive && LaunchTerminal.available() != null) RunMode.TERMINAL else TomahawkSettings.runMode.takeIf { it != RunMode.ASK } ?: RunMode.BACKGROUND
        append("<div class=\"ln-panel\" data-form=\"launch\">")
        append("<p class=\"ln-purpose\">").append(esc(step.purpose)).append("</p>")
        append("<div class=\"ln-facts\">")
        val invocation = esc(s.invocation(step))
        val ref = s.commands[step]
        if (ref != null) {
            append("<span class=\"ln-cmd clickable\" data-cmd=\"open\" data-arg=\"").append(WebUi.attr(ref.file.toString()))
            append("\" title=\"Abrir ").append(if (ref.skill) "a skill" else "o comando").append("\">").append(invocation).append("</span>")
        } else {
            append("<span class=\"ln-cmd\">").append(invocation).append("</span>")
        }
        listOfNotNull(
            "entrada obrigatória".takeIf { step.argsRequired },
            "opcional".takeIf { step.optional },
            "só leitura".takeIf { step.readOnly },
            "faz perguntas".takeIf { step.interactive },
        ).forEach { append("<span class=\"ln-fact\">").append(it).append("</span>") }
        append("</div>")
        if (blocked) append(alert("warn", "🔒", esc(status.reason)))
        append("<textarea class=\"in ln-in\" name=\"text\" rows=\"3\" data-draft=\"${step.name}\" placeholder=\"").append(WebUi.attr(step.argsHint)).append("\">")
        append(esc(drafts[step].orEmpty())).append("</textarea>")
        append("<div class=\"ln-actions\">")
        append(WebUi.seg("mode", listOf(RunMode.BACKGROUND.name to "🛰️ Devcontainer", RunMode.TERMINAL.name to "💻 Terminal"), mode.name, send = "mode"))
        append("<span class=\"grow\"></span>")
        if (artifactOf(step, feature) != null) append(WebUi.button("📄 Artefato", "artifact", "ghost sm", arg = step.name, title = "Abrir o que a etapa gerou"))
        append(WebUi.button("⧉ Copiar", "copy", "ghost sm", arg = step.name, title = "Copia o comando com a entrada, para colar no chat do agente"))
        val why = when {
            flying -> "Há uma missão em voo"
            !ready -> "Teste a integração com a IA no Arsenal"
            blocked -> status.reason
            else -> null
        }
        append(WebUi.button("🚀 Lançar <span class=\"ln-kbd\">Ctrl ↵</span>", "launch", "primary", arg = step.name, enabled = why == null, title = why ?: "Ctrl+Enter"))
        append("</div><div class=\"ln-hint\">")
        append(if (mode == RunMode.TERMINAL) "No terminal, você conversa com a IA, responde às perguntas e aprova os comandos." else "No devcontainer, a IA roda sem perguntar e a Telemetria acompanha cada passo.")
        append("</div></div>")
    }

    private fun tone(state: StepState) = when (state) {
        StepState.DONE -> "green"
        StepState.PARTIAL -> "blue"
        StepState.READY -> "orange"
        StepState.RECOMMENDED -> "yellow"
        StepState.UNAVAILABLE -> "red"
        else -> "gray"
    }

    private fun artifactOf(step: SddStep, feature: FeatureReport?): Path? = when (step) {
        SddStep.CONSTITUTION -> service.snapshot.layout?.constitution
        SddStep.SPECIFY, SddStep.CLARIFY -> feature?.spec?.file
        SddStep.PLAN -> feature?.plan?.file
        SddStep.CHECKLIST -> feature?.customChecklists?.firstOrNull()?.file ?: feature?.checklists?.firstOrNull()?.file
        SddStep.TASKS, SddStep.IMPLEMENT, SddStep.CONVERGE, SddStep.ANALYZE -> feature?.tasks?.file
    }?.takeIf { Files.exists(it) }

    override fun onMessage(command: String, data: JsonObject) {
        val step = data.str("arg")?.let { name -> SddStep.entries.firstOrNull { it.name == name } }
        when (command) {
            "step" -> {
                selected = step
                refresh(force = true)
            }
            "feature" -> {
                val arg = data.str("arg") ?: return
                if (arg == "NEW") {
                    newFeature = true
                    selected = SddStep.SPECIFY
                } else {
                    newFeature = false
                    selected = null
                    service.activate(Path.of(arg))
                }
                refresh(force = true)
            }
            "draft" -> {
                val key = data.str("arg").orEmpty()
                if (key.startsWith("pr.")) prFields[key.removePrefix("pr.")] = data.str("text").orEmpty()
                else step?.let { drafts[it] = data.str("text").orEmpty() }
            }
            "pr-base" -> data.str("value")?.let { prFields["base"] = it }
            "pr-mode" -> {
                saveFields(data)
                DeliveryMode.entries.firstOrNull { it.name == data.str("value") }?.let { prMode = it }
                refresh(force = true)
            }
            "pr-write" -> {
                val target = WriteTarget.entries.firstOrNull { it.name == data.str("arg") } ?: return
                saveFields(data)
                val base = data.str("base") ?: pr.state?.remote?.openPr?.base.orEmpty()
                val feature = service.snapshot.active?.let { service.snapshot.relative(it.dir) }
                pr.write(target, base, feature) { text ->
                    when (target) {
                        WriteTarget.COMMIT -> prFields["commit"] = text
                        WriteTarget.PULL_REQUEST -> {
                            val (title, body) = DeliveryWriter.pullRequest(text)
                            if (title.isNotBlank()) prFields["title"] = title
                            if (body.isNotBlank()) prFields["body"] = body
                        }
                    }
                    refresh(force = true)
                }
            }
            "pr-draft" -> prDraft = data.str("draft") == "true"
            "pr-refresh" -> pr.refresh(remote = true, delayMs = 0)
            "pr-login" -> pr.login()
            "pr-open" -> data.str("arg")?.let(BrowserUtil::browse)
            "pr" -> {
                saveFields(data)
                prSent = prMode
                pr.deliver(
                    DeliveryRequest(
                        branch = data.str("branch").orEmpty(),
                        base = data.str("base") ?: pr.state?.remote?.openPr?.base.orEmpty(),
                        commitMessage = data.str("commit") ?: prFields["commit"].orEmpty(),
                        title = data.str("title") ?: prFields["title"].orEmpty(),
                        body = data.str("body") ?: prFields["body"].orEmpty(),
                        draft = prDraft,
                        mode = prMode,
                    ),
                )
            }
            "mode" -> {
                val target = selected ?: return
                RunMode.entries.firstOrNull { it.name == data.str("value") }?.let { modes[target] = it }
            }
            "launch" -> {
                step ?: return
                val text = data.str("text").orEmpty()
                drafts[step] = text
                val feature = if (newFeature) null else service.snapshot.active
                val mode = RunMode.entries.firstOrNull { it.name == data.str("mode") }
                val mission = Mission(step, text, feature?.takeIf { step != SddStep.SPECIFY }?.let { service.snapshot.relative(it.dir) }, mode)
                if (runner.launch(mission)) drafts.remove(step)
                refresh(force = true)
            }
            "copy" -> step?.let { runner.copyCommand(Mission(it, data.str("text").orEmpty(), null)) }
            "artifact" -> step?.let { s -> artifactOf(s, if (newFeature) null else service.snapshot.active)?.let { openArtifact(project, it) } }
            "open" -> data.str("arg")?.let { openArtifact(project, Path.of(it)) }
            "init" -> runner.initSpecKit()
            "init-copy" -> SpecKitSetup.copyInit(project)
            "specify-shell" -> {
                SpecKitWeb.choose(data.str("value"))
                refresh(force = true)
            }
            "specify-install" -> SpecKitSetup.install(project)
            "specify-repair" -> SpecKitSetup.repair(project)
            "specify-upgrade" -> SpecKitSetup.upgrade(project)
            "specify-recheck" -> {
                SpecKitSetup.recheck()
                service.refresh(0)
            }
            "arsenal" -> AttackTabs.showTab(project, AttackTabs.ARSENAL)
            "radio" -> AttackTabs.showTab(project, AttackTabs.RADIO)
            "abort" -> runner.progress?.cancel()
        }
    }

    override val css: String get() = CSS

    private companion object {
        const val TICK_MS = 500
        const val MAX_FILES = 40

        val CSS = """
            .ln-top{padding:14px 16px 12px;border:1px solid var(--border);border-radius:14px;background:var(--card);box-shadow:var(--shadow)}
            .ln-head{display:flex;align-items:flex-end;gap:12px}
            .ln-titles{flex:1;min-width:0}
            .ln-kicker{font-size:10.5px;font-weight:700;letter-spacing:.08em;text-transform:uppercase;color:var(--muted)}
            .ln-title{font-size:17px;font-weight:800;letter-spacing:-.01em;margin-top:2px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
            .ln-count{flex:none;font-size:12px;color:var(--muted);font-variant-numeric:tabular-nums}
            .ln-count b{font-size:20px;font-weight:800;color:var(--fg)}
            .ln-count small{margin-left:5px;font-size:11px}
            .ln-progress{display:flex;gap:3px;margin:10px 0 9px}
            .ln-progress i{flex:1;height:5px;border-radius:3px;background:var(--gray-bg)}
            .ln-progress i.d{background:var(--green)}
            .ln-progress i.p{background:var(--blue)}
            .ln-progress i.r{background:color-mix(in srgb,var(--orange) 40%,transparent)}
            .ln-progress i.n{background:var(--orange);box-shadow:0 0 8px -1px var(--orange)}
            .ln-meta{display:flex;align-items:center;flex-wrap:wrap;gap:6px;font-size:12px;color:var(--muted)}
            .ln-meta b{color:var(--fg);font-weight:650}
            .ln-meta .grow{flex:1}
            .ln-sep{opacity:.45}
            .ln-warn{color:var(--yellow-fg);font-weight:600}
            .ln-ai{display:inline-flex;align-items:center;gap:6px;padding:2px 10px;border-radius:999px;border:1px solid var(--border);font-size:11.5px;font-weight:650;color:var(--fg);cursor:pointer;transition:border-color .15s}
            .ln-ai::before{content:"";width:6px;height:6px;border-radius:50%;background:var(--green);box-shadow:0 0 6px var(--green)}
            .ln-ai.off{color:var(--yellow-fg)} .ln-ai.off::before{background:var(--yellow);box-shadow:none}
            .ln-ai:hover{border-color:var(--accent)}

            .ln-alert{display:flex;align-items:center;gap:10px;margin-top:10px;padding:8px 12px;border-radius:10px;border:1px solid;font-size:12px}
            .ln-alert .grow{flex:1;min-width:0}
            .ln-alert .btn{flex:none}
            .ln-alert.warn{background:var(--yellow-bg);border-color:var(--yellow);color:var(--yellow-fg)}
            .ln-alert.info{background:var(--blue-bg);border-color:var(--blue);color:var(--blue-fg)}

            .ln-feats{display:flex;gap:6px;overflow-x:auto;margin:14px 0 10px;padding:1px 1px 3px;scrollbar-width:thin}
            .ln-feat{flex:none;display:inline-flex;align-items:center;gap:7px;height:28px;padding:0 12px;border-radius:999px;border:1px solid var(--border);
              background:transparent;color:var(--fg);font:600 12px inherit;cursor:pointer;transition:all .15s}
            .ln-feat:hover{border-color:var(--accent)}
            .ln-feat.on{background:var(--accent-bg);border-color:var(--accent);color:var(--accent-fg)}
            .ln-feat .num{font:700 10.5px "JetBrains Mono",Consolas,monospace;opacity:.7}
            .ln-feat .ln-fs{font-size:11px;font-weight:500;color:var(--muted)}
            .ln-feat.add{border-style:dashed;color:var(--muted)}

            .ln-steps{border:1px solid var(--border);border-radius:14px;background:var(--card);box-shadow:var(--shadow);overflow:hidden}
            .ln-step + .ln-step{border-top:1px solid var(--border)}
            .ln-row{display:flex;align-items:center;gap:10px;padding:8px 14px;cursor:pointer;transition:background .12s}
            .ln-row:hover{background:rgba(127,127,127,.07)}
            .ln-dot{flex:none;width:22px;height:22px;border-radius:50%;display:inline-flex;align-items:center;justify-content:center;
              font-size:11px;font-weight:800;border:1.5px solid var(--border);color:var(--muted)}
            .ln-step.done .ln-dot{background:var(--green);border-color:var(--green);color:#fff}
            .ln-step.partial .ln-dot{border-color:var(--blue);color:var(--blue-fg)}
            .ln-step.ready .ln-dot{border-color:var(--orange);color:var(--orange-fg)}
            .ln-step.recommended .ln-dot{border-color:var(--yellow);color:var(--yellow-fg)}
            .ln-step.unavailable .ln-dot{border-color:var(--red);color:var(--red-fg)}
            .ln-step.next .ln-dot{background:var(--orange);border-color:var(--orange);color:#fff;box-shadow:0 0 0 3px var(--orange-bg)}
            .ln-name{flex:none;font-size:13px;font-weight:650}
            .ln-step.blocked .ln-name,.ln-step.unavailable .ln-name{color:var(--muted)}
            .ln-why{flex:1;min-width:0;font-size:12px;color:var(--muted);overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
            .ln-state{flex:none;font-size:11px;font-weight:650;color:var(--muted)}
            .ln-state.s-green{color:var(--green-fg)} .ln-state.s-blue{color:var(--blue-fg)} .ln-state.s-orange{color:var(--orange-fg)}
            .ln-state.s-yellow{color:var(--yellow-fg)} .ln-state.s-red{color:var(--red-fg)}
            .ln-state.next{padding:1px 9px;border-radius:999px;background:var(--orange);color:#fff}
            .ln-step.sel{background:linear-gradient(90deg,var(--accent-bg),transparent 70%);box-shadow:inset 3px 0 0 var(--accent)}
            .ln-step.sel .ln-row:hover{background:transparent}

            .ln-panel{padding:0 14px 14px 46px}
            .ln-purpose{margin:0 0 8px;font-size:12.5px;line-height:1.5}
            .ln-facts{display:flex;flex-wrap:wrap;align-items:center;gap:6px;margin-bottom:10px}
            .ln-cmd{font:600 11px "JetBrains Mono",Consolas,monospace;padding:2px 7px;border-radius:6px;border:1px solid var(--border);background:var(--bg);color:var(--fg)}
            .ln-cmd.clickable{cursor:pointer} .ln-cmd.clickable:hover{border-color:var(--accent);color:var(--accent-fg)}
            .ln-fact{font-size:11px;color:var(--muted)} .ln-fact::before{content:"·";margin-right:6px;opacity:.6}
            .ln-panel .ln-alert{margin:0 0 10px}
            .ln-in{min-height:56px;resize:vertical;background:var(--card)}
            .ln-actions{display:flex;align-items:center;flex-wrap:wrap;gap:8px;margin-top:10px}
            .ln-actions .grow{flex:1}
            .ln-actions .btn.primary{margin-left:auto}
            .ln-kbd{margin-left:4px;padding:0 5px;border:1px solid rgba(255,255,255,.45);border-radius:4px;font:600 10px "JetBrains Mono",Consolas,monospace;opacity:.85}
            .ln-hint{margin-top:8px;font-size:11.5px;color:var(--muted)}
        """.trimIndent()
    }
}
