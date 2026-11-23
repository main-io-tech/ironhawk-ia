package com.mio.irondome.radar

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.mio.irondome.comando.ReviewMarkdown
import com.mio.irondome.interceptor.AgentEvents

/** Uma opção de resposta: [label] curto, [effect] com o que acontece se ela for escolhida e [changes] se ela altera arquivos. */
data class DiscussOption(val label: String, val effect: String = "", val changes: Boolean = true)

/** A pergunta só é feita se a pergunta [question] foi respondida com uma das [options] (índices 0-based). */
data class DiscussCondition(val question: String, val options: Set<Int>)

/**
 * Uma decisão que a IA leva ao usuário sobre o comentário [commentId]: [recommended] é o índice (0-based) da opção que
 * ela escolheria, ou -1, [reason] o porquê, e [condition] diz de que resposta anterior a pergunta depende.
 */
data class DiscussQuestion(
    val id: String,
    val commentId: String,
    val text: String,
    val options: List<DiscussOption>,
    val recommended: Int,
    val reason: String = "",
    val condition: DiscussCondition? = null,
)

/** A resposta da IA a um comentário: [verdict] é a conclusão para ler de relance, [text] a resposta em uma ou duas frases. */
data class DiscussAnswer(val verdict: String, val text: String) {
    val isEmpty get() = verdict.isBlank() && text.isBlank()
}

/**
 * O que a IA devolveu numa rodada: a resposta a cada comentário, pelo id, as perguntas que faltam, na ordem, a réplica
 * ao que o usuário escreveu ([reply]) e os fatos que ela apurou, para ela mesma reaproveitar ([notes]).
 */
data class DiscussRound(
    val answers: Map<String, DiscussAnswer>,
    val questions: List<DiscussQuestion>,
    val reply: String = "",
    val notes: String = "",
)

/** Uma pergunta respondida: a opção que o usuário escolheu. */
class DiscussDecision(val question: DiscussQuestion, val option: Int) {
    val chosen: DiscussOption get() = question.options[option]
}

/**
 * O "Discutir antes de alterar" do Radar, conduzido como uma entrevista: a IA apura os fatos no código e leva ao usuário
 * só as decisões, uma de cada vez, cada uma com a opção que ela recomenda. O usuário escolhe uma opção ou responde com
 * um texto, que a IA responde antes de perguntar de novo. Nada é alterado até ele confirmar o que ficou decidido, e aí
 * cada comentário vira uma alteração com a decisão escrita. Aqui ficam os textos e a leitura do JSON; quem roda é o
 * [DiscussionService].
 */
object Discussion {

    const val QUESTIONS_FILE = "discussao-perguntas.md"
    const val REPLY_FILE = "discussao-replica.md"
    const val MAX_QUESTIONS = 12
    const val DECISION_TITLE = "**Decidido na discussão"
    private const val MIN_OPTIONS = 2
    private const val MAX_OPTIONS = 4
    private const val MAX_NOTES = 4000

    private val FORMAT = """
        {
          "answers": [
            {"comment": "C3", "verdict": "Pode remover", "answer": "calcularLegado faz a mesma conta de calcular, só que sem o desconto, e só o relatório mensal ainda o usa. Ficou porque ninguém migrou o relatório."}
          ],
          "questions": [
            {
              "id": "Q1",
              "comment": "C3",
              "question": "Removo calcularLegado e passo o relatório a usar calcular?",
              "options": [
                {"label": "Remover", "effect": "O relatório passa a mostrar o valor com desconto; mudam 2 arquivos.", "changes": true},
                {"label": "Manter como está", "effect": "Nada muda; o relatório continua sem o desconto.", "changes": false}
              ],
              "recommended": 1,
              "reason": "O valor do relatório hoje está errado."
            },
            {
              "id": "Q2",
              "comment": "C3",
              "onlyIf": "Q1=1",
              "question": "O CSV exportado também passa a sair com o desconto?",
              "options": [
                {"label": "Sim, igual ao relatório", "effect": "Quem lê o CSV recebe o valor novo; muda 1 arquivo.", "changes": true},
                {"label": "Não, o CSV fica como está", "effect": "O CSV continua sem o desconto, num método só dele.", "changes": true}
              ],
              "recommended": 1,
              "reason": "Dois valores diferentes para o mesmo pedido confundem."
            }
          ],
          "notes": "calcularLegado: PedidoService.kt:88. Chamadores: RelatorioMensal.gerar, ExportacaoCsv.linha. Testes: PedidoServiceTest (2)."
        }
    """.trimIndent()

