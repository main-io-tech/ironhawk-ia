package com.mio.tomahawk.run

import com.intellij.execution.configurations.PathEnvironmentVariableUtil
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.util.SystemInfo
import com.intellij.util.messages.Topic
import com.mio.irondome.interceptor.AgentSettings
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import com.mio.irondome.interceptor.AgentHost as SharedHost
import com.mio.irondome.interceptor.AgentProvider as SharedProvider
import com.mio.irondome.interceptor.AgentRunMode as SharedRunMode

/**
 * O que o Tomahawk usa da IA. O CLI, onde ele está, o modelo, o tempo, os argumentos, a chave do Cursor, onde rodar e
 * o teste são os do Arsenal, comuns aos dois sistemas ([AgentSettings]); aqui ficam só os do Ataque: o `specify`, o
 * shell do Spec Kit e as proteções das missões. As missões só saem depois que o teste passa com a configuração de
 * agora e com um CLI que o Spec Kit conhece.
 */
object TomahawkSettings {

    private const val SPECIFY_KEY = "Tomahawk.Specify.Executable"
    private const val SPECIFY_SHELL_KEY = "Tomahawk.Specify.Shell"
    private const val GUARDRAILS_KEY = "Tomahawk.Agent.Guardrails"

    const val DEFAULT_TIMEOUT_MINUTES = AgentSettings.DEFAULT_TIMEOUT_MINUTES

    fun interface Listener {
        fun changed()

        companion object {
            @Topic.AppLevel
            val TOPIC = Topic(Listener::class.java, Topic.BroadcastDirection.NONE)
        }
    }

    private val props: PropertiesComponent get() = PropertiesComponent.getInstance()

    /** O CLI do Arsenal, se o Spec Kit tem integração para ele; o Antigravity não tem, e aí vale o Cursor nos textos. */
    var provider: AgentProvider
        get() = AgentProvider.entries.firstOrNull { it.name == AgentSettings.provider.name } ?: AgentProvider.CURSOR
        set(value) {
            AgentSettings.provider = SharedProvider.valueOf(value.name)
        }

    /** O CLI escolhido no Arsenal roda as missões: falso com o Antigravity, que o Spec Kit não conhece. */
    val supported: Boolean get() = AgentProvider.entries.any { it.name == AgentSettings.provider.name }

    var host: AgentHost
        get() = AgentHost.valueOf(AgentSettings.host.name)
        set(value) {
            AgentSettings.host = SharedHost.valueOf(value.name)
        }

    /** A distribuição do WSL, como `Ubuntu`, quando [host] é o WSL. */
    var wslDistro: String?
        get() = AgentSettings.wslDistro
        set(value) {
            AgentSettings.wslDistro = value
        }

    /** Caminho do executável (no WSL, o caminho do Linux); vazio procura sozinho. */
    var executable: String?
        get() = AgentSettings.executable
        set(value) {
            AgentSettings.executable = value
        }

    var model: String?
        get() = AgentSettings.model
        set(value) {
            AgentSettings.model = value
        }

    var timeoutMinutes: Int
        get() = AgentSettings.timeoutMinutes
        set(value) {
            AgentSettings.timeoutMinutes = value
        }

    /** Argumentos a mais para o CLI, antes do prompt, como `--approve-mcps`. */
    var extraArgs: String?
        get() = AgentSettings.extraArgs
        set(value) {
            AgentSettings.extraArgs = value
        }

    /** Onde rodar a IA, o do Arsenal: na aba, as missões rodam no devcontainer. */
    var runMode: RunMode
        get() = RunMode.valueOf(AgentSettings.runMode.name)
        set(value) {
            AgentSettings.runMode = SharedRunMode.valueOf(value.name)
        }

    /** Caminho do `specify`; vazio procura no PATH e em `~/.local/bin`, onde o `uv tool install` o deixa. */
    var specifyExecutable: String?
        get() = props.getValue(SPECIFY_KEY)?.takeIf { it.isNotBlank() }
        set(value) = change { props.setValue(SPECIFY_KEY, value?.trim()?.takeIf { it.isNotEmpty() }) }

    /** Onde rodam os comandos do Spec Kit no Windows: nulo é o shell padrão do terminal da IDE. */
    var specKitShell: WindowsShell?
        get() = WindowsShell.entries.firstOrNull { it.name == props.getValue(SPECIFY_SHELL_KEY) }
        set(value) = change { props.setValue(SPECIFY_SHELL_KEY, value?.name) }

    /** As proteções da máquina nas missões ([Guardrails] e [Checkpoints]); ligadas por padrão. */
    var guardrails: Boolean
        get() = props.getBoolean(GUARDRAILS_KEY, true)
        set(value) = change { props.setValue(GUARDRAILS_KEY, value, true) }

    var autoSetupTried: Boolean
        get() = AgentSettings.autoSetupTried
        set(value) {
            AgentSettings.autoSetupTried = value
        }

    val untouched: Boolean get() = AgentSettings.untouched

