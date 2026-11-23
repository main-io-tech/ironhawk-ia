package com.mio.irondome.interceptor

import com.intellij.execution.util.ExecUtil
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.SystemInfo
import com.mio.irondome.base.rootPath
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Testa a integração com a configuração salva: uma pergunta curta, sem alterar arquivos, que confirma o
 * executável, o login e o modelo. Se passar, a IA fica marcada como testada e os botões de IA se habilitam.
 */
object AgentVerifier {

    class Outcome(val ok: Boolean, val message: String)

    private const val TIMEOUT_MS = 120_000
    private val DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")

    @Volatile
    var testing = false
        private set

    /** [onDone] roda na EDT. */
    fun test(onDone: (Outcome) -> Unit) {
        val provider = AgentSettings.provider
        val launch = AgentSettings.launch()
        if (launch == null) {
            AgentSettings.clearVerified()
            val missing = if (AgentSettings.host == AgentHost.WSL) "Escolha a distribuição do WSL." else "Executável não encontrado. ${provider.installHint}"
            onDone(Outcome(false, missing))
            return
        }
        val model = AgentSettings.model
        testing = true
        ApplicationManager.getApplication().executeOnPooledThread {
            val key = if (provider == AgentProvider.CURSOR) AgentSettings.cursorApiKey else null
            val workspace = System.getProperty("user.home")
            val outcome = runCatching {
                val task = AgentTask("Teste", "Responda apenas OK", write = false)
                val command = AgentCommand.build(
                    provider, launch.executable, task, launch.workspace(workspace), model, 2, AgentCommand.splitArgs(AgentSettings.extraArgs),
                )
                ExecUtil.execAndGetOutput(launch.process(command, workspace, key, provider), TIMEOUT_MS)
            }.fold(
                onSuccess = { output ->
                    val events = output.stdoutLines.flatMap(AgentEvents::parseAll)
                    val answer = events.filterIsInstance<AgentEvent.Result>().firstOrNull()
                    val model = events.filterIsInstance<AgentEvent.Init>().firstOrNull()?.model
                    val reported = answer?.takeIf { it.error }?.text?.takeIf { it.isNotBlank() }
                    when {
                        output.isTimeout -> Outcome(false, "Sem resposta em ${TIMEOUT_MS / 60_000} minutos.")
                        output.exitCode != 0 -> Outcome(
                            false,
                            explain(provider, reported ?: output.stderrLines.lastOrNull { it.isNotBlank() } ?: "O CLI terminou com o código ${output.exitCode}."),
                        )
                        answer == null -> Outcome(
                            false,
                            "O CLI respondeu, mas sem o resultado esperado. Confira se ele está atualizado" +
                                (output.stdoutLines.lastOrNull { it.isNotBlank() }?.let { ": a última linha foi \"${it.take(120)}\"" } ?: "."),
                        )
                        answer.error -> Outcome(false, explain(provider, answer.text.ifBlank { "A IA informou um erro." }))
                        else -> Outcome(true, "${provider.title}${launch.where}${model?.let { " · modelo $it" }.orEmpty()} respondeu: ${answer.text.trim().take(40)}")
                    }
                },
                onFailure = { Outcome(false, it.message ?: it.javaClass.simpleName) },
            )
            ApplicationManager.getApplication().invokeLater({
                testing = false
                if (outcome.ok) AgentSettings.markVerified(LocalDateTime.now().format(DATE)) else AgentSettings.clearVerified()
                onDone(outcome)
            }, ModalityState.any())
        }
    }

    /** O erro do CLI com o que fazer, quando ele é conhecido, como o login que falta. */
    fun explain(provider: AgentProvider, message: String): String = when {
        AUTH_REQUIRED.containsMatchIn(message) -> when (provider) {
            AgentProvider.ANTIGRAVITY -> "$message. Falta entrar no Antigravity: rode agy uma vez no terminal e entre com a conta Google, depois teste de novo."
            AgentProvider.CURSOR -> "$message. Falta entrar no Cursor: rode agent login no terminal ou informe uma chave de API, depois teste de novo."
            AgentProvider.CLAUDE -> "$message. Falta entrar no Claude Code: rode claude uma vez no terminal e faça o login, depois teste de novo."
            AgentProvider.CODEX -> "$message. Falta entrar no Codex: rode codex login no terminal ou defina OPENAI_API_KEY, depois teste de novo."
        }
        else -> message
    }

    private val AUTH_REQUIRED = Regex("""authenticat|not logged in|login required|unauthori[sz]ed""", RegexOption.IGNORE_CASE)

    @Volatile
    var testingTerminal = false
        private set

