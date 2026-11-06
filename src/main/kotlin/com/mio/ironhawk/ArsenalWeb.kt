package com.mio.ironhawk

import com.google.gson.JsonObject
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.project.Project
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
import com.mio.irondome.interceptor.InterceptorPanel
import com.mio.tomahawk.run.DevContainer
import com.mio.tomahawk.run.Guardrails
import com.mio.tomahawk.run.LaunchTerminal
import com.mio.tomahawk.run.MissionRunner
import com.mio.tomahawk.run.RunMode
import com.mio.tomahawk.run.SpecKitSetup
import com.mio.tomahawk.run.TomahawkSettings
import com.mio.tomahawk.speckit.SddStep
import com.mio.tomahawk.speckit.SpecKitService
import com.mio.tomahawk.ui.web.SpecKitWeb
import com.mio.tomahawk.ui.web.WebTab
import com.mio.tomahawk.ui.web.WebUi
import java.nio.file.Files
import java.nio.file.Path

/**
 * O Arsenal, comum aos dois sistemas: em cima, a IA que os dois usam (configuração automática, o CLI, onde ele está, o
 * executável, o modelo, o tempo, os argumentos, a chave do Cursor, Salvar e Testar e o teste pelo terminal); embaixo, o
 * de cada sistema ligado (a execução da Defesa; o lançamento, o devcontainer, as proteções e o Spec Kit do Ataque) e
 * as execuções recentes dos dois.
 */
class ArsenalWeb(project: Project) : WebTab(project) {

    override val accent = "teal"

    /** O que está nos campos e ainda não foi salvo. */
    private data class Draft(
        val provider: AgentProvider,
        val host: AgentHost,
        val distro: String?,
        val executable: String,
        val model: String,
        val timeout: Int,
        val extra: String,
    ) {
        companion object {
            fun current() = Draft(
                AgentSettings.provider,
                AgentSettings.host,
                AgentSettings.wslDistro,
                AgentSettings.executable.orEmpty(),
                AgentSettings.model.orEmpty(),
                AgentSettings.timeoutMinutes,
                AgentSettings.extraArgs.orEmpty(),
            )
        }
    }

    private class Status(val text: String, val ok: Boolean?)

    /** Uma execução da IA: um pedido da Defesa ou uma missão do Ataque. */
    private class Run(val path: Path, val title: String, val status: String, val ok: Boolean, val system: IronHawkSystem, val time: Long)

    private val service = SpecKitService.getInstance(project)
    private val missions = MissionRunner.getInstance(project)
    private val agent = AgentRunner.getInstance(project)
    private var draft = Draft.current()
    private var saving = false
    private var distros: List<String> = emptyList()
    private val wslFound = mutableMapOf<String, String?>()
    private var defenseRuns: List<Run> = emptyList()
    private var testStatus: Status? = null
    private var autoStatus: Status? = null
    private var autoFailure: String? = null
    private var terminalStatus: Status? = null
    private var containerStatus: Status? = null
    private var containerBusy = false

    init {
        val app = ApplicationManager.getApplication()
        app.messageBus.connect(this).subscribe(AgentSettings.Listener.TOPIC, AgentSettings.Listener {
            app.invokeLater({
                if (!saving && !AgentAutoSetup.running) draft = Draft.current()
                refresh()
            }, project.disposed)
        })
        app.messageBus.connect(this).subscribe(TomahawkSettings.Listener.TOPIC, TomahawkSettings.Listener {
            app.invokeLater({ refresh() }, project.disposed)
        })
        app.messageBus.connect(this).subscribe(IronHawkSystem.TOPIC, IronHawkSystem.Listener {
            app.invokeLater({ refresh(force = true) }, project.disposed)
        })
        app.messageBus.connect(this).subscribe(SpecKitSetup.Listener.TOPIC, SpecKitSetup.Listener {
            app.invokeLater({ refresh() }, project.disposed)
        })
        project.messageBus.connect(this).subscribe(SpecKitService.CHANGED, SpecKitService.Listener { refresh() })
        project.messageBus.connect(this).subscribe(AgentRunner.RUNS, AgentRunner.Listener {
            app.invokeLater({ loadDefenseRuns() }, project.disposed)
        })
        if (AgentLaunch.wslSupported) {
            app.executeOnPooledThread {
                val names = AgentLaunch.distributions()
                app.invokeLater({
                    distros = names
                    if (draft.distro == null && names.isNotEmpty()) draft = draft.copy(distro = names.first())
                    refresh()
                }, project.disposed)
            }
        }
        loadDefenseRuns()
        refresh()
        if (!AgentSettings.ready && !AgentSettings.autoSetupTried && AgentSettings.untouched) {
            app.invokeLater({ autoSetup() }, project.disposed)
        }
    }

    private fun loadDefenseRuns() {
        ApplicationManager.getApplication().executeOnPooledThread {
            val loaded = agent.transcripts().take(InterceptorPanel.MAX_RUNS).map { path ->
                val (title, status) = AgentTranscript.summary(runCatching { Files.readAllLines(path).take(12) }.getOrDefault(emptyList()))
                val time = runCatching { Files.getLastModifiedTime(path).toMillis() }.getOrDefault(0L)
                Run(path, title ?: path.fileName.toString(), status.orEmpty(), status == "concluída", IronHawkSystem.DEFENSE, time)
            }
            ApplicationManager.getApplication().invokeLater({
                defenseRuns = loaded
                refresh()
            }, project.disposed)
        }
    }

