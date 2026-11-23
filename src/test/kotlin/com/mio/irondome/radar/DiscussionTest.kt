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

    private fun question(id: String = "Q1", condition: DiscussCondition? = null) = DiscussQuestion(
        id, "C1", "O que fazer com o legado?",
        listOf(DiscussOption("Remover", "Apaga 2 arquivos.", changes = true), DiscussOption("Manter como está", "Nada muda.", changes = false)),
        recommended = 0, condition = condition,
    )

    @Test
    fun readsQuestionsWithTheRecommendationByNumberOrByLabel() {
        val answer = """
            ```json
            {"questions": [
              {"id": "Q1", "comment": "C1", "question": "O que fazer?", "options": [{"label": "Manter como está", "effect": "nada muda", "changes": false}, {"label": "Remover", "effect": "apaga", "changes": true}], "recommended": 2, "reason": "não roda"},
              {"comment": "C2", "question": "Quando?", "options": ["Agora", "Manter"], "recommended": "manter"}
            ]}
            ```
        """.trimIndent()

        val questions = Discussion.parse(answer, ids)!!.questions

        assertEquals("sem id, a pergunta ganha um", listOf("Q1", "Q2"), questions.map { it.id })
        assertEquals(1, questions[0].recommended)
        assertEquals("nada muda", questions[0].options[0].effect)
        assertEquals("não roda", questions[0].reason)
        assertEquals(listOf(false, true), questions[0].options.map { it.changes })
        assertEquals(listOf("Agora", "Manter"), questions[1].options.map { it.label })
        assertEquals("sem `changes`, o rótulo de manter diz que nada é alterado", listOf(true, false), questions[1].options.map { it.changes })
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

        val questions = Discussion.parse(answer, ids)!!.questions

        assertEquals(listOf("Vale?"), questions.map { it.text })
        assertEquals("recomendação fora das opções vira nenhuma", -1, questions.single().recommended)
    }

    @Test
    fun keepsAtMostTwelveQuestionsAndFourOptions() {
        val many = (1..20).joinToString(",") { """{"comment": "C${1 + it % 2}", "question": "Q$it?", "options": ["a", "b", "c", "d", "e", "f"]}""" }

        val questions = Discussion.parse("""{"questions": [$many]}""", ids)!!.questions

        assertEquals(Discussion.MAX_QUESTIONS, questions.size)
        assertEquals(4, questions.first().options.size)
    }

    @Test
    fun noQuestionsIsAnEmptyListAndAnythingElseIsInvalid() {
        assertEquals(emptyList<DiscussQuestion>(), Discussion.parse("""{"questions": []}""", ids)!!.questions)
        assertNull(Discussion.parse("Acho que não há dúvidas.", ids))
        assertNull(Discussion.parse("""{"outra": 1}""", ids))
        assertNotNull(Discussion.parse("""[{"comment": "C1", "question": "Vale?", "options": ["a", "b"]}]""", ids))
    }

    @Test
    fun readsTheShortAnswerToEachCommentTheReplyAndTheNotes() {
        val answer = """{
            "reply": "Não é migração: roda a cada subida.",
            "notes": "Frete.kt:12",
            "answers": [{"comment": "c1 (A.kt)", "verdict": "Pode remover", "answer": "Ninguém chama."}, {"comment": "C9", "verdict": "x", "answer": "y"}],
            "questions": []
        }"""

        val round = Discussion.parse(answer, ids)!!

        assertEquals(mapOf("C1" to DiscussAnswer("Pode remover", "Ninguém chama.")), round.answers)
        assertEquals("Não é migração: roda a cada subida.", round.reply)
        assertEquals("Frete.kt:12", round.notes)
        assertEquals(
            "o formato antigo, com analyses, ainda é lido",
            "está no common porque os quatro módulos usam",
            Discussion.parse("""{"analyses": [{"comment": "C1", "answer": "está no common porque os quatro módulos usam"}], "questions": []}""", ids)!!.answers.getValue("C1").text,
        )
    }

    @Test
    fun aQuestionThatDependsOnAnotherIsOnlyAskedAfterTheAnswerItNeeds() {
        val answer = """{"questions": [
            {"id": "Q1", "comment": "C1", "question": "Remover?", "options": ["Remover", "Manter"]},
            {"id": "Q2", "comment": "C1", "onlyIf": "Q1=1", "question": "E o CSV?", "options": ["Sim", "Manter"]},
            {"id": "Q3", "comment": "C1", "onlyIf": "Q9=1", "question": "Sem dona?", "options": ["Sim", "Manter"]}
        ]}"""

        val (first, second, third) = Discussion.parse(answer, ids)!!.questions

        assertEquals(DiscussCondition("Q1", setOf(0)), second.condition)
        assertNull("a condição que aponta para uma pergunta que não veio é ignorada, senão a pergunta sumiria", third.condition)
        assertFalse("antes da resposta, a dependente não aparece", Discussion.applies(second, emptyList()))
        assertTrue(Discussion.applies(second, listOf(DiscussDecision(first, 0))))
        assertFalse("com a outra opção, a pergunta não se aplica", Discussion.applies(second, listOf(DiscussDecision(first, 1))))
        assertTrue(Discussion.applies(first, emptyList()))
    }

    @Test
    fun theFirstRoundAsksForShortAnswersAndOneDecisionAtATime() {
        val text = Discussion.questionInstructions(listOf(comment("C1")))

        assertTrue("só leitura", "não altere nem" in text && "crie arquivo nenhum" in text)
        assertTrue("como na entrevista: uma pergunta por vez", "uma pergunta por" in text)
        assertTrue("o que o código responde não vira pergunta", "Apure antes de perguntar" in text)
        assertTrue("cada pergunta vem com a recomendação", "`recommended`" in text && "`reason`" in text)
        assertTrue("a resposta é curta", "até 240 caracteres" in text && "Seja breve" in text)
        assertTrue("`verdict`" in text && "`onlyIf`" in text && "`changes`" in text)
        assertTrue("```json" in text)
        assertTrue("### C1 · `src/A.kt`, linhas 10–12" in text && "separar este pacote" in text && "val a = 1" in text)
        listOf("`how`", "`why`", "`evaluation`", "`impact`", "`preview`").forEach { assertFalse("a análise longa saiu: $it", it in text) }
    }

    @Test
    fun aTextFromTheUserGoesBackToTheAiWithWhatWasAlreadyDecided() {
        val first = question("Q1")
        val open = question("Q2").copy(text = "E o CSV?")
        val later = question("Q3", DiscussCondition("Q2", setOf(0))).copy(text = "Aviso quem consome?")

        val text = Discussion.replyInstructions(
            listOf(comment("C1")),
            mapOf("C1" to DiscussAnswer("Pode remover", "Ninguém chama.")),
            listOf(DiscussDecision(first, 0)),
            open,
            "isso é para uma migração?",
            listOf(open, later),
            "Frete.kt:12",
        )

        assertTrue("A pergunta em aberto é a `Q2`" in text)
        assertTrue("a IA não decide por ele", "Você não decide por ele" in text)
        assertTrue("o que já foi decidido vai junto", "`Q1` (C1) O que fazer com o legado? → **Remover**" in text)
        assertTrue("a pergunta em aberto e as que faltam", "`Q2` (C1) **(em aberto)** E o CSV?" in text && "`Q3` (C1) · só se Q2=1 Aviso quem consome?" in text)
        assertTrue("as anotações da IA voltam para ela", "Frete.kt:12" in text)
        assertTrue(text.trimEnd().endsWith("isso é para uma migração?"))
        assertTrue("só leitura", "não" in text && "altere nem crie arquivo nenhum" in text)
    }

    @Test
    fun theDecisionIsWrittenIntoTheCommentForTheAiToApply() {
        val decided = Discussion.decidedText("Por que isso está no common?", listOf(DiscussDecision(question(), 0)), "18/11/2026")

        assertEquals(
            "Por que isso está no common?\n\n**Decidido na discussão (18/11/2026):** aplique exatamente isto, sem discutir de novo.\n\n" +
                "- O que fazer com o legado? **Remover.** Apaga 2 arquivos.",
            decided,
        )
        assertEquals(
            "uma discussão nova troca o bloco da anterior",
            "Por que isso está no common?\n\n**Decidido na discussão (19/11/2026):** aplique exatamente isto, sem discutir de novo.\n\n" +
                "- O que fazer com o legado? **Manter como está.** Nada muda.",
            Discussion.decidedText(decided, listOf(DiscussDecision(question(), 1)), "19/11/2026"),
        )
        assertEquals("sem decisão, o comentário fica como era", "Por que isso está no common?", Discussion.decidedText(decided, emptyList(), "19/11/2026"))
    }

    @Test
    fun aProjectCommentIsDescribedWithoutAFile() {
        val general = ReviewComment(id = "C1", reviewId = 1, text = "padronizar os logs", target = CommentTarget.PROJECT, discuss = true)

        assertTrue("### C1 · projeto inteiro" in Discussion.questionInstructions(listOf(general)))
    }
}
