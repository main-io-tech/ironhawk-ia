package com.mio.irondome.radar

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.util.messages.Topic
import com.mio.irondome.base.DefenseTabs
import com.mio.irondome.base.rootPath
import com.mio.irondome.interceptor.AgentResult
import com.mio.irondome.interceptor.AgentRunner
import com.mio.irondome.interceptor.AgentTask
import java.nio.file.Path

/**
 * Conduz o "Discutir antes de alterar" em segundo plano, sem conversa ao vivo: uma execução só de leitura devolve as
 * perguntas, o usuário responde no painel do Radar, e outra execução aplica as decisões, podendo perguntar mais uma
 * vez. Depois de [Discussion.MAX_ROUNDS] rodadas de perguntas, a IA é obrigada a aplicar. Na EDT.
 */
@Service(Service.Level.PROJECT)
class DiscussionService(private val project: Project) {

    /** A discussão em andamento: os comentários, as perguntas esperando resposta e o que já foi decidido. */
    class Session(val comments: List<ReviewComment>) {
        var questions: List<DiscussQuestion> = emptyList()
        var rounds = 0
        val answers = mutableMapOf<String, DiscussAnswer>()
        val history = mutableListOf<AnsweredRound>()

        /** O que foi enviado por último, guardado até a aplicação começar, para voltar ao painel se ela não rodar. */
        var sentQuestions: List<DiscussQuestion> = emptyList()
        var sentAnswers: Map<String, DiscussAnswer> = emptyMap()

        /** A IA está trabalhando: não há o que responder agora. */
        var busy = true
        var status = ""
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

    /** Começa a discussão dos [comments] marcados para discutir. Sem efeito se já há uma em andamento. */
    fun start(comments: List<ReviewComment>) {
        if (comments.isEmpty()) return
        if (session != null) {
            notify("Já há uma discussão em andamento", "Responda ou cancele a discussão no painel do Radar antes de começar outra.", NotificationType.WARNING)
            return
        }
        val current = Session(comments.sortedWith(ReviewComment.ORDER))
        session = current
        askQuestions(current)
    }

    fun setAnswer(question: DiscussQuestion, option: Int?, text: String?) {
        val current = session ?: return
        val answer = current.answers.getOrPut(question.id) { DiscussAnswer() }
        if (option != null) answer.option = option
        if (text != null) answer.text = text
    }

    /** Envia as respostas (as sem resposta valem a recomendação da IA) e deixa a IA aplicar ou perguntar uma última vez. */
    fun submit() {
        val current = session ?: return
        if (current.busy || current.questions.isEmpty()) return
        current.sentQuestions = current.questions
        current.sentAnswers = current.answers.toMap()
        current.history += AnsweredRound(current.questions, current.questions.map { Discussion.decision(it, current.answers[it.id]) })
        current.questions = emptyList()
        current.answers.clear()
        apply(current, mayAskAgain = current.rounds < Discussion.MAX_ROUNDS)
    }

    /** Desiste: os comentários continuam no Radar, ainda marcados para discutir. */
    fun cancel() {
        val current = session ?: return
        session = null
        if (current.busy) runner.progress?.cancel()
        publish()
    }

    private fun askQuestions(current: Session) {
        busy(current, "A IA está lendo o código e preparando as perguntas…")
        val file = runner.writeInstructions(Discussion.QUESTIONS_FILE, Discussion.questionInstructions(current.comments, current.history))
            ?: return fail(current, "Não consegui gravar as instruções para a IA.")
        val started = runner.run(
            AgentTask(
                "Discussão: perguntas",
                "Leia as instruções em $file, sem alterar nenhum arquivo, e responda só com o JSON de perguntas pedido.",
                write = false,
                origin = DefenseTabs.RADAR,
            ),
        ) { result -> onQuestions(current, result) }
        if (!started) fail(current, "A IA não pôde começar agora. Os comentários continuam no Radar.")
    }

    private fun onQuestions(current: Session, result: AgentResult) {
        if (session !== current) return
        if (!result.ok) return fail(current, result.error ?: "A IA não terminou. Os comentários continuam no Radar.")
        val questions = Discussion.parse(result.answer, current.comments.mapTo(mutableSetOf()) { it.id })
            ?: return fail(current, "A IA não devolveu as perguntas no formato esperado. Os comentários continuam no Radar; veja a resposta no Arsenal.")
        if (questions.isEmpty()) return apply(current, mayAskAgain = false)
        waitForAnswers(current, questions)
    }

    private fun apply(current: Session, mayAskAgain: Boolean) {
        val root = project.rootPath()
        val reviewFile = project.service<ReviewCommentService>().exportPaths(current.comments.map { it.filePath })
        if (reviewFile == null || root == null) return fail(current, "Não consegui gravar o .md da revisão.")
        val relative = runCatching { Path.of(root).relativize(reviewFile).toString().replace('\\', '/') }.getOrDefault(reviewFile.toString())
        busy(current, "A IA está aplicando as decisões…")
        val file = runner.writeInstructions(
            Discussion.DECISIONS_FILE,
            Discussion.applyInstructions(current.comments, current.history, relative, mayAskAgain),
        ) ?: return fail(current, "Não consegui gravar as instruções para a IA.")
        val ids = current.comments.joinToString(", ") { it.id }
        val started = runner.run(
            AgentTask(
                "Discussão: aplicar $ids",
                "Aplique as decisões da discussão seguindo as instruções em $file e marque [x] os comentários tratados em $relative.",
                write = true,
                origin = DefenseTabs.RADAR,
            ),
            "Abrir ${reviewFile.fileName}" to { _ -> runner.open(reviewFile) },
        ) { result -> onApplied(current, result, mayAskAgain) }
        if (!started) retryLater(current)
    }

    private fun onApplied(current: Session, result: AgentResult, mayAskAgain: Boolean) {
        if (session !== current) return
        if (!result.ok) return retryLater(current, result.error ?: "A IA não terminou.")
        if (mayAskAgain && result.files.isEmpty()) {
            val more = Discussion.parse(result.answer, current.comments.mapTo(mutableSetOf()) { it.id }).orEmpty()
            if (more.isNotEmpty()) return waitForAnswers(current, more)
        }
        session = null
        publish()
    }

    private fun waitForAnswers(current: Session, questions: List<DiscussQuestion>) {
        current.questions = questions
        current.rounds++
        current.busy = false
        current.status = if (current.rounds >= Discussion.MAX_ROUNDS) "Última rodada de perguntas: depois dela, a IA aplica." else ""
        publish()
        notify(
            if (questions.size == 1) "A IA tem 1 pergunta" else "A IA tem ${questions.size} perguntas",
            "Responda na aba Radar. Pergunta sem resposta usa a recomendação da IA.",
            NotificationType.INFORMATION,
        )
    }

    /** A aplicação não rodou: as respostas continuam no painel para enviar de novo. */
    private fun retryLater(current: Session, why: String? = null) {
        if (session !== current) return
        if (current.sentQuestions.isNotEmpty() && current.history.isNotEmpty()) {
            current.history.removeAt(current.history.lastIndex)
            current.questions = current.sentQuestions
            current.answers.putAll(current.sentAnswers)
        }
        current.busy = false
        current.status = (why ?: "A IA não pôde começar agora.") + " As perguntas continuam aqui: envie de novo."
        if (current.questions.isEmpty()) return fail(current, current.status)
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
}
