package com.mio.irondome.radar

import com.mio.irondome.comando.ReviewMarkdown
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RulesCheckTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun write(root: Path, name: String, text: String = "regra") {
        val file = root.resolve(name)
        Files.createDirectories(file.parent)
        Files.writeString(file, text)
    }

    private fun git(root: Path, vararg args: String): Boolean =
        ProcessBuilder(listOf("git") + args).directory(root.toFile()).redirectErrorStream(true).start().let { process ->
            process.inputStream.readAllBytes()
            process.waitFor() == 0
        }

    @Test
    fun detectsTheRuleFilesOfEveryAiToolAndSkipsBuildFolders() {
        val root = temp.newFolder().toPath()
        listOf(
            "CLAUDE.md", "api/AGENTS.md", ".claude/rules/seguranca.md", ".cursor/rules/estilo.mdc", ".cursorrules",
            ".github/copilot-instructions.md", "node_modules/pacote/CLAUDE.md", "build/AGENTS.md", "README.md", ".claude/settings.json",
            "ironhawk/iron-dome/ia/CLAUDE.md",
        ).forEach { write(root, it) }

        assertEquals(
            listOf(".claude/rules/seguranca.md", ".cursor/rules/estilo.mdc", ".cursorrules", ".github/copilot-instructions.md", "CLAUDE.md", "api/AGENTS.md"),
            RulesCheck.detectRuleFiles(root),
        )
    }

    @Test
    fun aRuleFileIsOnlyTheKnownNamesAndFolders() {
        assertTrue(RulesCheck.isRuleFile("modulo/CLAUDE.md"))
        assertTrue(RulesCheck.isRuleFile("a/b/.claude/rules/x.md"))
        assertFalse(RulesCheck.isRuleFile("docs/regras.md"))
        assertFalse(RulesCheck.isRuleFile(".claude/rules/imagem.png"))
        assertFalse(RulesCheck.isRuleFile("src/Claude.kt"))
    }

    @Test
    fun readsGitStatusIncludingUntrackedAndRenamedFiles() {
        val output = " M src/A.kt\u0000?? src/B.kt\u0000R  novo/C.kt\u0000antigo/C.kt\u0000A  src/D.kt\u0000"

        assertEquals(listOf("src/A.kt", "src/B.kt", "novo/C.kt", "src/D.kt"), RulesCheck.parseStatus(output))
        assertEquals(listOf("a/B.kt", "c.md"), RulesCheck.parseNames("a/B.kt\u0000c.md\u0000"))
    }

    @Test
    fun changedFilesAreTheUncommittedOnesAndTheBranchCommitsWithoutRulesOrPluginFolders() {
        val root = temp.newFolder().toPath()
        assumeTrue(git(root, "init", "-q", "-b", "main"))
        git(root, "config", "user.email", "t@t.t")
        git(root, "config", "user.name", "t")
        git(root, "config", "commit.gpgsign", "false")
        write(root, "src/Base.kt")
        write(root, "src/Velho.kt")
        assumeTrue(git(root, "add", ".") && git(root, "commit", "-q", "-m", "base"))
        assumeTrue(git(root, "checkout", "-q", "-b", "feature"))
        write(root, "src/DaBranch.kt")
        assumeTrue(git(root, "add", ".") && git(root, "commit", "-q", "-m", "branch"))
        write(root, "src/Base.kt", "mudou")
        write(root, "src/Novo.kt")
        write(root, "CLAUDE.md", "regras")
        write(root, "ironhawk/iron-dome/x.md")
        write(root, ".idea/workspace.xml")
        Files.delete(root.resolve("src/Velho.kt"))

        assertEquals(listOf("src/Base.kt", "src/DaBranch.kt", "src/Novo.kt"), RulesCheck.changedFiles(root))
    }

    @Test
    fun readsViolationsAndTheRulesTheAiCouldNotCheck() {
        val answer = """
            Conferi tudo.
            ```json
            {"violations": [
              {"rule": "Nunca desabilitar certificados", "ruleFile": "CLAUDE.md", "path": "./src\\Client.kt", "line": 12, "snippet": "trustAll = true", "why": "aceita qualquer certificado"},
              {"rule": "Sem caminho"},
              {"path": "src/B.kt", "line": 3}
            ], "unverifiable": ["Responder em português", "  "]}
            ```
        """.trimIndent()

        val result = RulesCheck.parse(answer, listOf("CLAUDE.md"), 4)!!

        assertEquals(1, result.violations.size)
        val violation = result.violations.single()
        assertEquals("src/Client.kt", violation.path)
        assertEquals(12, violation.line)
        assertEquals("CLAUDE.md", violation.ruleFile)
        assertEquals(listOf("Responder em português"), result.unverifiable)
        assertEquals(4, result.changedFiles)
    }

    @Test
    fun readsAPlainListAndKeepsAtMostThirtyViolations() {
        val items = (1..40).joinToString(",") { """{"rule": "R$it", "path": "src/A.kt", "line": $it}""" }

        val result = RulesCheck.parse("[$items]", emptyList(), 1)!!

        assertEquals(RulesCheck.MAX_VIOLATIONS, result.violations.size)
        assertTrue(result.unverifiable.isEmpty())
        assertEquals(1, RulesCheck.parse("""{"violations": [{"rule": "R", "path": "a.kt"}]}""", emptyList(), 1)!!.violations.single().line)
    }

    @Test
    fun anAnswerWithoutTheExpectedJsonIsNotAResult() {
        assertNull(RulesCheck.parse("Não encontrei nada de errado.", emptyList(), 1))
        assertNull(RulesCheck.parse("""{"outra": 1}""", emptyList(), 1))
        assertNotNull(RulesCheck.parse("""{"violations": []}""", emptyList(), 1))
    }

    @Test
    fun theInstructionsListTheRulesAndOnlyTheChangedFilesAndForbidWriting() {
        val text = RulesCheck.instructions(listOf("CLAUDE.md", ".claude/rules/a.md"), listOf("src/A.kt", "src/B.kt"))

        assertTrue("`CLAUDE.md`" in text && "`.claude/rules/a.md`" in text)
        assertTrue("`src/A.kt`" in text && "`src/B.kt`" in text)
        assertTrue("não altere nenhum arquivo" in text)
        assertTrue("unverifiable" in text)
    }

    @Test
    fun aViolationBecomesATextWithTheRuleThatIsTheKeyAgainstDuplicates() {
        val violation = RuleViolation("Nunca desabilitar certificados", "CLAUDE.md", "src/Client.kt", 12, "trustAll = true", "aceita qualquer certificado")

        val text = RulesCheck.commentText(violation)

        assertTrue(RulesCheck.mention(violation) in text)
        assertTrue("`CLAUDE.md`" in text && "aceita qualquer certificado" in text)
    }

    @Test
    fun theOriginOfARuleCheckSurvivesTheMarkdown() {
        val comment = ReviewComment(
            id = "C1",
            reviewId = 1,
            filePath = "src/Client.kt",
            text = "**Regra violada:** «Nunca desabilitar certificados»",
            origin = CommentOrigin.REGRAS,
        ).also { it.exported = ExportedVersion.of(it) }

        val markdown = ReviewMarkdown.render("Revisão 1", listOf(comment), LocalDateTime.now(), LocalDateTime.now())

        assertTrue("**Origem:** Regras de IA" in markdown)
        assertEquals(CommentOrigin.REGRAS, ReviewMarkdown.parse(markdown).single().origin)
    }
}
