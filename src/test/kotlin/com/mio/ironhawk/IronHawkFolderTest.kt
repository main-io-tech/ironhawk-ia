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
}
