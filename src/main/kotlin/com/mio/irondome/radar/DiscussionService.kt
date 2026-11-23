package com.mio.irondome.radar

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.util.messages.Topic
import com.mio.irondome.base.DefenseTabs
import com.mio.irondome.interceptor.AgentResult
import com.mio.irondome.interceptor.AgentRunner
import com.mio.irondome.interceptor.AgentTask
import com.mio.irondome.interceptor.FireWithAiAction
import org.jetbrains.annotations.TestOnly
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * Conduz o "Discutir antes de alterar" como uma entrevista: uma execução só de leitura devolve a resposta a cada
 * comentário e as decisões que cabem ao usuário, e a aba Discussão do Radar mostra uma pergunta por vez. Escolher uma
 * opção passa para a próxima, sem rodar a IA; responder com um texto roda a IA, que responde e pergunta de novo. Nada é
 * alterado até o usuário confirmar o que ficou decidido: aí cada comentário vira uma alteração com a decisão escrita, e
 * é o disparo normal do Radar que a aplica. Na EDT.
 */
@Service(Service.Level.PROJECT)
class DiscussionService(private val project: Project) {

    /** A discussão em andamento: os comentários, o que a IA respondeu, as perguntas que faltam e o que já foi decidido. */
    class Session(val comments: List<ReviewComment>) {
        /** A resposta mais recente da IA a cada comentário, pelo id. */
        val answers = mutableMapOf<String, DiscussAnswer>()
        /** As perguntas que faltam, na ordem; as que dependem de uma resposta que não veio são puladas. */
        var queue: List<DiscussQuestion> = emptyList()
        val decisions = mutableListOf<DiscussDecision>()
        /** A réplica da IA ao último texto do usuário, mostrada acima da pergunta. */
        var reply = ""
        /** O texto que o usuário escreveu e a IA está respondendo, ou não conseguiu responder: volta para o campo. */
        var draft = ""
        var notes = ""
        /** Quantas perguntas a IA trouxe na primeira rodada, para o "2 de 5". */
        var planned = 0

        /** A IA está trabalhando: não há o que responder agora. */
        var busy = true
        var status = ""

        /** A pergunta da vez; nula quando não falta nenhuma. */
        val current: DiscussQuestion? get() = queue.firstOrNull { Discussion.applies(it, decisions) }

        /** Nada mais a perguntar: falta o usuário confirmar o que ficou decidido. */
        val confirming: Boolean get() = !busy && current == null

        /** As decisões que alteram arquivos. */
        val changes: List<DiscussDecision> get() = decisions.filter { it.chosen.changes }

        fun decisionsOf(comment: ReviewComment): List<DiscussDecision> = decisions.filter { it.question.commentId == comment.id }
    }

    fun interface Listener {
        fun changed()

        companion object {
            @Topic.ProjectLevel
            val TOPIC = Topic(Listener::class.java, Topic.BroadcastDirection.NONE)
        }
    }

    @Volatile
    var session: Session? = null
        private set

    private val runner get() = AgentRunner.getInstance(project)
    private val comments get() = project.service<ReviewCommentService>()

    /** Só para os testes: põe a discussão no ponto pedido, sem rodar a IA. */
    @TestOnly
    fun show(session: Session?) {
        this.session = session
        publish()
    }

    /** Começa a discussão dos [comments] marcados para discutir. Sem efeito se já há uma em andamento. */
    fun start(comments: List<ReviewComment>) {
        if (comments.isEmpty()) return
        if (session != null) {
            notify("Já há uma discussão em andamento", "Termine ou cancele a discussão na aba Radar antes de começar outra.", NotificationType.WARNING)
            return
        }
        val current = Session(comments.sortedWith(ReviewComment.ORDER))
        session = current
        busy(current, "A IA está lendo o código para responder…")
        val file = runner.writeInstructions(Discussion.QUESTIONS_FILE, Discussion.questionInstructions(current.comments))
            ?: return fail(current, "Não consegui gravar as instruções para a IA.")
        val started = runner.run(
            AgentTask(
                "Discussão: perguntas",
                "Leia as instruções em $file, sem alterar nenhum arquivo, e responda só com o JSON pedido.",
                write = false,
                origin = DefenseTabs.RADAR,
            ),
        ) { result -> onFirstRound(current, result) }
        if (!started) fail(current, "A IA não pôde começar agora. Os comentários continuam no Radar.")
    }

    /** O usuário escolheu a opção [option] da pergunta da vez: vale como decisão e a próxima pergunta aparece. */
    fun choose(option: Int) {
        val current = session ?: return
        val question = current.current ?: return
        if (current.busy || option !in question.options.indices) return
        current.decisions += DiscussDecision(question, option)
        current.queue = current.queue.filter { it !== question }
        current.reply = ""
        current.draft = ""
        current.status = ""
        publish()
    }

    /** Volta à pergunta anterior, desfazendo a última decisão. */
    fun back() {
        val current = session ?: return
        if (current.busy || current.decisions.isEmpty()) return
        val last = current.decisions.removeAt(current.decisions.lastIndex)
        current.queue = listOf(last.question) + current.queue
        current.reply = ""
        current.status = ""
        publish()
    }