    override fun body(): String = buildString {
        val ready = AgentSettings.ready
        val launch = AgentSettings.launch()
        val busy = AgentAutoSetup.running || AgentVerifier.testing
        val working = agent.running
        val (title, badge) = when {
            AgentAutoSetup.running -> "Configurando a IA…" to "<span class=\"status-big warn\">⏳ configurando</span>"
            AgentVerifier.testing -> "Testando a integração…" to "<span class=\"status-big warn\">⏳ testando</span>"
            launch == null -> "IA não configurada" to "<span class=\"status-big bad\">○ não configurada</span>"
            !ready -> "Teste a integração" to "<span class=\"status-big warn\">⚠ falta testar</span>"
            working != null -> "IA trabalhando: ${esc(working.title)}" to "<span class=\"status-big good\">⚡ trabalhando</span>"
            missions.progress != null -> "Missão em voo" to "<span class=\"status-big good\">🚀 em voo</span>"
            else -> "IA pronta para os dois sistemas" to "<span class=\"status-big good\">✓ pronta</span>"
        }
        val subtitle = when {
            launch == null -> esc(AgentSettings.provider.installHint)
            else -> esc("${AgentSettings.provider.title} CLI${launch.where} · ${AgentSettings.model?.let { "modelo $it" } ?: "modelo padrão"}") +
                (AgentSettings.verifiedAt?.let { " · testada em ${esc(it)}" } ?: "")
        }
        append(WebUi.header("🧰", "Arsenal · a IA do IronHawk", title, subtitle, "", badge))

        append(WebUi.section("A IA · comum aos dois sistemas"))
        append(GRID)
        append(autoCard(busy))
        append("</div><div>")
        append(cliCard(busy))
        append("</div></div>")

        if (IronHawkSystem.DEFENSE.enabled) {
            append(WebUi.section("🛡️ Defesa · Iron Dome"))
            append(GRID)
            append(defenseRunCard())
            append("</div><div>")
            append(defenseCapsCard(ready))
            append("</div></div>")
        }
        if (IronHawkSystem.ATTACK.enabled) {
            append(WebUi.section("🚀 Ataque · Tomahawk"))
            append(GRID)
            append(launchCard())
            append(containerCard())
            append(guardCard())
            append("</div><div>")
            append(specKitCard())
            append(attackCapsCard())
            append("</div></div>")
        }
        append(WebUi.section("Execuções recentes"))
        append(runsCard())
    }

    private fun autoCard(busy: Boolean): String = buildString {
        append("<div class=\"card2\"><h3>⚡ Configuração automática</h3>")
        append("<div class=\"desc\">Procura o ").append(esc(AgentProvider.entries.joinToString(", ") { "${it.title} CLI" })).append(" instalados")
        if (AgentLaunch.wslSupported) append(", nesta máquina e no WSL,")
        append(" e testa cada um, com o modelo padrão, até um responder.</div>")
        append(WebUi.button(if (AgentAutoSetup.running) "⏳ Configurando…" else "✨ Configurar automaticamente", "auto", "primary", enabled = !busy))
        status(autoStatus)
        autoFailure?.let { append(notice("warn", "⚠️", esc(it))) }
        append("</div>")
    }

