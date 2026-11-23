package com.mio.ironhawk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path

class IronHawkFolderTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun write(root: Path, relative: String, text: String = "x") {
        val file = root.resolve(relative)
        Files.createDirectories(file.parent)
        Files.writeString(file, text)
    }

    @Test
    fun `move as pastas antigas da raiz para dentro de ironhawk`() {
        val root = temp.root.toPath()
        write(root, "iron-dome/ordem-interceptacao-1.md")
        write(root, "iron-dome/ia/respostas/r.md")
        write(root, "tomahawk/execucoes/20261104-plan.md")

        assertEquals(listOf("iron-dome", "tomahawk"), IronHawkFolder.migrate(root))

        assertTrue(Files.exists(root.resolve("ironhawk/iron-dome/ordem-interceptacao-1.md")))
        assertTrue(Files.exists(root.resolve("ironhawk/iron-dome/ia/respostas/r.md")))
        assertTrue(Files.exists(root.resolve("ironhawk/tomahawk/execucoes/20261104-plan.md")))
        assertFalse(Files.exists(root.resolve("iron-dome")))
        assertFalse(Files.exists(root.resolve("tomahawk")))
        assertEquals(emptyList<String>(), IronHawkFolder.migrate(root))
    }

    @Test
    fun `junta com o que ja esta em ironhawk sem sobrescrever`() {
        val root = temp.root.toPath()
        write(root, "tomahawk/execucoes/a.md", "antigo")
        write(root, "tomahawk/execucoes/b.md", "so no antigo")
        write(root, "ironhawk/tomahawk/execucoes/a.md", "novo")

        IronHawkFolder.migrate(root)

        assertEquals("novo", Files.readString(root.resolve("ironhawk/tomahawk/execucoes/a.md")))
        assertEquals("so no antigo", Files.readString(root.resolve("ironhawk/tomahawk/execucoes/b.md")))
        assertEquals("antigo", Files.readString(root.resolve("tomahawk/execucoes/a.md")))
        assertFalse(Files.exists(root.resolve("tomahawk/execucoes/b.md")))
    }

    @Test
    fun `nao mexe numa pasta tomahawk que e do projeto`() {
        val root = temp.root.toPath()
        write(root, "tomahawk/src/Main.kt")

        assertEquals(emptyList<String>(), IronHawkFolder.migrate(root))
        assertTrue(Files.exists(root.resolve("tomahawk/src/Main.kt")))
        assertFalse(Files.exists(root.resolve("ironhawk")))
    }

    @Test
    fun `a pasta fica fora do git por um gitignore proprio, sem mexer no do projeto`() {
        val root = temp.root.toPath()
        write(root, ".gitignore", "node_modules/\n")

        IronHawkFolder.keepLocal(root, create = false)
        assertFalse("sem a pasta, não cria nada", Files.exists(root.resolve("ironhawk")))

        IronHawkFolder.keepLocal(root)

        val ignore = root.resolve("ironhawk/.gitignore")
        assertTrue("ignora tudo, inclusive ele mesmo", Files.readString(ignore).lines().any { it.trim() == "*" })
        assertEquals("node_modules/\n", Files.readString(root.resolve(".gitignore")))
    }

    @Test
    fun `um gitignore que ja esta na pasta e de quem o editou`() {
        val root = temp.root.toPath()
        write(root, "ironhawk/.gitignore", "")

        IronHawkFolder.keepLocal(root)

        assertEquals("", Files.readString(root.resolve("ironhawk/.gitignore")))
    }

    @Test
    fun `a poda deixa so os registros mais novos e nao toca nas subpastas nem em outros arquivos`() {
        val dir = temp.root.toPath().resolve("ironhawk/iron-dome/ia")
        (1..5).forEach { number ->
            write(dir, "2026111$number-run.md")
            Files.setLastModifiedTime(dir.resolve("2026111$number-run.md"), java.nio.file.attribute.FileTime.fromMillis(1_000_000L * number))
        }
        write(dir, "pedidos/antigo.md")
        write(dir, "resposta.md.fim")

        assertEquals(3, IronHawkFolder.prune(dir, keep = 2))

        assertEquals(setOf("20261114-run.md", "20261115-run.md", "pedidos", "resposta.md.fim"), Files.list(dir).use { files -> files.map { it.fileName.toString() }.toList().toSet() })
        assertTrue(Files.exists(dir.resolve("pedidos/antigo.md")))
        assertEquals(0, IronHawkFolder.prune(dir.resolve("nao-existe"), keep = 2))
    }
}
