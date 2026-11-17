package com.mio.tomahawk.run

/**
 * O que o Ataque usa da IA nas mensagens e no ambiente: a chave do Cursor e a explicação dos erros conhecidos do CLI.
 * O teste da integração é o do Arsenal ([com.mio.irondome.interceptor.AgentVerifier]), comum aos dois sistemas.
 */
object AgentVerifier {

    private val AUTH_REQUIRED = Regex("""authenticat|not logged in|login required|unauthori[sz]ed|invalid api key|/login""", RegexOption.IGNORE_CASE)

    /** A chave do Cursor no ambiente, se houver; fora da EDT, porque lê o cofre de senhas. */
    fun apiKeyEnv(provider: AgentProvider): Map<String, String> =
        if (provider == AgentProvider.CURSOR) TomahawkSettings.cursorApiKey?.let { mapOf("CURSOR_API_KEY" to it) }.orEmpty() else emptyMap()

    /** O erro do CLI com o que fazer, quando ele é conhecido, como o login que falta. */
    fun explain(provider: AgentProvider, message: String): String = when {
        AUTH_REQUIRED.containsMatchIn(message) -> when (provider) {
            AgentProvider.CURSOR -> "$message. Falta entrar no Cursor: rode agent login no terminal ou informe uma chave de API, depois teste de novo."
            AgentProvider.CLAUDE -> "$message. Falta entrar no Claude Code: rode claude uma vez no terminal e faça o login, depois teste de novo."
            AgentProvider.CODEX -> "$message. Falta entrar no Codex: rode codex login no terminal ou defina OPENAI_API_KEY, depois teste de novo."
        }
        else -> message
    }
}