    private fun cliCard(busy: Boolean): String = buildString {
        val d = draft
        append("<div class=\"card2\" data-form=\"cli\"><h3>🤖 CLI de IA</h3>")
        val uses = listOfNotNull(
            "as ordens de interceptação e as revisões da Defesa".takeIf { IronHawkSystem.DEFENSE.enabled },
            "as missões do Spec Kit do Ataque".takeIf { IronHawkSystem.ATTACK.enabled },
        )
        append("<div class=\"desc\">O agente que executa ").append(uses.joinToString(" e ")).append(".</div>")
        append("<div class=\"field\"><label>CLI</label>")
        append(WebUi.seg("provider", AgentProvider.entries.map { it.name to "${esc(it.title)} <span class=\"muted\">(${esc(it.executables.first())})</span>" }, d.provider.name, send = "form"))
        append("</div>")
        if (IronHawkSystem.ATTACK.enabled && d.provider == AgentProvider.ANTIGRAVITY) {
            append(notice("warn", "🚀", "O <b>Antigravity</b> não lança as missões do Ataque: o Spec Kit não tem integração para ele. Para o Lançador, escolha o Cursor ou o Claude Code."))
        }
        if (AgentLaunch.wslSupported) {
            append("<div class=\"field\"><label>Onde está o CLI</label>")
            append(WebUi.seg("host", AgentHost.entries.map { it.name to esc(it.label) }, d.host.name, send = "form")).append("</div>")
            if (d.host == AgentHost.WSL) {
                append("<div class=\"field\"><label>Distribuição do WSL</label><select class=\"in\" name=\"distro\" data-change=\"form\">")
                (distros + listOfNotNull(d.distro)).distinct().forEach { name ->
                    append("<option value=\"").append(WebUi.attr(name)).append("\"").append(if (name == d.distro) " selected" else "").append(">").append(esc(name)).append("</option>")
                }
                if (distros.isEmpty() && d.distro == null) append("<option value=\"\">Procurando distribuições…</option>")
                append("</select></div>")
            }
        } else {
            append("<input type=\"hidden\" name=\"host\" value=\"LOCAL\">")
        }
        append("<div class=\"field\"><label>Executável</label><div class=\"in-row\">")
        append("<input class=\"in\" name=\"executable\" data-change=\"form\" value=\"").append(WebUi.attr(d.executable))
        append("\" placeholder=\"").append(if (d.host == AgentHost.WSL) "Vazio usa ${d.provider.executables.first()} do PATH do WSL" else "Vazio procura sozinho").append("\">")
        if (d.host == AgentHost.LOCAL) append(WebUi.button("📂 Procurar…", "browse", "ghost"))
        append("</div>").append(foundHint(d)).append("</div>")
        append("<div class=\"cols\">")
        append("<div class=\"field\"><label>Modelo</label><input class=\"in\" name=\"model\" value=\"").append(WebUi.attr(d.model)).append("\" placeholder=\"Padrão do CLI\">")
        append("<span class=\"hint\">Como no --model do CLI.</span></div>")
        append("<div class=\"field\"><label>Tempo máximo (min)</label><input class=\"in\" type=\"number\" min=\"1\" max=\"${AgentSettings.MAX_TIMEOUT_MINUTES}\" name=\"timeout\" value=\"${d.timeout}\">")
        append("<span class=\"hint\">Uma tarefa ou missão que passar disso é interrompida.</span></div>")
        append("</div>")
        append("<div class=\"field\"><label>Argumentos extras</label><input class=\"in\" name=\"extra\" value=\"").append(WebUi.attr(d.extra)).append("\" placeholder=\"Ex.: --approve-mcps\">")
        append("<span class=\"hint\">Vão para o CLI antes do pedido, separados por espaço; aspas agrupam.</span></div>")
        if (d.provider == AgentProvider.CURSOR) {
            append("<div class=\"field\"><label>Chave de API do Cursor</label><input class=\"in\" type=\"password\" name=\"key\" autocomplete=\"off\" placeholder=\"")
            append(if (AgentSettings.hasCursorApiKey) "•••••••• salva no cofre de senhas (deixe vazio para manter)" else "Opcional: sem ela, vale o agent login").append("\">")
            append("<span class=\"hint\">Crie em cursor.com/dashboard › Integrations. Fica no cofre de senhas da IDE.</span>")
            if (AgentSettings.hasCursorApiKey) append("<div>").append(WebUi.button("Remover chave salva", "clearKey", "ghost")).append("</div>")
            append("</div>")
        }
        if (d.provider == AgentProvider.ANTIGRAVITY) {
            append(notice("warn", "⚠️", "O <code>agy -p</code> pode travar quando chamado por outro programa (issue 318 do antigravity-cli); o tempo máximo interrompe."))
        }
        append("<div class=\"row-btns\" style=\"margin-top:12px\">")
        append(WebUi.button(if (AgentVerifier.testing) "⏳ Testando…" else "✔ Salvar e testar", "save", "primary big", arg = "test", enabled = !busy))
        append(WebUi.button("Salvar", "save", enabled = !busy))
        val terminal = AgentTerminal.available() != null
        append(WebUi.button(
            if (AgentVerifier.testingTerminal) "⏳ Esperando o terminal…" else "💻 Testar no terminal", "terminal",
            enabled = terminal && !AgentVerifier.testingTerminal && AgentSettings.launch() != null,
            title = if (terminal) "Abre o terminal da IDE e roda o CLI com a configuração salva" else "O plugin de terminal da IDE está desligado",
        ))
        append("</div>")
        status(testStatus ?: AgentSettings.verifiedAt?.takeIf { AgentSettings.ready }?.let { Status("Testada em $it.", true) })
        status(terminalStatus ?: AgentSettings.terminalVerifiedAt?.let { Status("Terminal testado em $it.", true) })
        append("</div>")
    }

    private fun foundHint(d: Draft): String {
        if (d.host == AgentHost.WSL) {
            val distro = d.distro ?: return "<span class=\"hint bad\">Escolha a distribuição do WSL.</span>"
            if (d.executable.isNotBlank()) return "<span class=\"hint\">No WSL ${esc(distro)}: ${esc(d.executable)}</span>"
            val key = "${d.provider.name}|$distro"
            if (key !in wslFound) {
                wslFound[key] = null
                ApplicationManager.getApplication().executeOnPooledThread {
                    val path = AgentLaunch.findInWsl(d.provider, distro)
                    ApplicationManager.getApplication().invokeLater({
                        wslFound[key] = path ?: ""
                        refresh()
                    }, project.disposed)
                }
            }
            val found = wslFound[key]
            return when {
                found == null -> "<span class=\"hint\">Procurando o ${esc(d.provider.title)} CLI no WSL ${esc(distro)}…</span>"
                found.isEmpty() -> "<span class=\"hint bad\">Não encontrado no WSL ${esc(distro)}. ${esc(d.provider.installHint)}</span>"
                else -> "<span class=\"hint good\">✓ Encontrado: WSL ${esc(distro)}: ${esc(found)}</span>"
            }
        }
        val path = AgentSettings.resolveExecutable(d.provider, d.executable.ifBlank { null })
        return if (path != null) "<span class=\"hint good\">✓ Encontrado: ${esc(path.toString())}</span>"
        else "<span class=\"hint bad\">Não encontrado. ${esc(d.provider.installHint)}</span>"
    }

