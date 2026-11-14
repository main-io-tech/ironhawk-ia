package com.mio.irondome.radar

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.mio.irondome.comando.ReviewMarkdown
import com.mio.irondome.interceptor.AgentEvents

/** Uma opção de resposta: [label] curto e [detail] com o que muda, a vantagem e o risco. */
data class DiscussOption(val label: String, val detail: String)

/** Uma pergunta da IA sobre o comentário [commentId]; [recommended] é o índice (0-based) da opção que ela recomenda, ou -1. */
data class DiscussQuestion(val id: String, val commentId: String, val text: String, val options: List<DiscussOption>, val recommended: Int)

/** A resposta do usuário: uma opção ([option], -1 sem escolha) e/ou um texto livre. */
class DiscussAnswer(var option: Int = -1, var text: String = "")

/** Uma rodada de perguntas já respondida: cada pergunta com a decisão em palavras, para a IA ler. */
class AnsweredRound(val questions: List<DiscussQuestion>, val decisions: List<String>)

/**
 * O "Discutir antes de alterar" do Radar sem conversa ao vivo: a IA devolve as perguntas em JSON, o plugin as mostra, e as
 * respostas voltam para ela em outra execução. Aqui ficam só os textos e a leitura do JSON; quem roda é o [DiscussionService].
 */
object Discussion {

    /** Até duas rodadas de perguntas; depois disso a IA é obrigada a aplicar. */
    const val MAX_ROUNDS = 2
    const val QUESTIONS_FILE = "discussao-perguntas.md"
    const val DECISIONS_FILE = "discussao-decisoes.md"
    const val MAX_QUESTIONS_PER_COMMENT = 3
    const val MAX_QUESTIONS = 12
    private const val MIN_OPTIONS = 2
    private const val MAX_OPTIONS = 5

    private const val FORMAT = """{"questions": [{"comment": "C3", "question": "O que fazer com o método legado?", "options": [""" +
        """{"label": "Manter como está", "detail": "Nada muda. Risco: a duplicação continua."}, """ +
        """{"label": "Remover", "detail": "Apaga o método e os 2 chamadores. Risco: quebra o módulo X."}], "recommended": 1}]}"""

    /** O pedido da rodada de perguntas: só leitura, sem arquivo nenhum para o usuário ler. */
    fun questionInstructions(comments: List<ReviewComment>, history: List<AnsweredRound>): String = buildString {
        appendLine("# Discussão antes de alterar: perguntas")
        appendLine()
        appendLine("O usuário marcou os comentários abaixo para **discutir antes de alterar**. Nesta execução você só lê: **não altere nenhum")
        appendLine("arquivo e não escreva arquivo nenhum**. Leia o código citado, quem o usa e os testes dele, e faça as perguntas de que")
        appendLine("depende a decisão. O usuário as responde na IDE, e só depois você altera o código.")
        appendLine()
        appendLine("- Para cada comentário em que a decisão é do usuário, faça de 1 a $MAX_QUESTIONS_PER_COMMENT perguntas.")
        appendLine("- Cada pergunta tem de $MIN_OPTIONS a $MAX_OPTIONS opções, sempre incluindo manter como está quando fizer sentido. Em `detail`, diga o")
        appendLine("  que muda, a vantagem, a desvantagem e o risco. Marque a que você recomenda em `recommended` (1-based).")
        appendLine("- Não pergunte o que o código já responde. Se não há dúvida real num comentário, não faça pergunta para ele.")
        appendLine("- No máximo $MAX_QUESTIONS perguntas no total.")
        appendLine()
        appendLine("Responda **só** com um bloco ```json, neste formato (`comment` é o id do comentário, como `C3`):")
        appendLine()
        appendLine("```json")
        appendLine(FORMAT)
        appendLine("```")
        appendLine()
        appendLine("Se não houver pergunta nenhuma, responda `{\"questions\": []}`.")
        appendLine()
        appendLine(commentsSection(comments))
        appendLine(historySection(history))
    }