    private val REPLY_FORMAT = """
        {
          "reply": "Não é migração: ela roda toda vez que a aplicação sobe e só confere o formato dos dados.",
          "questions": [
            {"id": "Q2", "comment": "C2", "question": "…", "options": [{"label": "…", "effect": "…", "changes": true}, {"label": "Manter como está", "effect": "…", "changes": false}], "recommended": 1, "reason": "…"}
          ],
          "notes": "…"
        }
    """.trimIndent()

    /** O tamanho de cada texto: o usuário disse que respostas longas atrapalham a decisão. */
    private val BREVITY = """
        **Seja breve.** O usuário decide pelo que você escrever, sem abrir o código, e texto longo atrapalha a decisão. Frases
        curtas, em português simples do Brasil. Na primeira vez que usar o nome de uma classe ou de um termo do projeto, diga
        em poucas palavras o que é. Nada de histórico, lista de classes, passo a passo ou prévia de código: se ele quiser
        mais detalhe, ele pergunta, e você responde na rodada seguinte.
    """.trimIndent()

    private val QUESTION_RULES = """
        - `question`: uma frase curta, que se responde escolhendo uma opção.
        - `options`: de $MIN_OPTIONS a $MAX_OPTIONS, sempre com a de manter como está. `label` com até 5 palavras. `effect`: o que acontece
          se ela for escolhida, em uma frase de até 140 caracteres, com o tamanho da mudança ("mudam 2 arquivos"). `changes`:
          `true` se a opção altera arquivos, `false` se deixa tudo como está.
        - `recommended`: o número (1-based) da opção que você escolheria, e `reason`: por quê, em uma frase.
        - `onlyIf`: só na pergunta que depende de outra. `"Q1=2"` faz a pergunta aparecer só se a opção 2 da Q1 for a
          escolhida; `"Q1=2,3"` vale para mais de uma.
    """.trimIndent()

    /** O pedido da primeira rodada: só leitura, a resposta a cada comentário e as decisões que cabem ao usuário. */
    fun questionInstructions(comments: List<ReviewComment>): String = buildString {
        appendLine("# Discussão antes de alterar")
        appendLine()
        appendLine("O usuário marcou os comentários abaixo para **discutir antes de alterar**. Nesta execução você só lê: **não altere nem")
        appendLine("crie arquivo nenhum**.")
        appendLine()
        appendLine("Conduza como uma entrevista: você apura os fatos e leva ao usuário só as decisões. A IDE mostra **uma pergunta por")
        appendLine("vez** e espera a resposta antes da próxima. Nada é alterado até ele confirmar o que ficou decidido.")
        appendLine()
        appendLine("1. **Apure antes de perguntar.** Leia o código citado, quem o usa, os testes e as regras do projeto. O que dá para")
        appendLine("   descobrir no código você descobre, e não pergunta.")
        appendLine("2. **Responda ao comentário primeiro**, em `answers`, um item para cada comentário, sem exceção:")
        appendLine("   - `verdict`: a conclusão em até 6 palavras, para ser lida de relance, como \"Pode remover\", \"Precisa ficar\",")
        appendLine("     \"Está no lugar errado\" ou \"Depende de uma decisão sua\".")
        appendLine("   - `answer`: a resposta direta ao que o comentário pergunta ou aponta, em 1 ou 2 frases curtas (até 240 caracteres).")
        appendLine("3. **Pergunte só as decisões**, em `questions`, na ordem em que precisam ser tomadas: primeiro a de que as outras")
        appendLine("   dependem. Uma decisão por pergunta, no máximo $MAX_QUESTIONS no total. Um comentário que só queria uma explicação, sem nada")
        appendLine("   a decidir, fica sem pergunta.")
        QUESTION_RULES.lines().forEach { appendLine("   $it") }
        appendLine("4. Em `notes`, guarde para você mesmo os fatos que apurou (arquivos, quem chama quem, regras do projeto), em até 1500")
        appendLine("   caracteres. O usuário não vê; você recebe de volta nas próximas rodadas e não precisa apurar de novo.")
        appendLine()
        appendLine(BREVITY)
        appendLine()
        appendLine("Responda **só** com um bloco ```json, neste formato (`comment` é o id do comentário, como `C3`):")
        appendLine()
        appendLine("```json")
        appendLine(FORMAT)
        appendLine("```")
        appendLine()
        appendLine(commentsSection(comments))
    }