    private fun defenseRunCard(): String = buildString {
        append("<div class=\"card2\"><h3>🎛️ Execução</h3><div class=\"desc\">Onde a IA roda quando um botão da Defesa a chama.</div>")
        append("<div class=\"field\"><label>Onde rodar a IA</label>")
        append(WebUi.seg("runMode", AgentRunMode.entries.map { it.name to esc(it.label) }, AgentSettings.runMode.name, send = "defense-runmode")).append("</div>")
        append("<div class=\"desc\">").append(esc(InterceptorPanel.RUN_MODE_HINT)).append("</div>")
        append(
            notice(
                "info", "🔐",
                "Nas tarefas que alteram arquivos rodando na aba, como Disparar com IA, não há janela para aprovar cada passo: o Cursor " +
                    "roda com <code>--force</code> (edita e roda comandos sem perguntar), o Claude Code com <code>--permission-mode acceptEdits</code> " +
                    "(edita sem perguntar; comandos de terminal só os liberados nas configurações dele ou com <code>--allowedTools</code> nos " +
                    "argumentos extras) e o Antigravity com <code>--dangerously-skip-permissions</code>. " +
                    (if (AgentSettings.confirmWrites) "Por isso o plugin pergunta antes de cada uma. " else "Você desligou a pergunta antes de cada uma. ") +
                    "No terminal, a IA pede aprovação a cada passo. Nas de leitura, como revisões e explicações, nenhum deles recebe permissão para escrever.",
            ),
        )
        if (!AgentSettings.confirmWrites) {
            append("<div class=\"row-btns\">").append(WebUi.button("Voltar a perguntar antes de alterar arquivos", "confirmWrites", "ghost")).append("</div>")
        }
        append(
            notice(
                "info", "🧯",
                "<b>Contenção da IA</b>, sempre ligada: antes de cada tarefa que altera arquivos, uma foto do projeto no git, sem mexer no " +
                    "seu índice nem na branch (sem git, no Local History), e o aviso do fim traz <b>Desfazer Alterações da IA</b>; na aba, a IA roda sem <code>git push</code> e sem os tokens e senhas " +
                    "do ambiente que o CLI não usa; o Claude Code recusa comandos destrutivos e mexer no <code>.git</code> e nas chaves do usuário; " +
                    "no WSL, o Cursor roda no sandbox dele; e a IA é parada na hora se tentar apagar pastas, descartar alterações do git, " +
                    "reescrever o histórico, rodar como administrador, formatar disco, baixar e executar scripts, ou escrever fora do projeto ou no <code>.git</code>.",
            ),
        )
        append("</div>")
    }

    private fun defenseCapsCard(ready: Boolean): String = buildString {
        append("<div class=\"card2\"><h3>✨ O que a IA libera na Defesa</h3><ul class=\"caps\">")
        DEFENSE_CAPABILITIES.forEach { (where, what) ->
            append("<li><span class=\"ck ").append(if (ready) "on\">✓" else "off\">○").append("</span><span><b>")
                .append(esc(where)).append("</b>: ").append(esc(what)).append("</span></li>")
        }
        append("</ul>")
        if (!ready) append("<div class=\"status-line\">Desligadas até a integração ser testada com a configuração atual.</div>")
        append("</div>")
    }

    private fun launchCard(): String = buildString {
        append("<div class=\"card2\"><h3>🎛️ Lançamento</h3><div class=\"desc\">Onde as missões rodam por padrão; cada etapa pode trocar na hora de lançar.</div>")
        append("<div class=\"field\"><label>Onde lançar</label>")
        append(WebUi.seg("launchMode", RunMode.entries.map { it.name to it.label }, TomahawkSettings.runMode.name, send = "attack-runmode")).append("</div>")
        append("<div class=\"desc\">Na aba, a IA roda sem perguntar no devcontainer, a Telemetria acompanha cada passo e a IA decide sozinha quando o comando faria perguntas. ")
        append("No terminal da IDE você conversa com a IA e aprova os comandos dela (o Reconhecimento já abre lá).</div>")
        append(
            notice(
                "warn", "🔐",
                "<b>Missões na aba:</b> só no <b>devcontainer</b> do projeto, a única forma de a IA rodar sem perguntar. Lá o Cursor roda com " +
                    "<code>--force --trust</code> e o Claude Code com <code>--permission-mode bypassPermissions</code>, mas o container só enxerga a pasta do projeto.<br>" +
                    "<b>No terminal da IDE:</b> modo agente sem permissão total, nesta máquina: a IA edita o projeto e pede a sua aprovação antes de rodar comandos.<br>" +
                    "<b>Textos</b> (commit, PR, respostas do Rádio): só leitura, para um comentário malicioso num PR não dar à IA permissão para alterar arquivos.",
            ),
        )
        if (LaunchTerminal.available() == null) append("<div class=\"status-line bad\">O plugin de terminal da IDE está desligado: as missões só rodam na aba.</div>")
        append("</div>")
    }