    /**
     * O pedido para aplicar o que foi decidido. Com [mayAskAgain], a IA pode devolver mais perguntas, uma vez, em vez de
     * alterar; sem ele, é a última rodada: ela aplica, assume a melhor opção onde ainda houver dúvida e registra a suposição.
     */
    fun applyInstructions(comments: List<ReviewComment>, history: List<AnsweredRound>, reviewFile: String, mayAskAgain: Boolean): String = buildString {
        appendLine("# Discussão antes de alterar: aplicar as decisões")
        appendLine()
        appendLine("O usuário discutiu os comentários abaixo e respondeu às suas perguntas. As decisões estão no fim. Elas **substituem o modo de")
        appendLine("discussão** das instruções de `$reviewFile`: não discuta de novo.")
        appendLine()
        appendLine("1. Altere o código de cada comentário conforme as decisões, e só o que elas pedem.")
        appendLine("2. Depois de tratar um comentário, troque `[ ]` por `[x]` no título dele em `$reviewFile`, inclusive quando a decisão for manter")
        appendLine("   o código como está. Não altere o resto desse arquivo.")
        appendLine("3. Termine com um resumo curto do que mudou, em português do Brasil.")
        appendLine()
        if (mayAskAgain) {
            appendLine("**Se, mesmo com as respostas, ainda houver uma dúvida que muda o resultado**, não altere nenhum arquivo e responda **só** com um")
            appendLine("bloco ```json de novas perguntas, no formato abaixo. Isso vale uma única vez; se não houver dúvida real, aplique.")
            appendLine()
            appendLine("```json")
            appendLine(FORMAT)
            appendLine("```")
        } else {
            appendLine("**Esta é a última rodada: não faça mais perguntas.** Onde ainda houver dúvida, assuma a melhor opção (a recomendada ou a mais")
            appendLine("segura), aplique e registre cada suposição no resumo final, numa seção **Suposições**.")
        }
        appendLine()
        appendLine(commentsSection(comments))
        appendLine(historySection(history))
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

    private fun historySection(history: List<AnsweredRound>) = buildString {
        if (history.isEmpty()) return@buildString
        appendLine("## Perguntas e decisões")
        history.forEachIndexed { index, round ->
            appendLine()
            appendLine("### Rodada ${index + 1}")
            round.questions.forEachIndexed { number, question ->
                appendLine()
                appendLine("- **${question.commentId}** · ${question.text}")
                appendLine("  - Decisão: ${round.decisions[number]}")
            }
        }
    }

    private fun where(comment: ReviewComment): String =
        if (comment.target == CommentTarget.PROJECT) "projeto inteiro"
        else "`${comment.filePath}`, ${ReviewMarkdown.describe(comment.target, comment.startLine, comment.endLine)}"

    /** A decisão em palavras: o texto livre, a opção escolhida ou, sem resposta, a recomendação da IA. */
    fun decision(question: DiscussQuestion, answer: DiscussAnswer?): String {
        val chosen = answer?.option?.let { question.options.getOrNull(it) }
        val text = answer?.text?.trim().orEmpty()
        return when {
            text.isNotEmpty() && chosen != null -> "${chosen.label}. O usuário acrescentou: $text"
            text.isNotEmpty() -> "Resposta do usuário: $text"
            chosen != null -> chosen.label
            question.recommended in question.options.indices -> "${question.options[question.recommended].label} (sem resposta do usuário: vale a recomendação da IA)"
            else -> "Sem resposta do usuário e sem recomendação: decida pela opção mais segura e registre a suposição"
        }
    }

    /**
     * As perguntas da resposta da IA, só as dos comentários de [commentIds]. Nulo se ela não trouxe o JSON esperado;
     * lista vazia quando ela não tem pergunta nenhuma.
     */
    fun parse(answer: String, commentIds: Set<String>): List<DiscussQuestion>? {
        val json: JsonElement = AgentEvents.extractJson(answer) ?: return null
        val items = when {
            json.isJsonArray -> json.asJsonArray
            json.isJsonObject -> json.asJsonObject.getAsJsonArray("questions") ?: return null
            else -> return null
        }
        val perComment = mutableMapOf<String, Int>()
        val questions = mutableListOf<DiscussQuestion>()
        for (element in items) {
            val item = element as? JsonObject ?: continue
            val comment = item.text("comment")?.trim()?.takeIf { it in commentIds } ?: continue
            val text = item.text("question")?.trim()?.takeIf { it.isNotEmpty() } ?: continue
            val options = item.getAsJsonArray("options")?.mapNotNull(::option)?.take(MAX_OPTIONS).orEmpty()
            if (options.size < MIN_OPTIONS) continue
            val number = (perComment[comment] ?: 0) + 1
            if (number > MAX_QUESTIONS_PER_COMMENT) continue
            perComment[comment] = number
            questions += DiscussQuestion("$comment-$number", comment, text, options, recommended(item, options))
            if (questions.size >= MAX_QUESTIONS) break
        }
        return questions
    }

    private fun option(element: JsonElement): DiscussOption? {
        if (element.isJsonPrimitive) return element.asString.trim().takeIf { it.isNotEmpty() }?.let { DiscussOption(it, "") }
        val item = element as? JsonObject ?: return null
        val label = item.text("label")?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return DiscussOption(label, item.text("detail")?.trim().orEmpty())
    }

    /** `recommended` como número 1-based ou como o rótulo da opção; -1 se não bater com nenhuma. */
    private fun recommended(item: JsonObject, options: List<DiscussOption>): Int {
        val value = item.get("recommended")?.takeIf { it.isJsonPrimitive }?.asString?.trim() ?: return -1
        value.toIntOrNull()?.let { return (it - 1).takeIf { index -> index in options.indices } ?: -1 }
        return options.indexOfFirst { it.label.equals(value, ignoreCase = true) }
    }

    private fun JsonObject.text(key: String): String? = get(key)?.takeIf { it.isJsonPrimitive }?.asString
}