    /**
     * O pedido quando o usuário respondeu a [open] com um texto em vez de escolher uma opção: a IA responde ao texto e
     * devolve as perguntas que faltam, começando pela que estava em aberto. Ela não decide por ele.
     */
    fun replyInstructions(
        comments: List<ReviewComment>,
        answers: Map<String, DiscussAnswer>,
        decisions: List<DiscussDecision>,
        open: DiscussQuestion,
        text: String,
        remaining: List<DiscussQuestion>,
        notes: String,
    ): String = buildString {
        appendLine("# Discussão antes de alterar: o usuário respondeu com um texto")
        appendLine()
        appendLine("Você está discutindo com o usuário os comentários abaixo, uma decisão de cada vez. Nesta execução você só lê: **não")
        appendLine("altere nem crie arquivo nenhum**.")
        appendLine()
        appendLine("A pergunta em aberto é a `${open.id}`. Em vez de escolher uma opção, o usuário escreveu o texto que está no fim. Ele")
        appendLine("pode estar perguntando de volta, discordando ou propondo outra saída.")
        appendLine()
        appendLine("1. Em `reply`, responda ao que ele escreveu, em até 3 frases curtas. Se for uma pergunta, responda com o fato, apurando")
        appendLine("   no código o que faltar. Se ele discordou, diga se ele tem razão. Não repita o que já foi dito.")
        appendLine("2. Em `questions`, devolva **todas** as perguntas que ainda faltam, na ordem, começando pela que está em aberto, com o")
        appendLine("   mesmo `id` e ajustada ao que ele disse. Se ele propôs uma saída que não estava nas opções, inclua-a como opção.")
        appendLine("   **Você não decide por ele**: a decisão só vale quando ele escolhe uma opção. Se a resposta dele tornou uma pergunta")
        appendLine("   desnecessária, tire-a; se abriu uma decisão nova, acrescente-a. Sem mais nada a decidir, `questions` fica vazio.")
        QUESTION_RULES.lines().forEach { appendLine("   $it") }
        appendLine("3. Em `notes`, devolva as suas anotações atualizadas, em até 1500 caracteres.")
        appendLine()
        appendLine(BREVITY)
        appendLine()
        appendLine("Responda **só** com um bloco ```json, neste formato:")
        appendLine()
        appendLine("```json")
        appendLine(REPLY_FORMAT)
        appendLine("```")
        appendLine()
        appendLine(commentsSection(comments))
        if (answers.isNotEmpty()) {
            appendLine("## O que você já respondeu")
            appendLine()
            comments.forEach { comment ->
                answers[comment.id]?.let { appendLine("- **${comment.id}** · ${listOf(it.verdict, it.text).filter(String::isNotBlank).joinToString(": ")}") }
            }
            appendLine()
        }
        if (decisions.isNotEmpty()) {
            appendLine("## Decisões que o usuário já tomou")
            appendLine()
            decisions.forEach { appendLine("- `${it.question.id}` (${it.question.commentId}) ${it.question.text} → **${it.chosen.label}**") }
            appendLine()
        }
        appendLine("## Perguntas que faltam")
        appendLine()
        remaining.forEach { question ->
            val mark = if (question.id == open.id) " **(em aberto)**" else ""
            val condition = question.condition?.let { " · só se ${it.question}=${it.options.map { option -> option + 1 }.sorted().joinToString(",")}" }.orEmpty()
            appendLine("- `${question.id}` (${question.commentId})$mark$condition ${question.text}")
            question.options.forEachIndexed { index, option -> appendLine("  ${index + 1}. ${option.label}: ${option.effect}") }
        }
        appendLine()
        if (notes.isNotBlank()) {
            appendLine("## Suas anotações")
            appendLine()
            appendLine(notes.trim())
            appendLine()
        }
        appendLine("## O que o usuário escreveu sobre a `${open.id}`")
        appendLine()
        appendLine(text.trim())
    }

