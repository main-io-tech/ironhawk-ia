package com.mio.irondome.escudo

import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CriteriaFilesTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun write(root: Path, name: String, text: String) {
        val dir = CriteriaFiles.folder(root)
        Files.createDirectories(dir)
        Files.writeString(dir.resolve(name), text)
    }

    @Test
    fun readsEveryYamlFileOfEveryRootAndRejectsARepeatedIdAcrossThem() {
        val api = temp.newFolder("api").toPath()
        val web = temp.newFolder("web").toPath()
        write(api, "roteiro.yaml", "- id: ROT-01\n  titulo: a\n")
        write(api, "notas.txt", "- id: X\n  titulo: ignorado\n")
        write(web, "acesso.yml", "- id: ACE-01\n  titulo: b\n- id: ROT-01\n  titulo: repetido\n")

        val catalog = CriteriaFiles.load(listOf(api, web, temp.root.toPath().resolve("inexistente")))

        assertEquals(listOf("ROT-01", "ACE-01"), catalog.criteria.map { it.id })
        assertEquals(1, catalog.errors.size)
        assertTrue(catalog.errors.single().toString(), catalog.errors.single().toString().startsWith("acesso.yml:3"))
    }

    @Test
    fun aProjectWithoutTheFolderHasAnEmptyCatalog() {
        assertTrue(CriteriaFiles.load(listOf(temp.newFolder("vazio").toPath())).isEmpty)
    }

    @Test
    fun theInstructionsCarryTheContractTheFolderAndTheSource() {
        val existing = CriteriaCatalog(listOf(Criterion("ROT-01", "Primeiro", null, null, setOf(Level.ACCEPTANCE), "roteiro.yaml", 1)), emptyList())

        val generate = CriteriaActions.generateInstructions("MAI-184", listOf("ROT-01"))
        val align = CriteriaActions.alignInstructions(existing)

        assertTrue(generate.contains("ironhawk/iron-dome/criterios/"))
        assertTrue(generate.contains("MAI-184"))
        assertTrue(generate.contains("ROT-01"))
        assertTrue(generate.contains("criterio:nenhum"))
        assertTrue(align.contains("`ROT-01`: Primeiro (exige: aceitacao)"))
        assertTrue(align.contains("Não chute"))
    }
}
