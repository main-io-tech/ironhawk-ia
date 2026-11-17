package com.mio.irondome.defesa

import com.mio.irondome.impacto.CommentSource
import com.mio.irondome.impacto.ImpactComment
import com.mio.irondome.radar.CommentTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DefenseLogicTest {

    private fun commit(sha: String, author: String, subject: String, vararg files: String) =
        "\u001E$sha\t$author\t$subject\n\n${files.joinToString("\n")}\n"

    private val history = ChangeHistory.parse(
        commit("a1b2c3d4", "Ana", "fix: frete negativo", "src/Frete.kt", "src/FreteTest.kt") +
            commit("b1b2c3d4", "Ana", "feat: frete grátis", "src/Frete.kt", "src/FreteTest.kt") +
            commit("c1b2c3d4", "Ana", "corrige arredondamento", "src/Frete.kt", "src/FreteTest.kt") +
            commit("d1b2c3d4", "Ana", "refactor", "src/Frete.kt", "src/FreteTest.kt") +
            commit("e1b2c3d4", "Bia", "docs", "README.md"),
    )

    private fun comment(
        id: String, start: Int, end: Int, path: String = "src/Frete.kt", source: CommentSource = CommentSource.RADAR, text: String = "texto de $id",
    ) = ImpactComment(id, source, path, CommentTarget.LINES, start, end, text)

    @Test
    fun `historico conta mudancas, correcoes, autores e co-mudancas`() {
        val frete = history.of("src/Frete.kt")
        assertEquals(4, frete.commits)
        assertEquals(2, frete.fixes)
        assertEquals(setOf("Ana"), frete.authors)
        assertTrue(frete.singleAuthor)
        assertEquals("src/FreteTest.kt", frete.strongCoChanges.single().path)
        assertEquals(100, frete.strongCoChanges.single().percent)
        assertEquals(0, history.of("src/Nada.kt").commits)
    }

    @Test
    fun `sobreposicao de comentarios por intervalo no mesmo arquivo`() {
        val overlaps = CommentConflicts.overlaps(
            listOf(
                comment("C1", 10, 14),
                comment("P1", 12, 12, source = CommentSource.PATRULHA),
                comment("C2", 20, 22),
                comment("C3", 10, 14, path = "src/Outro.kt"),
            ),
        )
        val pair = overlaps.single()
        assertEquals("a ordem dos dois não muda o par", pair.key, CommentOverlap(pair.second, pair.first).key)
        val nextReview = CommentConflicts.overlaps(listOf(comment("C1", 10, 14, text = "outro pedido"), comment("P1", 12, 12, source = CommentSource.PATRULHA)))
        assertNotEquals("C1 de outra revisão é outro par", pair.key, nextReview.single().key)
        assertEquals("linha 12", pair.location)
        assertNotEquals("as fontes dos dois são diferentes", pair.first.source, pair.second.source)
        val instructions = CommentConflicts.synthesisInstructions(overlaps)
        assertTrue("texto de C1" in instructions && "texto de P1" in instructions)
    }

    @Test
    fun `aprendizado volta igual do md e vira preferencias no pedido`() {
        val state = FeedbackState(
            accepted = 2,
            discarded = 4,
            downgraded = 3,
            bugsProposed = 4,
            acceptedByTheme = mapOf(ReviewTheme.NULLS to 2),
            discardedByTheme = mapOf(ReviewTheme.NAMING to 4),
            rules = listOf("No pacote legacy, não sugira refatoração."),
        )
        val parsed = FeedbackMarkdown.parse(FeedbackMarkdown.render(state))
        assertEquals(state, parsed)
        val hints = parsed.promptHints()
        assertTrue(hints.any { "nomenclatura" in it })
        assertTrue(hints.any { "**Bug:**" in it })
        assertTrue("No pacote legacy, não sugira refatoração." in hints)
        assertTrue(FeedbackState().promptHints().isEmpty())
        assertEquals(ReviewTheme.NULLS, ReviewTheme.of("Aqui pode dar NullPointerException"))
    }

    @Test
    fun `dossie junta decisoes por arquivo e historico`() {
        val decisions = ClassDossier.decisions("# Decisões\n\n## Frete.kt\n- É thread-safe: só a EDT escreve.\n\n## src/Outro.kt\n- Legado.\n")
        assertEquals(listOf("É thread-safe: só a EDT escreve."), ClassDossier.decisionsFor("src/Frete.kt", decisions))
        val text = ClassDossier.render(
            listOf(
                DossierEntry("src/Frete.kt", history.of("src/Frete.kt"), listOf("Radar · resolvido: lista vazia"), ClassDossier.decisionsFor("src/Frete.kt", decisions)),
                DossierEntry("src/Vazio.kt", null, emptyList(), emptyList()),
            ),
        )
        assertTrue("fix: frete negativo" in text)
        assertTrue("Costuma mudar junto" in text)
        assertTrue("Decisão do time" in text)
        assertFalse("Vazio.kt" in text)
        assertEquals("", ClassDossier.render(emptyList()))
    }
}