    private fun commentsSection(comments: List<ReviewComment>) = buildString {
        appendLine("## Comentários")
        comments.forEach { comment ->
            appendLine()
            appendLine("### ${comment.id} · ${where(comment)}")
            appendLine()
            if (comment.snippet.isNotBlank()) {
                appendLine("```")
                appendLine(comment.snippet.trimEnd('\n'))
                appendLine("```")
                appendLine()
            }
            appendLine(comment.text.trim())
        }
    }

    private fun where(comment: ReviewComment): String =
        if (comment.target == CommentTarget.PROJECT) "projeto inteiro"
        else "`${comment.filePath}`, ${ReviewMarkdown.describe(comment.target, comment.startLine, comment.endLine)}"

    /** A pergunta vale agora: não depende de outra, ou a outra foi respondida com uma das opções que ela pede. */
    fun applies(question: DiscussQuestion, decisions: List<DiscussDecision>): Boolean {
        val condition = question.condition ?: return true
        return decisions.any { it.question.id.equals(condition.question, ignoreCase = true) && it.option in condition.options }
    }

    /**
     * O texto do comentário depois da discussão: o que o usuário escreveu e, embaixo, o que ficou decidido, para a IA
     * aplicar sem discutir de novo. Um bloco de decisões de uma discussão anterior é trocado pelo novo.
     */
    fun decidedText(original: String, decisions: List<DiscussDecision>, date: String): String {
        val base = original.substringBefore(DECISION_TITLE).trimEnd()
        if (decisions.isEmpty()) return base
        return buildString {
            append(base)
            append("\n\n").append(DECISION_TITLE).append(" ($date):** aplique exatamente isto, sem discutir de novo.\n")
            decisions.forEach { decision ->
                append("\n- ${decision.question.text} **${decision.chosen.label}.**")
                decision.chosen.effect.takeIf { it.isNotBlank() }?.let { append(" $it") }
            }
        }
    }

    /**
     * A rodada lida da resposta da IA, só com o que é dos comentários de [commentIds]. Nulo se ela não trouxe o JSON
     * esperado; perguntas vazias quando não sobra nada para decidir.
     */
    fun parse(answer: String, commentIds: Set<String>): DiscussRound? {
        val json: JsonElement = AgentEvents.extractJson(answer) ?: return null
        val root = json as? JsonObject
        // Um `[x]` solto no meio de uma resposta em prosa vira um array de texto: isso não é a resposta pedida. E um
        // campo que não é lista (`"questions": null`) é formato errado, não motivo para estourar.
        val items = when {
            json is JsonArray -> json.takeIf { array -> array.any { it is JsonObject } } ?: return null
            root != null -> root.get("questions") as? JsonArray ?: return null
            else -> return null
        }
        // `analyses` era o nome antes de `answers`: uma resposta nesse formato ainda é lida.
        val answers = ((root?.get("answers") ?: root?.get("analyses")) as? JsonArray)?.mapNotNull { element ->
            val item = element as? JsonObject ?: return@mapNotNull null
            val comment = commentId(item.text("comment"), commentIds) ?: return@mapNotNull null
            val read = DiscussAnswer(item.clean("verdict"), item.clean("answer").ifEmpty { item.clean("understanding") })
            if (read.isEmpty) null else comment to read
        }?.toMap().orEmpty()
        return DiscussRound(
            answers,
            questions(items, commentIds),
            root?.clean("reply").orEmpty(),
            root?.clean("notes").orEmpty().take(MAX_NOTES),
        )
    }

