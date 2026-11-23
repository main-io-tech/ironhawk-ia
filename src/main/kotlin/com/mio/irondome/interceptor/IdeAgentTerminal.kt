package com.mio.irondome.interceptor

import com.intellij.openapi.project.Project
import java.util.concurrent.atomic.AtomicBoolean
import org.jetbrains.plugins.terminal.TerminalProjectOptionsProvider
import org.jetbrains.plugins.terminal.TerminalToolWindowManager

/** [AgentTerminal] pelo plugin de terminal da IDE; registrado em `META-INF/ironhawk-terminal.xml`. */
class IdeAgentTerminal : AgentTerminal {

    override fun shellPath(project: Project): String? =
        runCatching { TerminalProjectOptionsProvider.getInstance(project).shellPath }.getOrNull()

    override fun run(project: Project, workDir: String, title: String, command: String): AgentTerminal.Session {
        // Sem adiar a sessão: com um diálogo aberto o terminal não aparece, e o comando ficaria esperando.
        val widget = TerminalToolWindowManager.getInstance(project).createShellWidget(workDir, title, true, false)
        val ended = AtomicBoolean(false)
        runCatching { widget.addTerminationCallback({ ended.set(true) }, widget) }
        widget.sendCommandToExecute(command)
        return AgentTerminal.Session { ended.get() }
    }
}
