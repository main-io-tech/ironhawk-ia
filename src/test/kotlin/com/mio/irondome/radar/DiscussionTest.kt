package com.mio.irondome.radar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DiscussionTest {

    private val ids = setOf("C1", "C2")

    private fun comment(id: String, text: String = "separar este pacote") =
        ReviewComment(id = id, reviewId = 1, filePath = "src/A.kt", startLine = 9, endLine = 11, snippet = "val a = 1", text = text, discuss = true)

    private fun question(recommended: Int = 1) = DiscussQuestion(
        "C1-1", "C1", "O que fazer?", listOf(DiscussOption("Manter", "nada muda"), DiscussOption("Remover", "apaga")), recommended,
    )

    @Test
    fun readsQuestionsWithTheRecommendationByNumberOrByLabel() {
        val answer = """
            ```json
            {"questions": [
              {"comment": "C1", "question": "O que fazer?", "options": [{"label": "Manter", "detail": "nada muda"}, {"label": "Remover", "detail": "apaga"}], "recommended": 2},
              {"comment": "C2", "question": "Quando?", "options": ["Agora", "Depois"], "recommended": "depois"}
            ]}
            ```
        """.trimIndent()

        val questions = Discussion.parse(answer, ids)!!

        assertEquals(listOf("C1-1", "C2-1"), questions.map { it.id })
        assertEquals(1, questions[0].recommended)
        assertEquals("nada muda", questions[0].options[0].detail)
        assertEquals(listOf("Agora", "Depois"), questions[1].options.map { it.label })
        assertEquals(1, questions[1].recommended)
    }

    @Test
    fun skipsQuestionsOfUnknownCommentsOrWithFewerThanTwoOptions() {
        val answer = """{"questions": [
            {"comment": "C9", "question": "De quem?", "options": ["a", "b"]},
            {"comment": "C1", "question": "Uma só?", "options": ["a"]},
            {"comment": "C1", "question": "", "options": ["a", "b"]},
            {"comment": "C1", "question": "Vale?", "options": ["a", "b"], "recommended": 7}
        ]}"""

        val questions = Discussion.parse(answer, ids)!!

        assertEquals(listOf("Vale?"), questions.map { it.text })
        assertEquals("recomendação fora das opções vira nenhuma", -1, questions.single().recommended)
    }

    @Test
    fun keepsAtMostThreeQuestionsPerCommentAndTwelveInAll() {
        val perComment = (1..5).joinToString(",") { """{"comment": "C1", "question": "P$it", "options": ["a", "b"]}""" }

        assertEquals(Discussion.MAX_QUESTIONS_PER_COMMENT, Discussion.parse("""{"questions": [$perComment]}""", ids)!!.size)

        val many = (1..30).joinToString(",") { """{"comment": "${if (it % 2 == 0) "C1" else "C2"}", "question": "P$it", "options": ["a", "b"]}""" }
        assertTrue(Discussion.parse("""{"questions": [$many]}""", ids)!!.size <= Discussion.MAX_QUESTIONS)
    }

    @Test
    fun noQuestionsIsAnEmptyListAndAnythingElseIsInvalid() {
        assertEquals(emptyList<DiscussQuestion>(), Discussion.parse("""{"questions": []}""", ids))
        assertNull(Discussion.parse("Acho que não há dúvidas.", ids))
        assertNull(Discussion.parse("""{"outra": 1}""", ids))
        assertNotNull(Discussion.parse("""[{"comment": "C1", "question": "Vale?", "options": ["a", "b"]}]""", ids))
    }

    @Test
    fun anUnansweredQuestionUsesTheAiRecommendation() {
        val decision = Discussion.decision(question(recommended = 1), null)

        assertTrue(decision.startsWith("Remover"))
        assertTrue("recomendação da IA" in decision)
        assertTrue("sem recomendação, a IA assume a opção mais segura", "mais segura" in Discussion.decision(question(recommended = -1), DiscussAnswer()))
    }

    @Test
    fun theUsersChoiceAndTextWinOverTheRecommendation() {
        assertEquals("Manter", Discussion.decision(question(), DiscussAnswer(option = 0)))
        assertEquals("Resposta do usuário: depende do prazo", Discussion.decision(question(), DiscussAnswer(text = " depende do prazo ")))
        assertEquals("Manter. O usuário acrescentou: só até a sprint", Discussion.decision(question(), DiscussAnswer(option = 0, text = "só até a sprint")))
    }

    @Test
    fun theQuestionRoundOnlyReadsAndAsksForJson() {
        val text = Discussion.questionInstructions(listOf(comment("C1")), emptyList())

        assertTrue("não altere nenhum" in text)
        assertTrue("```json" in text)
        assertTrue("C1" in text && "separar este pacote" in text && "val a = 1" in text)
        assertTrue("linhas 10–12" in text)
        assertFalse("Perguntas e decisões" in text)
    }

    @Test
    fun theApplyRoundMayAskOnceMoreOnlyWhenAllowedAndOtherwiseMustApplyAndRegisterAssumptions() {
        val history = listOf(AnsweredRound(listOf(question()), listOf("Remover")))

        val first = Discussion.applyInstructions(listOf(comment("C1")), history, "ironhawk/iron-dome/ordem-interceptacao-1.md", mayAskAgain = true)
        val last = Discussion.applyInstructions(listOf(comment("C1")), history, "ironhawk/iron-dome/ordem-interceptacao-1.md", mayAskAgain = false)

        assertTrue("Isso vale uma única vez" in first)
        assertFalse("última rodada" in first)
        assertTrue("Esta é a última rodada" in last && "Suposições" in last)
        assertFalse("```json" in last)
        listOf(first, last).forEach {
            assertTrue("Decisão: Remover" in it)
            assertTrue("ordem-interceptacao-1.md" in it)
            assertTrue("[x]" in it)
        }
    }

    @Test
    fun aProjectCommentIsDescribedWithoutAFile() {
        val general = ReviewComment(id = "C2", reviewId = 1, filePath = "", text = "padronizar logs", target = CommentTarget.PROJECT, discuss = true)

        assertTrue("projeto inteiro" in Discussion.questionInstructions(listOf(general), emptyList()))
    }
}