    /**
     * O usuário respondeu à pergunta da vez com um texto: uma dúvida, uma discordância ou outra saída. A IA responde e
     * devolve as perguntas que faltam; ela não decide por ele.
     */
    fun reply(text: String) {
        val current = session ?: return
        val question = current.current ?: return
        if (current.busy || text.isBlank()) return
        current.draft = text.trim()
        busy(current, "A IA está respondendo…")
        val file = runner.writeInstructions(
            Discussion.REPLY_FILE,
            Discussion.replyInstructions(current.comments, current.answers, current.decisions, question, current.draft, current.queue, current.notes),
        ) ?: return resume(current, "Não consegui gravar as instruções para a IA.")
        val started = runner.run(
            AgentTask(
                "Discussão: resposta sobre ${question.commentId}",
                "Leia as instruções em $file, sem alterar nenhum arquivo, e responda só com o JSON pedido.",
                write = false,
                origin = DefenseTabs.RADAR,
            ),
        ) { result -> onReply(current, result) }
        if (!started) resume(current, "A IA não pôde começar agora.")
    }

    /**
     * O usuário confirmou o que ficou decidido. Cada comentário com uma decisão que altera arquivos vira uma alteração:
     * deixa de ser "discutir antes" e passa a trazer a decisão no texto. Os outros, que só queriam uma resposta ou
     * ficaram como estão, saem como tratados. Com [fire], a IA já aplica as alterações; sem ele, elas ficam no Radar
     * para disparar depois.
     */
    fun confirm(fire: Boolean) {
        val current = session ?: return
        if (current.busy || current.current != null) return
        session = null
        val date = LocalDate.now().format(DATE)
        val (changing, kept) = current.comments.partition { comment -> current.decisionsOf(comment).any { it.chosen.changes } }
        changing.forEach { comment -> comments.decide(comment, Discussion.decidedText(comment.text, current.decisionsOf(comment), date)) }
        kept.forEach { comment -> comments.decide(comment, Discussion.decidedText(comment.text, current.decisionsOf(comment), date), keepDiscussing = true) }
        comments.resolve(kept)
        publish()
        if (changing.isEmpty()) return
        if (fire) {
            FireWithAiAction.fireTargets(project, changing)
        } else {
            notify(
                if (changing.size == 1) "A decisão virou uma alteração" else "As decisões viraram ${changing.size} alterações",
                "${changing.joinToString(", ") { it.id }} ${if (changing.size == 1) "está" else "estão"} no Radar, com a decisão escrita. Dispare com IA quando quiser aplicar.",
                NotificationType.INFORMATION,
            )
        }
    }

    /** A resposta bastou e nada precisa mudar: encerra a discussão e dá os comentários por tratados, sem alterar código. */
    fun conclude() {
        val current = session ?: return
        if (current.busy) return
        session = null
        comments.resolve(current.comments)
        publish()
    }

    /** Desiste: os comentários continuam no Radar, ainda marcados para discutir. */
    fun cancel() {
        val current = session ?: return
        session = null
        if (current.busy) runner.progress?.cancel()
        publish()
    }

    private fun onFirstRound(current: Session, result: AgentResult) {
        if (session !== current) return
        if (!result.ok) return fail(current, result.error ?: "A IA não terminou. Os comentários continuam no Radar.")
        val round = parse(current, result)
            ?: return fail(current, "A IA não devolveu a resposta no formato esperado. Os comentários continuam no Radar; veja a resposta no Arsenal.")
        // Nada reconhecido não é "sem dúvida": é uma resposta fora do formato, e não há o que mostrar ao usuário.
        if (round.questions.isEmpty() && round.answers.isEmpty()) {
            return fail(current, "A IA não devolveu a resposta nem as perguntas. Os comentários continuam no Radar; veja a resposta no Arsenal.")
        }
        current.answers.putAll(round.answers)
        current.queue = round.questions
        current.planned = round.questions.size
        current.notes = round.notes
        current.busy = false
        current.status = ""
        publish()
        notify(
            when (round.questions.size) {
                0 -> "A IA respondeu aos seus comentários"
                1 -> "A IA respondeu e tem 1 decisão para você"
                else -> "A IA respondeu e tem ${round.questions.size} decisões para você"
            },
            "Veja na aba Radar, em Discussão. Nada é alterado antes de você confirmar.",
            NotificationType.INFORMATION,
        )
    }

    private fun onReply(current: Session, result: AgentResult) {
        if (session !== current) return
        if (!result.ok) return resume(current, result.error ?: "A IA não terminou.")
        val round = parse(current, result) ?: return resume(current, "A IA não devolveu a resposta no formato esperado; veja no Arsenal.")
        if (round.reply.isBlank() && round.questions.isEmpty()) return resume(current, "A IA não respondeu ao que você escreveu; veja no Arsenal.")
        current.answers.putAll(round.answers)
        current.queue = round.questions
        current.reply = round.reply
        current.draft = ""
        if (round.notes.isNotBlank()) current.notes = round.notes
        current.busy = false
        current.status = ""
        publish()
    }

    private fun parse(current: Session, result: AgentResult): DiscussRound? =
        Discussion.parse(result.answer, current.comments.mapTo(mutableSetOf()) { it.id })

    /** A réplica não veio: a pergunta e o texto do usuário continuam na tela para ele tentar de novo ou escolher uma opção. */
    private fun resume(current: Session, why: String) {
        if (session !== current) return
        current.busy = false
        current.status = "$why A pergunta continua aqui."
        publish()
    }

    private fun busy(current: Session, status: String) {
        current.busy = true
        current.status = status
        publish()
    }

    private fun fail(current: Session, message: String) {
        if (session === current) session = null
        publish()
        notify("Discussão interrompida", message, NotificationType.WARNING)
    }

    private fun publish() = project.messageBus.syncPublisher(Listener.TOPIC).changed()

    private fun notify(title: String, content: String, type: NotificationType) {
        NotificationGroupManager.getInstance().getNotificationGroup("Iron Dome IA").createNotification(title, content, type)
            .notify(project)
    }

    private companion object {
        val DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM/yyyy")
    }
}
