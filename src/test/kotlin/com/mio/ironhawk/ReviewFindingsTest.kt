package com.mio.ironhawk

import com.mio.irondome.comando.ReviewMarkdown
import com.mio.irondome.interceptor.AgentEvents
import com.mio.irondome.interceptor.AgentSettings
import com.mio.irondome.radar.Discussion
import com.mio.tomahawk.run.Guardrails
import com.mio.tomahawk.run.ShellKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.mio.irondome.interceptor.AgentCommand as DefenseCommand
import com.mio.irondome.interceptor.ShellKind as DefenseShell
import com.mio.tomahawk.run.AgentCommand as AttackCommand

/** Os defeitos achados na revisão do plugin, cada um com o cenário que o mostrava. */
class ReviewFindingsTest {

    @Test
    fun typographicQuotesCannotCloseAPowerShellString() {
        val hostile = "specs/001-x\u2019;Start-Process calc;\u2019"

        assertEquals("'specs/001-x\u2019\u2019;Start-Process calc;\u2019\u2019'", AttackCommand.quote(hostile, ShellKind.POWERSHELL))
        assertEquals("'Joe\u2019\u2019s app'", DefenseCommand.quote("Joe\u2019s app", DefenseShell.POWERSHELL))
        assertEquals("'it''s'", AttackCommand.quote("it's", ShellKind.POWERSHELL))
    }

    @Test
    fun anEmptyQuotedArgumentDoesNotBreakTheExtraArguments() {
        assertEquals(listOf("--append-system-prompt", "", "--x", "a b"), AttackCommand.splitArgs("--append-system-prompt \"\" --x 'a b'"))
        assertEquals(listOf("-m", ""), DefenseCommand.splitArgs("-m ''"))
    }

    @Test
    fun anObjectWinsOverABracketInsideItsText() {
        val json = AgentEvents.extractJson("""{"decision":"reply","reply":"corrigido, veja [x]"}""")

        assertTrue(json!!.isJsonObject)
        assertEquals("reply", json.asJsonObject.get("decision").asString)
        assertTrue("um array de verdade continua valendo", AgentEvents.extractJson("""[{"a": 1}]""")!!.isJsonArray)
    }

    @Test
    fun anAnswerOutOfFormatIsNotReadAsNoDoubts() {
        val ids = setOf("C1", "C3")

        assertNull("prosa com um [x] não é a resposta pedida", Discussion.parse("Feito. Marque [x] quando terminar.", ids))
        assertNull("campo que não é lista é formato errado, não exceção", Discussion.parse("""{"questions": null}""", ids))
        val round = Discussion.parse(
            """{"analyses": "nada", "questions": [{"comment": "c3 (Frete.kt)", "question": "Vale?", "options": ["a", "b"]},
               {"comment": "C1", "question": "Sem opções", "options": "x"}]}""",
            ids,
        )
        assertNotNull(round)
        assertEquals("o id vale sem diferenciar maiúsculas e com o arquivo ao lado", listOf("C3"), round!!.questions.map { it.commentId })
    }

    @Test
    fun aHeadingInsideTheQuotedSnippetIsCodeNotASection() {
        val markdown = """
            # Revisão: ordem-interceptacao-1

            ## `docs/guia.md`

            ### [ ] C1 — linhas 3–5

            **Modo:** aplicar direto

            ```markdown
            ## Geral
            ### [x] C9 — linha 1
            ```

            **Comentário:**

            Reescrever esta seção.

            ### [x] C2 — linha 9

            **Modo:** aplicar direto

            **Comentário:**

            Outro.
        """.trimIndent()

        val comments = ReviewMarkdown.parse(markdown)

        assertEquals(listOf("C1", "C2"), comments.map { it.id })
        assertEquals("o arquivo continua o mesmo depois do trecho", listOf("docs/guia.md", "docs/guia.md"), comments.map { it.filePath })
        assertEquals("Reescrever esta seção.", comments.first().text.trim())
    }

    @Test
    fun theWatchRecognisesCredentialFoldersAndLeavesTheProjectFilesAlone() {
        val root = "/workspaces/app"

        assertNotNull("pasta com barra no nome", Guardrails.verdict("shell", "cat ~/.docker/config.json", root))
        assertNotNull(Guardrails.verdict("read", "~/.config/gh/hosts.yml", root))
        assertNotNull(Guardrails.verdict("read", "/home/dev/.ssh/id_rsa", root))
        assertNull("o .npmrc do próprio projeto não é credencial da máquina", Guardrails.verdict("read", "/workspaces/app/.npmrc", root))
        assertNotNull(Guardrails.verdict("read", "/home/dev/.npmrc", root))
    }

    @Test
    fun aPathPastedWithQuotesIsStillAPath() {
        assertEquals("specify.exe", AgentSettings.pathOf("\"C:\\Users\\x\\.local\\bin\\specify.exe\"")?.fileName.toString())
        assertNull("um caminho impossível não lança", AgentSettings.pathOf("C:\\a<b>|c\u0000"))
    }
}