    private fun containerCard(): String = buildString {
        val root = service.root()
        val cli = DevContainer.cli()
        val config = root?.let(DevContainer::config)?.takeIf { Files.isRegularFile(it) }
        val exists = config != null
        val problems = config?.let { file -> DevContainer.audit(runCatching { Files.readString(file) }.getOrDefault("")) }.orEmpty()
        val cursor = AgentSettings.provider == AgentProvider.CURSOR
        append("<div class=\"card2\"><h3>📦 Devcontainer</h3><div class=\"desc\">A única forma de a IA rodar sem perguntar: as missões na aba rodam num container ")
        append("que só enxerga a pasta do projeto. Sem ele, as missões só saem pelo terminal, com você aprovando os comandos.</div>")
        append("<div class=\"chips\" style=\"margin:0 0 10px\">")
        append(if (cli != null) WebUi.chip("CLI", "devcontainer ✓", "green") else WebUi.chip("CLI", "devcontainer não instalado", "red"))
        append(
            when {
                !exists -> WebUi.chip("Projeto", "sem devcontainer", "yellow")
                problems.isEmpty() -> WebUi.chip("Isolamento", "conferido ✓", "green")
                else -> WebUi.chip("Isolamento", "${problems.size} problema(s)", "red")
            },
        )
        append("</div>")
        if (cli == null) append(notice("warn", "🐳", esc(DevContainer.CLI_HINT)))
        if (problems.isNotEmpty()) {
            append("<div class=\"notice err\"><span class=\"ni\">⚠️</span><div class=\"nb\">O <code>").append(DevContainer.CONFIG).append("</code> não isola a máquina:<ul>")
            problems.forEach { append("<li>").append(esc(it)).append("</li>") }
            append("</ul></div></div>")
        }
        append("<ul class=\"caps\">")
        listOf(
            "<b>Só o projeto</b>: o container não vê as suas pastas, as suas credenciais nem o Docker da máquina.",
            "<b>Git protegido</b>: o <code>.git/config</code> e os hooks ficam só leitura lá dentro, porque o Git da sua máquina roda o que estiver neles.",
            "<b>Conferido a cada missão</b>: o Tomahawk recusa o container privilegiado, com rede ou processos da máquina, ou com pastas da máquina montadas.",
        ).forEach { append("<li><span class=\"ck on\">✓</span><span>").append(it).append("</span></li>") }
        append("</ul>")
        if (cursor) {
            append("<div class=\"status-line").append(if (AgentSettings.hasCursorApiKey) "" else " bad").append("\">No container, o Cursor CLI entra pela chave de API do cartão <b>CLI de IA</b>.</div>")
        } else {
            append("<div class=\"status-line\">No container, o Claude Code usa o login feito lá dentro (guardado num volume) ou o <code>ANTHROPIC_API_KEY</code> desta máquina. Para entrar uma vez:</div>")
            append("<div class=\"cmdline\">devcontainer exec --workspace-folder . --config ").append(DevContainer.CONFIG).append(" claude</div>")
        }
        append("<div class=\"row-btns\" style=\"margin-top:10px\">")
        if (!exists) {
            append(WebUi.button("📦 Criar o devcontainer", "dc-create", "primary", enabled = root != null, title = "Grava ${DevContainer.CONFIG} com os CLIs de IA e o isolamento"))
        } else {
            append(WebUi.button("📄 Abrir devcontainer.json", "dc-open", "ghost"))
            append(WebUi.button(if (containerBusy) "⏳ Subindo…" else "▶ Subir e conferir", "dc-up", enabled = cli != null && !containerBusy && missions.progress == null,
                title = "Sobe o container (a primeira vez demora) e confere o isolamento"))
        }
        append("</div>")
        status(containerStatus)
        append("</div>")
    }