    /**
     * Testa o caminho do terminal da IDE com a configuração salva, no Windows ou no WSL: abre uma aba do terminal,
     * pede à IA que grave OK num arquivo e espera o arquivo. Não muda o teste principal, que habilita os botões.
     * [onDone] roda na EDT.
     */
    fun testTerminal(project: Project, onDone: (Outcome) -> Unit) {
        val terminal = AgentTerminal.available()
            ?: return onDone(Outcome(false, "O plugin de terminal da IDE está desligado."))
        val provider = AgentSettings.provider
        val launch = AgentSettings.launch()
            ?: return onDone(Outcome(false, if (AgentSettings.host == AgentHost.WSL) "Escolha a distribuição do WSL." else "Executável não encontrado."))
        val root = project.rootPath() ?: return onDone(Outcome(false, "O projeto não tem pasta no disco."))
        val dir = AgentRunner.getInstance(project).transcriptsDir()?.resolve(TERMINAL_TEST_DIR)
            ?: return onDone(Outcome(false, "O projeto não tem pasta no disco."))
        val file = dir.resolve("teste-terminal-${System.currentTimeMillis()}.md")
        val relative = Path.of(root).relativize(file).toString().replace('\\', '/')
        testingTerminal = true
        ApplicationManager.getApplication().executeOnPooledThread {
            val shellPath = terminal.shellPath(project)
            val shell = ShellKind.of(shellPath, SystemInfo.isWindows)
            val prompt = "Teste de integracao do IronHawk IA: grave apenas a palavra OK no arquivo $relative (crie as pastas se faltarem) e termine, sem fazer mais nada."
            val exit = file.resolveSibling("${file.fileName}.fim")
            val command = runCatching {
                AgentCommand.withExitMarker(launch.terminal(provider, prompt, AgentSettings.model, 2, shell, root, interactive = false), exit.toString(), shell)
            }
            ApplicationManager.getApplication().invokeLater({
                val session = try {
                    Files.createDirectories(dir)
                    terminal.run(project, root, "IA: Teste", command.getOrThrow())
                } catch (e: Exception) {
                    testingTerminal = false
                    onDone(Outcome(false, "O terminal não abriu: ${e.message ?: e.javaClass.simpleName}"))
                    return@invokeLater
                }
                val shellName = shellPath?.replace('\\', '/')?.substringAfterLast('/') ?: shell.name.lowercase()
                waitForTerminalAnswer(file, exit, session, provider, launch, shellName, onDone)
            }, ModalityState.any())
        }
    }

    /** Espera o OK; para antes do tempo limite se o CLI terminar ([exit] aparece) ou a aba do terminal fechar sem ele. */
    private fun waitForTerminalAnswer(
        file: Path,
        exit: Path,
        session: AgentTerminal.Session,
        provider: AgentProvider,
        launch: AgentLaunch,
        shellName: String,
        onDone: (Outcome) -> Unit,
    ) {
        ApplicationManager.getApplication().executeOnPooledThread {
            val started = System.currentTimeMillis()
            var answer: String? = null
            var endedAt = 0L
            var ended: String? = null
            while (System.currentTimeMillis() - started < TIMEOUT_MS) {
                if (Files.isRegularFile(file)) {
                    answer = runCatching { Files.readString(file).trim() }.getOrNull()?.takeIf { it.isNotEmpty() }
                    if (answer != null) break
                }
                if (endedAt == 0L && (Files.exists(exit) || session.ended())) endedAt = System.currentTimeMillis()
                if (endedAt > 0 && System.currentTimeMillis() - endedAt > EXIT_GRACE_MS) {
                    ended = TerminalEnd.describe(Files.exists(exit), runCatching { Files.readString(exit) }.getOrNull())
                    break
                }
                Thread.sleep(1_000)
            }
            runCatching { Files.deleteIfExists(file) }
            runCatching { Files.deleteIfExists(exit) }
            val seconds = (System.currentTimeMillis() - started) / 1000
            val outcome = when {
                answer == null && ended != null -> Outcome(false, "$ended Confira a aba \"IA: Teste\": o comando, o login e o PATH do $shellName.")
                answer == null -> Outcome(
                    false,
                    "A resposta não chegou em ${TIMEOUT_MS / 60_000} minutos. Confira a aba \"IA: Teste\" do terminal: o comando, o login e o PATH do $shellName.",
                )
                else -> Outcome(true, "${provider.title}${launch.where} respondeu no terminal ($shellName) em $seconds s.")
            }
            ApplicationManager.getApplication().invokeLater({
                testingTerminal = false
                if (outcome.ok) AgentSettings.terminalVerifiedAt = LocalDateTime.now().format(DATE)
                onDone(outcome)
            }, ModalityState.any())
        }
    }

    private const val TERMINAL_TEST_DIR = "respostas"
    private const val EXIT_GRACE_MS = 4_000L
}
