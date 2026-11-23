package com.mio.ironhawk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path

class IronHawkCleanupTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun file(root: Path, relative: String, text: String = "x"): Path =
        root.resolve(IronHawkFolder.ROOT).resolve(relative).also {
            Files.createDirectories(it.parent)
            Files.writeString(it, text)
        }

    @Test
    fun eachFileGoesToItsCategoryAndTheGuardrailsNeverShow() {
        assertEquals(CleanupCategory.DEFENSE_AI, IronHawkCleanup.categoryOf("iron-dome/ia/20261118-discussao.md"))
        assertEquals(CleanupCategory.DEFENSE_AI, IronHawkCleanup.categoryOf("iron-dome/ia/pedidos/discussao-perguntas.md"))
        assertEquals(CleanupCategory.RADAR_HISTORY, IronHawkCleanup.categoryOf("iron-dome/ordem-interceptacao-18.md"))
        assertEquals(CleanupCategory.RADAR_HISTORY, IronHawkCleanup.categoryOf("iron-dome/historico-interceptacoes.md"))
        assertEquals(CleanupCategory.PATROL, IronHawkCleanup.categoryOf("iron-dome/pull-requests/pr-7.md"))
        assertEquals(CleanupCategory.LEARNING, IronHawkCleanup.categoryOf("iron-dome/defesa/aprendizado.md"))
        assertEquals(CleanupCategory.CRITERIA, IronHawkCleanup.categoryOf("iron-dome/criterios/pedido.yaml"))
        assertEquals(CleanupCategory.CONFERENCES, IronHawkCleanup.categoryOf("iron-dome/escudo/conferencias.json"))
        assertEquals(CleanupCategory.ATTACK_RUNS, IronHawkCleanup.categoryOf("tomahawk/execucoes/20261118.json"))
        assertEquals(CleanupCategory.ATTACK_INPUTS, IronHawkCleanup.categoryOf("tomahawk/pedidos/20261118.md"))
        assertEquals(CleanupCategory.OTHER, IronHawkCleanup.categoryOf("iron-dome/bugs/print.png"))
        assertNull(IronHawkCleanup.categoryOf("tomahawk/protecoes/claude.json"))
        assertNull("o que mantém a pasta fora do git nunca é apagado", IronHawkCleanup.categoryOf(".gitignore"))
    }

    @Test
    fun scanGroupsByCategoryKeepsTheFilesInUseAndSkipsEmptyCategories() {
        val root = temp.root.toPath()
        file(root, "iron-dome/ia/a.md", "12345")
        file(root, "iron-dome/ia/respostas/b.md")
        val open = file(root, "iron-dome/ordem-interceptacao-2.md")
        file(root, "iron-dome/ordem-interceptacao-1.md")
        file(root, "tomahawk/protecoes/claude.json")

        val groups = IronHawkCleanup.scan(root, keep = setOf(open))

        assertEquals(listOf(CleanupCategory.DEFENSE_AI, CleanupCategory.RADAR_HISTORY), groups.map { it.category })
        assertEquals(6L, groups[0].size)
        assertEquals(listOf("iron-dome/ordem-interceptacao-1.md"), groups[1].files.map { it.relative })
    }

    @Test
    fun noFolderMeansNothingToClean() {
        assertTrue(IronHawkCleanup.scan(temp.root.toPath()).isEmpty())
    }

    @Test
    fun foldersThatWouldBeEmptyGoDeepestFirstAndTheRootStays() {
        val root = temp.root.toPath()
        val answer = file(root, "iron-dome/ia/respostas/b.md")
        val log = file(root, "iron-dome/ia/a.md")
        file(root, "iron-dome/ordem-interceptacao-1.md")

        val folders = IronHawkCleanup.emptyFolders(root, listOf(answer, log))
        val ironDome = root.resolve(IronHawkFolder.DEFENSE)

        assertEquals(listOf(ironDome.resolve("ia/respostas"), ironDome.resolve("ia")).map { it.toAbsolutePath().normalize() }, folders)
        assertFalse("iron-dome ainda tem a revisão", ironDome.toAbsolutePath().normalize() in folders)
    }

    @Test
    fun sizesAreReadable() {
        assertEquals("512 B", IronHawkCleanup.formatSize(512))
        assertEquals("2 KB", IronHawkCleanup.formatSize(2048))
        assertEquals("1,5 MB", IronHawkCleanup.formatSize(1_572_864))
    }
}