    private fun containerUp(root: Path) {
        containerBusy = true
        containerStatus = Status("Subindo o devcontainer (a primeira vez demora)…", null)
        refresh(force = true)
        var shown = 0L
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = runCatching {
                DevContainer.up(root, { line ->
                    val now = System.currentTimeMillis()
                    if (now - shown > 1_000) {
                        shown = now
                        ApplicationManager.getApplication().invokeLater({
                            if (containerBusy) containerStatus = Status("Subindo: $line", null)
                            refresh()
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
                refresh(force = true)
            }, project.disposed)
        }
    }

    private fun guardCard(): String = buildString {
        val on = TomahawkSettings.guardrails
        val cursor = AgentSettings.provider == AgentProvider.CURSOR
        append("<div class=\"card2\"><h3>🛡️ Proteções das missões</h3><div class=\"desc\">O que impede a IA, rodando sozinha, de danificar a máquina ou perder o seu trabalho.</div>")
        append("<div class=\"field\">").append(WebUi.seg("guard", listOf("on" to "🛡️ Ligadas", "off" to "Desligadas"), if (on) "on" else "off", send = "guard")).append("</div>")
        if (!on) {
            append(notice("err", "⚠️", "Com as proteções desligadas, a IA roda sem as regras do Tomahawk (no devcontainer e no terminal) e as missões não têm ponto de restauração."))
        }
        append("<ul class=\"caps\">")
        listOf(
            "<b>Barrado antes de rodar</b>: apagar pastas inteiras, formatar discos, desligar a máquina, pedir administrador, mudar o sistema, " +
                "instalar programas, rodar script baixado da internet, <code>git push</code>/<code>reset --hard</code>/<code>clean</code>. " +
                (if (cursor) "Fica no <code>.cursor/cli.json</code> do projeto." else "Vai no <code>--settings</code> do Claude Code, que também não escreve fora do projeto."),
            "<b>Vigia</b>: o Tomahawk lê cada passo da IA e aborta a missão num comando perigoso, numa escrita fora do projeto ou dentro do <code>.git</code>, " +
                "ou numa leitura de credenciais (<code>~/.ssh</code>, <code>~/.aws</code>…).",
            "<b>Ponto de restauração</b>: antes de cada missão, o projeto inteiro vai para o Git (em <code>refs/tomahawk/</code>, sem mexer na sua branch), e dá para desfazer.",
            "<b>Sem segredos</b>: tokens e chaves do ambiente (GitHub, nuvem, npm) não chegam ao processo da IA.",
        ).forEach { text ->
            append("<li><span class=\"ck ").append(if (on) "on\">✓" else "off\">○").append("</span><span>").append(text).append("</span></li>")
        }
        append("</ul><div class=\"row-btns\" style=\"margin-top:10px\">")
        append(WebUi.button("↩ Desfazer a última missão", "undo", "ghost", enabled = missions.progress == null,
            title = "Volta o projeto para o ponto de restauração da última missão, depois de mostrar o que mudou"))
        append("</div></div>")
    }

    private fun specKitCard(): String = buildString {
        val s = service.snapshot
        val specify = TomahawkSettings.resolveSpecify()
        val info = SpecKitSetup.infoFor(specify)
        val installing = SpecKitSetup.job != null
        val integration = TomahawkSettings.provider.integration
        append("<div class=\"card2\"><h3>🌱 Spec Kit</h3><div class=\"desc\">O CLI <code>specify</code> nesta máquina e os comandos instalados neste projeto.</div>")
        append("<div class=\"chips\" style=\"margin:0 0 10px\">")
        append(
            when {
                specify == null -> WebUi.chip("Máquina", "specify não instalado", "red")
                info?.broken == true -> WebUi.chip("Máquina", "specify com defeito", "red")
                info?.outdated == true -> WebUi.chip("Máquina", "specify v${esc(info.version ?: "?")} · nova v${esc(info.latest.orEmpty())}", "orange")
                else -> WebUi.chip("Máquina", "specify ${info?.version?.let { "v" + esc(it) } ?: ""} ✓", "green")
            },
        )
        append(if (s.initialized) WebUi.chip("Projeto", "inicializado", "green") else WebUi.chip("Projeto", "sem .specify/", "yellow"))
        s.integration.key?.let { append(WebUi.chip("Integração", esc(it))) }
        if (s.initialized) append(WebUi.chip("Comandos", "${s.commands.size}/${SddStep.entries.size}", if (s.commands.size == SddStep.entries.size) "green" else "orange"))
        append("</div>")
        specify?.let { append("<div class=\"status-line\">").append(esc(it.toString())).append("</div>") }
        append(SpecKitWeb.machine(specify))
        val key = s.integration.key
        if (s.initialized && key != null && key != integration && TomahawkSettings.supported) {
            append("<div class=\"notice warn\"><span class=\"ni\">⚠️</span><div class=\"nb\">O projeto foi inicializado para <b>").append(esc(key))
            append("</b>, mas a IA configurada é o <b>").append(esc(TomahawkSettings.provider.title)).append("</b>, e os comandos dele podem não estar instalados.")
            append("<div class=\"cmdline\" style=\"margin:8px 0 0\">specify integration install ").append(esc(integration)).append("</div><div class=\"row-btns\">")
            append(WebUi.button("🔌 Instalar a integração ${esc(integration)}", "specify-integration", enabled = specify != null && !installing && info?.broken != true))
            append("</div></div></div>")
        }
        if (!s.initialized) {
            append("<div class=\"status-line\" style=\"margin-top:12px\">🛰️ Este projeto ainda não tem o Spec Kit (a pasta <b>.specify/</b>).</div>")
            append(SpecKitWeb.initBox(specify))
        } else {
            SpecKitWeb.shellPicker().takeIf { it.isNotEmpty() }?.let { append("<div style=\"margin-top:12px\">").append(it).append("</div>") }
        }
        if (specify != null && !installing) {
            append("<div class=\"row-btns\" style=\"margin-top:10px\">")
            append(WebUi.button("↻ Verificar de novo", "specify-recheck", "ghost", title = "Procura o specify de novo, relê a versão e o projeto"))
            append("</div>")
        }
        append("</div>")
    }

    private fun attackCapsCard(): String = buildString {
        val ready = TomahawkSettings.ready
        val terminal = LaunchTerminal.available() != null
        append("<div class=\"card2\"><h3>✨ O que a IA libera no Ataque</h3><ul class=\"caps\">")
        listOf(
            ready to "<b>Lançador</b>: as nove etapas do SDD na aba, monitoradas, ou no terminal",
            (ready && terminal) to "<b>Terminal</b>: conversar com a IA nas etapas que fazem perguntas",
            ready to "<b>Avaliação</b>: Simulação de Rota (analyze) e Avaliação de Danos (converge) num clique",
            ready to "<b>Extração e Rádio</b>: a mensagem do commit, o texto do PR e as respostas aos comentários",
            true to "<b>Telemetria</b>: cada passo da IA ao vivo e o registro de cada missão",
        ).forEach { (on, text) ->
            append("<li><span class=\"ck ").append(if (on) "on\">✓" else "off\">○").append("</span><span>").append(text).append("</span></li>")
        }
        append("</ul>")
        if (!ready) {
            val why = if (!TomahawkSettings.supported) "Desligadas: o CLI escolhido não lança missões do Spec Kit." else "Desligadas até a integração ser testada com a configuração atual."
            append("<div class=\"status-line\">").append(why).append("</div>")
        }
        append("</div>")
    }

    private fun runs(): List<Run> {
        val defense = if (IronHawkSystem.DEFENSE.enabled) defenseRuns else emptyList()
        val attack = if (IronHawkSystem.ATTACK.enabled) {
            service.snapshot.runs.map { record ->
                Run(record.file, record.title, record.status.label, record.ok, IronHawkSystem.ATTACK, record.startedAt)
            }
        } else {
            emptyList()
        }
        return (defense + attack).sortedByDescending { it.time }.take(InterceptorPanel.MAX_RUNS)
    }

    private fun runsCard(): String = buildString {
        val runs = runs()
        append("<div class=\"card2\"><div class=\"desc\">O pedido, os passos, os arquivos alterados e a resposta de cada execução: as da Defesa em ")
        append("<code>iron-dome/ia/</code> e as missões do Ataque em <code>tomahawk/execucoes/</code>.</div>")
        if (runs.isEmpty()) {
            append("<div class=\"empty2\">Nenhuma execução da IA neste projeto ainda</div>")
        } else {
            runs.forEachIndexed { index, run ->
                val tag = if (run.system == IronHawkSystem.DEFENSE) WebUi.chip("", "🛡️ Defesa", "blue") else WebUi.chip("", "🚀 Ataque", "orange")
                append("<div class=\"run ").append(if (run.ok) "r-ok" else "r-failed").append("\" data-cmd=\"openRun\" data-arg=\"$index\">")
                append("<div class=\"run-icon\">").append(if (run.ok) "✓" else "!").append("</div><div class=\"run-body\">")
                append("<div class=\"run-title\">").append(esc(run.title)).append("</div>")
                append("<div class=\"run-meta\">").append(tag).append("<span>").append(esc(run.status)).append("</span><span>")
                    .append(esc(run.path.fileName.toString().take(15))).append("</span></div>")
                append("</div></div>")
            }
        }
        append("</div>")
    }

    private fun notice(tone: String, icon: String, html: String): String =
        "<div class=\"notice $tone\"><span class=\"ni\">$icon</span><div class=\"nb\">$html</div></div>"

    private fun StringBuilder.status(status: Status?) {
        status ?: return
        val tone = when (status.ok) {
            true -> " good"
            false -> " bad"
            null -> ""
        }
        append("<div class=\"status-line$tone\">").append(esc(status.text)).append("</div>")
    }

    private fun readForm(data: JsonObject) {
        draft = draft.copy(
            provider = AgentProvider.entries.firstOrNull { it.name == data.str("provider") } ?: draft.provider,
            host = AgentHost.entries.firstOrNull { it.name == data.str("host") } ?: draft.host,
            distro = data.str("distro")?.ifBlank { null } ?: draft.distro,
            executable = data.str("executable") ?: draft.executable,
            model = data.str("model") ?: draft.model,
            timeout = data.int("timeout") ?: draft.timeout,
            extra = data.str("extra") ?: draft.extra,
        )
    }

    private fun save(key: String?, test: Boolean) {
        saving = true
        val d = draft
        AgentSettings.provider = d.provider
        AgentSettings.host = if (AgentLaunch.wslSupported) d.host else AgentHost.LOCAL
        AgentSettings.wslDistro = d.distro
        AgentSettings.executable = d.executable
        AgentSettings.model = d.model
        AgentSettings.timeoutMinutes = d.timeout
        AgentSettings.extraArgs = d.extra
        testStatus = Status(if (test) "Testando o ${d.provider.title}…" else "Salvo.", if (test) null else true)
        refresh(force = true)
        ApplicationManager.getApplication().executeOnPooledThread {
            if (!key.isNullOrBlank()) AgentSettings.cursorApiKey = key
            ApplicationManager.getApplication().invokeLater({
                saving = false
                draft = Draft.current()
                if (!test) return@invokeLater refresh(force = true)
                AgentVerifier.test { outcome ->
                    testStatus = Status(outcome.message, outcome.ok)
                    refresh(force = true)
                }
                refresh(force = true)
            }, project.disposed)
        }
    }

    private fun autoSetup() {
        autoFailure = null
        AgentAutoSetup.run(
            onProgress = { message ->
                autoStatus = Status(message, null)
                refresh(force = true)
            },
            onDone = { result ->
                draft = Draft.current()
                autoStatus = Status(if (result.ok) result.message else "Não deu para configurar sozinho: termine nos campos do CLI.", result.ok)
                autoFailure = result.message.takeUnless { result.ok }
                testStatus = null
                refresh(force = true)
            },
        )
        refresh(force = true)
    }

    override fun onMessage(command: String, data: JsonObject) {
        when (command) {
            "form" -> {
                readForm(data)
                refresh(force = true)
            }
            "browse" -> {
                val descriptor = FileChooserDescriptorFactory.createSingleFileNoJarsDescriptor().withTitle("Executável do CLI de IA")
                FileChooser.chooseFile(descriptor, project, null)?.let {
                    readForm(data)
                    draft = draft.copy(executable = it.path)
                    refresh(force = true)
                }
            }
            "save" -> {
                readForm(data)
                save(data.str("key"), test = data.str("arg") == "test")
            }
            "clearKey" -> ApplicationManager.getApplication().executeOnPooledThread { AgentSettings.cursorApiKey = null }
            "auto" -> autoSetup()
            "terminal" -> {
                terminalStatus = Status("Abrindo o terminal e esperando a IA gravar OK…", null)
                refresh(force = true)
                AgentVerifier.testTerminal(project) { outcome ->
                    terminalStatus = Status(outcome.message, outcome.ok)
                    refresh(force = true)
                }
            }
            "confirmWrites" -> AgentSettings.confirmWrites = true
            "defense-runmode" -> AgentRunMode.entries.firstOrNull { it.name == data.str("value") }?.let { AgentSettings.runMode = it }
            "attack-runmode" -> RunMode.entries.firstOrNull { it.name == data.str("value") }?.let { TomahawkSettings.runMode = it }
            "openRun" -> data.str("arg")?.toIntOrNull()?.let(runs()::getOrNull)?.let { run ->
                if (run.system == IronHawkSystem.DEFENSE) agent.open(run.path) else missions.open(run.path)
            }
            "init" -> missions.initSpecKit()
            "init-copy" -> SpecKitSetup.copyInit(project)
            "specify-shell" -> {
                SpecKitWeb.choose(data.str("value"))
                refresh(force = true)
            }
            "specify-repair" -> SpecKitSetup.repair(project)
            "specify-install" -> SpecKitSetup.install(project)
            "specify-upgrade" -> SpecKitSetup.upgrade(project)
            "specify-recheck" -> {
                SpecKitSetup.recheck()
                service.refresh(0)
            }
            "specify-integration" -> SpecKitSetup.installIntegration(project)
            "guard" -> {
                val on = data.str("value") == "on"
                TomahawkSettings.guardrails = on
                service.root()?.let { root ->
                    ApplicationManager.getApplication().executeOnPooledThread { runCatching { Guardrails.applyCursorConfig(root, on) } }
                }
                refresh(force = true)
            }
            "undo" -> missions.undo()
            "dc-create" -> service.root()?.let { root ->
                containerStatus = runCatching { DevContainer.create(root) }.fold(
                    onSuccess = {
                        missions.open(it)
                        Status("Criado em ${DevContainer.CONFIG}. Acrescente as ferramentas do projeto em features, se a IA precisar delas.", true)
                    },
                    onFailure = { Status("Não deu para criar: ${it.message ?: it.javaClass.simpleName}", false) },
                )
                refresh(force = true)
            }
            "dc-open" -> service.root()?.let { missions.open(DevContainer.config(it)) }
            "dc-up" -> service.root()?.let(::containerUp)
        }
    }

    private companion object {
        /** Duas colunas que ocupam a largura toda; numa aba estreita, uma embaixo da outra. */
        const val GRID = "<div class=\"grid2\" style=\"align-items:start;grid-template-columns:repeat(auto-fit,minmax(min(320px,100%),1fr))\"><div>"

        val DEFENSE_CAPABILITIES = listOf(
            "Radar e barra do editor" to "Disparar Todos com IA, ou Disparar Selecionados e Disparar no cartão do alvo: a IA executa a ordem de interceptação e marca os alvos atendidos",
            "Patrulha" to "Revisar com IA (no modo revisão): comentários da IA como rascunhos do PR",
            "Escudo" to "Explicar Falha com IA e, na resposta, Corrigir com IA",
            "Raio de Impacto" to "Analisar Impacto com IA: as classes que os comentários e as alterações afetam",
            "Caixa-Preta" to "Analisar com IA, no bug gerado e em Ver Logs de Erro",
        )
    }
}
