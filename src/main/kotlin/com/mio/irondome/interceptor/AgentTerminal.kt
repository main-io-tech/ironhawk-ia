package com.mio.irondome.interceptor

import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.project.Project

/**
 * O terminal da IDE, para a IA rodar à vista do usuário. Vem do plugin de terminal, que é opcional: sem ele,
 * não há extensão e a IA roda na aba.
 */
interface AgentTerminal {

    /**
     * O shell que o terminal do projeto abre, como `powershell.exe` ou `/bin/zsh`. Fora da EDT: o plugin de
     * terminal resolve o shell bloqueando a thread.
     */
    fun shellPath(project: Project): String?

    /**
     * Na EDT. Abre uma aba nova do terminal em [workDir], chamada [title], e executa [command] nela. A sessão diz quando
     * a aba foi fechada ou o shell terminou, para quem espera a IA não ficar esperando à toa.
     */
    fun run(project: Project, workDir: String, title: String, command: String): Session

    /** A aba do terminal aberta por [run]. */
    fun interface Session {
        /** A aba foi fechada ou o shell dela terminou. */
        fun ended(): Boolean
    }

    companion object {
        val EP = ExtensionPointName<AgentTerminal>("com.mio.ironhawk.agentTerminal")

        fun available(): AgentTerminal? = EP.extensionList.firstOrNull()
    }
}

/** Por que a espera da resposta no terminal parou antes de a resposta chegar. */
object TerminalEnd {

    /** [exited]: o marcador de fim apareceu, com [code] (o código de saída, quando o shell o grava). Sem ele, a aba fechou. */
    fun describe(exited: Boolean, code: String?): String {
        if (!exited) return "O terminal foi fechado antes de a resposta chegar."
        val number = code?.trim()?.toIntOrNull()
        val how = if (number != null && number != 0) " com o código $number" else ""
        return "O CLI terminou$how sem gravar a resposta. Veja a mensagem na aba do terminal."
    }
}
