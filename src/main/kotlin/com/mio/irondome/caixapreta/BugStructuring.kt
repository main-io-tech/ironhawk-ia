package com.mio.irondome.caixapreta

import com.google.gson.JsonObject
import com.mio.irondome.interceptor.AgentEvents

/** O texto do usuário organizado em partes pela IA; qualquer uma pode ficar vazia. */
data class StructuredBug(val description: String, val steps: String, val expected: String)

/**
 * Pede à IA que só organize o texto que o usuário escreveu em o que aconteceu, passos e esperado. Ela não lê o código do
 * projeto aberto nem acrescenta nada: o bug é do plugin, e o projeto do usuário não faz parte do problema.
 */
object BugStructuring {

    const val INSTRUCTIONS_FILE = "reporte-bug.md"

    fun instructions(title: String, text: String): String = buildString {
        appendLine("# Organizar um relatório de bug")
        appendLine()
        appendLine("Uma pessoa escreveu o relato abaixo sobre um problema no plugin IronHawk IA. Organize **só o texto dela** em três")
        appendLine("partes, em português do Brasil. **Não leia nem altere nenhum arquivo do projeto aberto**, porque o bug é do plugin, e")
        appendLine("não do projeto dela. Não invente nada: se uma parte não estiver no texto, deixe-a vazia.")
        appendLine()
        appendLine("- `description`: o que aconteceu, o que a pessoa viu, com as mensagens de erro que ela citou.")
        appendLine("- `steps`: os passos para reproduzir, como lista numerada, se ela os contou.")
        appendLine("- `expected`: o que ela esperava que acontecesse, se ela disse.")
        appendLine()
        appendLine("Mantenha as palavras dela e o que ela citou entre crases. Responda **só** com um bloco ```json:")
        appendLine()
        appendLine("```json")
        appendLine("""{"description": "...", "steps": "1. ...\n2. ...", "expected": "..."}""")
        appendLine("```")
        appendLine()
        appendLine("## Título")
        appendLine()
        appendLine(title.trim())
        appendLine()
        appendLine("## Texto da pessoa")
        appendLine()
        appendLine(text.trim())
    }

    /** As partes da resposta da IA, ou nulo se ela não trouxe o JSON ou a descrição veio vazia. */
    fun parse(answer: String): StructuredBug? {
        val json = AgentEvents.extractJson(answer)?.takeIf { it.isJsonObject }?.asJsonObject ?: return null
        val description = json.text("description").orEmpty()
        if (description.isBlank()) return null
        return StructuredBug(description, json.text("steps").orEmpty(), json.text("expected").orEmpty())
    }

    private fun JsonObject.text(key: String): String? = get(key)?.takeIf { it.isJsonPrimitive }?.asString?.trim()
}