    /** Opcional: sem ela, o Cursor CLI usa o login do `agent login`. Fora da EDT. */
    var cursorApiKey: String?
        get() = AgentSettings.cursorApiKey
        set(value) {
            AgentSettings.cursorApiKey = value
        }

    val hasCursorApiKey: Boolean get() = AgentSettings.hasCursorApiKey

    @Volatile
    private var readyCache: Boolean? = null

    /** A IA pode lançar missões: um CLI que o Spec Kit conhece, encontrado e testado com a configuração de agora. */
    val ready: Boolean
        get() = readyCache ?: (launch() != null && verified).also { readyCache = it }

    val verified: Boolean get() = supported && AgentSettings.verified

    val verifiedAt: String? get() = AgentSettings.verifiedAt?.takeIf { supported }

    fun fingerprint(): String = AgentSettings.fingerprint()

    fun markVerified(at: String) = AgentSettings.markVerified(at)

    fun clearVerified() = AgentSettings.clearVerified()

    /** Quando o teste pelo terminal passou com a configuração de agora; nulo se nunca, ou se ela mudou. */
    var terminalVerifiedAt: String?
        get() = AgentSettings.terminalVerifiedAt
        set(value) {
            AgentSettings.terminalVerifiedAt = value
        }

    /** O Arsenal mudou: as telas do Tomahawk se atualizam como se a mudança fosse daqui. */
    fun sharedChanged() {
        readyCache = null
        ApplicationManager.getApplication()?.messageBus?.syncPublisher(Listener.TOPIC)?.changed()
    }

    private fun change(update: () -> Unit) {
        update()
        sharedChanged()
    }

    /**
     * Como rodar o CLI com a configuração dada; nulo se ele não existe nesta máquina, falta a distribuição do WSL ou
     * o CLI do Arsenal não roda missões. No WSL não roda nada: sem caminho configurado, vale o nome do comando.
     */
    fun launch(
        provider: AgentProvider = this.provider,
        configured: String? = executable,
        host: AgentHost = this.host,
        distro: String? = wslDistro,
    ): AgentLaunch? {
        if (!supported && provider == this.provider) return null
        return when (host) {
            AgentHost.LOCAL -> resolveExecutable(provider, configured)?.let(AgentLaunch::local)
            AgentHost.WSL -> distro?.takeIf { AgentLaunch.wslSupported }?.let { AgentLaunch.wsl(it, configured ?: provider.executables.first()) }
        }
    }

    /** O executável configurado, se existir, ou o encontrado no PATH e nas pastas de instalação. */
    fun resolveExecutable(provider: AgentProvider = this.provider, configured: String? = executable): Path? {
        configured?.let { path -> return Path.of(path).takeIf(Files::isRegularFile) }
        return find(provider)
    }

    fun find(provider: AgentProvider): Path? = find(provider.executables, installDirs(provider))

    fun resolveSpecify(configured: String? = specifyExecutable): Path? {
        configured?.let { path -> return Path.of(path).takeIf(Files::isRegularFile) }
        val home = System.getProperty("user.home")
        return find(listOf("specify"), listOf(File(home, ".local/bin")))
    }

    /** O `uv`, que instala o `specify`: no PATH ou onde o instalador da Astral o deixa. */
    fun resolveUv(): Path? {
        val home = System.getProperty("user.home")
        return find(listOf("uv"), listOf(File(home, ".local/bin"), File(home, ".cargo/bin")))
    }

    private fun find(executables: List<String>, dirs: List<File>): Path? {
        val names = executables.flatMap { name ->
            if (SystemInfo.isWindows) listOf("$name.exe", "$name.cmd", "$name.bat", name) else listOf(name)
        }
        names.firstNotNullOfOrNull { PathEnvironmentVariableUtil.findInPath(it) }?.let { return it.toPath() }
        return dirs.flatMap { dir -> names.map { File(dir, it) } }.firstOrNull { it.isFile }?.toPath()
    }

    /** Onde os instaladores costumam deixar o CLI quando a IDE não herdou o PATH do terminal. */
    private fun installDirs(provider: AgentProvider): List<File> {
        val home = System.getProperty("user.home")
        val localAppData = System.getenv("LOCALAPPDATA")
        val appData = System.getenv("APPDATA")
        return when (provider) {
            AgentProvider.CURSOR -> listOfNotNull(
                File(home, ".local/bin"),
                File(home, ".cursor/bin"),
                localAppData?.let { File(it, "cursor-agent") },
                localAppData?.let { File(it, "Programs/cursor-agent") },
            )
            AgentProvider.CLAUDE -> listOfNotNull(
                File(home, ".local/bin"),
                File(home, ".claude/local"),
                appData?.let { File(it, "npm") },
            )
        }
    }
}

/** Repassa ao Tomahawk as mudanças do Arsenal comum; registrado em `plugin.xml`. */
class SharedArsenalListener : AgentSettings.Listener {
    override fun changed() = TomahawkSettings.sharedChanged()
}
