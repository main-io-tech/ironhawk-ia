package com.mio.irondome.caixapreta

import com.intellij.openapi.diagnostic.IdeaLoggingEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class BugReportTest {

    private val log = """
        2026-11-03 06:50:00,001 [  1000]   INFO - #c.i.o.a.Something - nada a ver
        2026-11-03 06:51:10,123 [  2000]  ERROR - #c.i.o.a.i.ActionUpdater - Falha ao atualizar
        java.lang.IllegalStateException: comentário sem arquivo
        	at com.intellij.openapi.actionSystem.impl.ActionUpdater.update(ActionUpdater.kt:10)
        	at com.mio.irondome.radar.CommentForm.submit(CommentForm.kt:120)
        	at com.mio.irondome.base.Visuals.paint(Visuals.kt:5)
        2026-11-03 06:52:00,000 [  3000]  ERROR - #c.i.o.v.Other - erro de outro plugin
        java.lang.RuntimeException: não é nosso
        	at com.other.Plugin.run(Plugin.kt:1)
        2026-11-03 06:53:00,000 [  4000]   WARN - #c.i.o.a.Warn - aviso
        	at com.mio.irondome.sentinela.ChangesPanel.refresh(ChangesPanel.kt:42)
    """.trimIndent()

    @Test
    fun readsOnlyErrorsAndWarningsThatPassThroughThePlugin() {
        val errors = PluginLog.parse(log, limit = 5)

        assertEquals(listOf("2026-11-03 06:53:00", "2026-11-03 06:51:10"), errors.map { it.time })
        assertEquals(listOf("WARN", "ERROR"), errors.map { it.level })
        assertEquals("Falha ao atualizar", errors[1].message)
    }

    @Test
    fun originIsTheFirstPluginFrame() {
        val error = PluginLog.parse(log, limit = 5).last()

        assertEquals("com.mio.irondome.radar.CommentForm.submit(CommentForm.kt:120)", error.origin)
        assertEquals(Mechanism.RADAR, Mechanism.ofFrame(error.origin!!))
    }

    @Test
    fun limitKeepsTheMostRecent() {
        assertEquals(listOf("2026-11-03 06:53:00"), PluginLog.parse(log, limit = 1).map { it.time })
    }

    @Test
    fun mechanismsMatchTabsAndFrames() {
        assertEquals(Mechanism.IMPACTO, Mechanism.ofTab("Raio de Impacto"))
        assertNull(Mechanism.ofTab(null))
        assertEquals(Mechanism.CAIXA_PRETA, Mechanism.ofFrame("com.mio.irondome.caixapreta.GifRecorder.record(ScreenCapture.kt:70)"))
        assertNull(Mechanism.ofFrame("com.mio.irondomeextra.Thing.run(Thing.kt:1)"))
    }

    @Test
    fun markdownPointsWhereTheBugIsInThePlugin() {
        val errors = PluginLog.parse(log, limit = 5)
        val markdown = BugReportMarkdown.render(report(Mechanism.SENTINELA, errors, screenshot = true))

        assertTrue(markdown.startsWith("# Bug: Árvore some ao filtrar\n"))
        assertTrue("pacote do mecanismo", "pacote `com.mio.irondome.sentinela`" in markdown)
        assertTrue("pasta do código", "`src/main/kotlin/com/mio/irondome/sentinela/`" in markdown)
        assertTrue(
            "origem do erro mais recente",
            "**Origem provável:** `com.mio.irondome.sentinela.ChangesPanel.refresh(ChangesPanel.kt:42)`" in markdown,
        )
        assertTrue("![Print da janela da IDE](print.png)" in markdown)
        assertFalse("sem gravação, sem GIF", "gravacao.gif" in markdown)
        assertTrue("passos vazios ficam marcados", "## O que era esperado\n\n_Não informado._" in markdown)
        assertTrue("| IDE | IntelliJ \\| 2025.2 |" in markdown)
    }

    @Test
    fun originInAnotherMechanismIsCalledOut() {
        val radarError = PluginError("t", "ERROR", "falha", listOf("\tat com.mio.irondome.radar.CommentForm.submit(CommentForm.kt:120)"))
        val markdown = BugReportMarkdown.render(report(Mechanism.SENTINELA, listOf(radarError)))

        assertTrue("que fica no mecanismo Radar (`src/main/kotlin/com/mio/irondome/radar/`)" in markdown)
    }

    @Test
    fun theIssueBodyDoesNotEmbedImagesBecauseTheApiCannotAttachThem() {
        val markdown = BugReportMarkdown.render(report(Mechanism.RADAR, emptyList(), screenshot = true, recording = true), inline = false)

        assertFalse("![" in markdown)
        assertTrue("O autor do relatório vai anexar o print e a gravação nos comentários desta issue." in markdown)
        assertTrue("Nenhum erro ou aviso do plugin no `idea.log`." in markdown)
        assertFalse("sem captura, sem a seção", "## Capturas" in BugReportMarkdown.render(report(Mechanism.RADAR, emptyList()), inline = false))
    }

    @Test
    fun aReportTheAiDidNotOrganizeKeepsTheTextAsWrittenAndSaysSo() {
        val raw = report(Mechanism.RADAR, emptyList()).copy(description = "A árvore sumiu quando digitei na busca.", steps = "", structured = false)

        val markdown = BugReportMarkdown.render(raw, inline = false)

        assertTrue("## O que aconteceu\n\nA árvore sumiu quando digitei na busca." in markdown)
        assertTrue("_Texto como o autor escreveu: não foi organizado em partes._" in markdown)
        assertFalse("Passos para reproduzir" in markdown)
        assertFalse("O que era esperado" in markdown)
    }

    @Test
    fun theIssueTitleKeepsThePrefixTheGitHubActionLooksFor() {
        assertEquals("[Bug] Sentinela: Árvore some ao filtrar", BugReports.issueTitle(report(Mechanism.SENTINELA, emptyList())))
    }

    @Test
    fun theAiOrganizesOnlyTheUsersTextAndNeverReadsTheProject() {
        val text = BugStructuring.instructions("Árvore some", "Digitei na busca e a árvore sumiu.")

        assertTrue("Digitei na busca e a árvore sumiu." in text)
        assertTrue("Não leia nem altere nenhum arquivo do projeto aberto" in text)
        assertTrue("Não invente nada" in text)
    }

    @Test
    fun readsTheStructuredPartsAndRejectsAnAnswerWithoutDescription() {
        val parsed = BugStructuring.parse("```json\n{\"description\": \"A árvore some\", \"steps\": \"1. Abra\", \"expected\": \"\"}\n```")!!

        assertEquals(StructuredBug("A árvore some", "1. Abra", ""), parsed)
        assertNull(BugStructuring.parse("""{"description": "  ", "steps": "x"}"""))
        assertNull(BugStructuring.parse("não consegui"))
    }

    @Test
    fun aForbiddenOrMissingRepositoryIsReportedAsMissingPermission() {
        val denied = BugGitHub.describe(com.mio.irondome.patrulha.GitHubException("negado", 403))
        val missing = BugGitHub.describe(com.mio.irondome.patrulha.GitHubException("não achei", 404))

        assertTrue("Sem permissão neste repositório (main-io-tech/ironhawk-ia)" in denied)
        assertEquals(denied, missing)
        assertEquals("rede caiu", BugGitHub.describe(com.mio.irondome.patrulha.GitHubException("rede caiu", 500)))
    }

    @Test
    fun theNewIssuePageCarriesTheTitleAndBodyEncodedAndCutWhenTooLong() {
        val url = BugGitHub.newIssueUrl("[Bug] Radar: falha & erro", "linha 1\nlinha 2")

        assertTrue(url.startsWith("https://github.com/main-io-tech/ironhawk-ia/issues/new?title="))
        assertTrue("title=%5BBug%5D+Radar%3A+falha+%26+erro" in url)
        assertTrue("body=linha+1%0Alinha+2" in url)
        assertTrue("texto+cortado" in BugGitHub.newIssueUrl("t", "a".repeat(7_000)))
    }

    @Test
    fun compactStackKeepsPluginFramesAndSummarizesTheRest() {
        val stack = listOf("java.lang.IllegalStateException: x") +
            (1..8).map { "\tat com.intellij.Frame$it.run(Frame.kt:$it)" } +
            "\tat com.mio.irondome.radar.CommentForm.submit(CommentForm.kt:120)" +
            "\tat com.intellij.Last.run(Last.kt:1)"

        assertEquals(
            listOf(
                "java.lang.IllegalStateException: x",
                "\tat com.intellij.Frame1.run(Frame.kt:1)",
                "\tat com.intellij.Frame2.run(Frame.kt:2)",
                "\tat com.intellij.Frame3.run(Frame.kt:3)",
                "\tat com.intellij.Frame4.run(Frame.kt:4)",
                "\t... 4 linhas omitidas",
                "\tat com.mio.irondome.radar.CommentForm.submit(CommentForm.kt:120)",
                "\t... 1 linha omitida",
            ),
            BugReportMarkdown.compactStack(stack),
        )
    }

    @Test
    fun repeatedErrorsAreGroupedWithTheirCountAndFirstTime() {
        val repeated = """
            2026-11-03 07:00:00,000 [  1]  ERROR - #com.mio.irondome.radar.ReviewCommentService - Falha ao salvar
            java.lang.IllegalStateException: sem arquivo
            	at com.mio.irondome.radar.ReviewCommentService.export(ReviewCommentService.kt:10)
            2026-11-03 07:05:00,000 [  2]   WARN - #com.mio.irondome.base.DefenseTabs - A aba Patrulha não pôde ser aberta
            java.lang.NoSuchMethodError: requestReLogin
            2026-11-03 07:10:00,000 [  3]  ERROR - #com.mio.irondome.radar.ReviewCommentService - Falha ao salvar
            java.lang.IllegalStateException: sem arquivo
            	at com.mio.irondome.radar.ReviewCommentService.export(ReviewCommentService.kt:10)
        """.trimIndent()

        val groups = PluginLog.group(PluginLog.parse(repeated, limit = 10))

        assertEquals(2, groups.size)
        assertEquals(2, groups[0].count)
        assertEquals("2026-11-03 07:10:00", groups[0].latest.time)
        assertEquals("2026-11-03 07:00:00", groups[0].firstTime)
        assertEquals(Mechanism.RADAR, groups[0].latest.mechanism)
        assertEquals("pelo logger, sem frame do plugin na pilha", Mechanism.BASE, groups[1].latest.mechanism)
        assertTrue(PluginLog.describe(groups[0]).startsWith("2026-11-03 07:10:00 · ERROR (2 vezes, desde 2026-11-03 07:00:00)"))
        val markdown = PluginLog.markdown(groups)
        assertTrue("### 2026-11-03 07:10:00 · ERROR · Radar (2 vezes, desde 2026-11-03 07:00:00)" in markdown)
        assertTrue("Origem no plugin: `com.mio.irondome.radar.ReviewCommentService.export(ReviewCommentService.kt:10)`" in markdown)
    }

    @Test
    fun ideErrorBecomesAPluginErrorWithTheUsersNote() {
        val event = IdeaLoggingEvent("Falha ao abrir", IllegalStateException("quebrou"))

        val error = IronDomeErrorSubmitter.errorOf(event, "cliquei em Revisar", LocalDateTime.of(2026, 11, 3, 7, 0))

        assertEquals("2026-11-03 07:00:00", error.time)
        assertEquals("Falha ao abrir · Nota: cliquei em Revisar", error.message)
        assertEquals("java.lang.IllegalStateException: quebrou", error.exception)
        assertTrue(error.stack.size > 1)
    }

    private fun report(
        mechanism: Mechanism,
        errors: List<PluginError>,
        screenshot: Boolean = false,
        recording: Boolean = false,
    ) = BugReport(
        title = "Árvore some ao filtrar",
        mechanism = mechanism,
        description = "Ao digitar na busca, a árvore fica vazia.",
        steps = "1. Abra a Sentinela\n2. Digite na busca",
        expected = "",
        errors = errors,
        context = listOf("IDE" to "IntelliJ | 2025.2"),
        screenshot = screenshot,
        recording = recording,
        createdAt = LocalDateTime.of(2026, 11, 3, 7, 0),
    )
}