    private fun questions(items: JsonArray, commentIds: Set<String>): List<DiscussQuestion> {
        val questions = mutableListOf<DiscussQuestion>()
        val used = mutableSetOf<String>()
        for (element in items) {
            val item = element as? JsonObject ?: continue
            val comment = commentId(item.text("comment"), commentIds) ?: continue
            val text = item.text("question")?.trim()?.takeIf { it.isNotEmpty() } ?: continue
            val options = (item.get("options") as? JsonArray)?.mapNotNull(::option)?.take(MAX_OPTIONS).orEmpty()
            if (options.size < MIN_OPTIONS) continue
            // Sem id, ou com um repetido, a pergunta ganha um: é por ele que outra pergunta depende dela.
            val id = item.clean("id").takeIf { it.isNotEmpty() && used.none { other -> other.equals(it, ignoreCase = true) } }
                ?: generateSequence(questions.size + 1) { it + 1 }.map { "Q$it" }.first { candidate -> used.none { it.equals(candidate, ignoreCase = true) } }
            used += id
            questions += DiscussQuestion(id, comment, text, options, recommended(item, options), item.clean("reason"), condition(item.text("onlyIf")))
            if (questions.size >= MAX_QUESTIONS) break
        }
        // Uma condição que aponta para uma pergunta que não veio nunca seria atendida, e a pergunta sumiria sem aviso.
        return questions.map { question ->
            val condition = question.condition ?: return@map question
            val known = questions.any { it.id.equals(condition.question, ignoreCase = true) && it.id != question.id }
            if (known) question else question.copy(condition = null)
        }
    }

    /** `Q1=2` ou `Q1=2,3`, com os números 1-based como a IA escreve. */
    private fun condition(raw: String?): DiscussCondition? {
        val match = CONDITION.matchEntire(raw?.trim().orEmpty()) ?: return null
        val options = match.groupValues[2].split(',', '|', ' ').mapNotNull { it.trim().toIntOrNull() }.filter { it >= 1 }.map { it - 1 }.toSet()
        return if (options.isEmpty()) null else DiscussCondition(match.groupValues[1], options)
    }

    private val CONDITION = Regex("""([A-Za-z]\w*)\s*=\s*([\d,| ]+)""")

    /** O id do comentário como a IA o escreveu: `c3` e `C3 (Frete.kt)` valem `C3`. Nulo se não for de [ids]. */
    private fun commentId(raw: String?, ids: Set<String>): String? {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return null
        ids.firstOrNull { it.equals(text, ignoreCase = true) }?.let { return it }
        val token = LEADING_ID.find(text)?.value ?: return null
        return ids.firstOrNull { it.equals(token, ignoreCase = true) }
    }

    private val LEADING_ID = Regex("""^[A-Za-z]+\d+""")

    private fun option(element: JsonElement): DiscussOption? {
        if (element.isJsonPrimitive) return element.asString.trim().takeIf { it.isNotEmpty() }?.let { DiscussOption(it, "", changes = !keeps(it)) }
        val item = element as? JsonObject ?: return null
        val label = item.text("label")?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        // Sem `changes`, vale o que o rótulo diz: na dúvida, a opção altera, para a IA não deixar de aplicar uma decisão.
        val changes = item.get("changes")?.takeIf { it.isJsonPrimitive }?.asString?.trim()?.lowercase()?.let { it != "false" && it != "0" && it != "não" && it != "nao" }
            ?: !keeps(label)
        return DiscussOption(label, item.clean("effect").ifEmpty { item.clean("detail") }, changes)
    }

    private fun keeps(label: String) = label.trim().lowercase().let { it.startsWith("manter") || it.startsWith("não alterar") || it.startsWith("nao alterar") || it.startsWith("deixar como") }

    /** `recommended` como número 1-based ou como o rótulo da opção; -1 se não bater com nenhuma. */
    private fun recommended(item: JsonObject, options: List<DiscussOption>): Int {
        val value = item.get("recommended")?.takeIf { it.isJsonPrimitive }?.asString?.trim() ?: return -1
        value.toIntOrNull()?.let { return (it - 1).takeIf { index -> index in options.indices } ?: -1 }
        return options.indexOfFirst { it.label.equals(value, ignoreCase = true) }
    }

    private fun JsonObject.text(key: String): String? = get(key)?.takeIf { it.isJsonPrimitive }?.asString

    private fun JsonObject.clean(key: String): String = text(key)?.trim().orEmpty()
}
