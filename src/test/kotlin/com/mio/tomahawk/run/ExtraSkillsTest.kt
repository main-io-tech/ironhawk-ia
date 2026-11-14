package com.mio.tomahawk.run

import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ExtraSkillsTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun skill(base: Path, folder: String, name: String?, description: String = "faz algo") {
        val file = base.resolve(folder).resolve("SKILL.md")
        Files.createDirectories(file.parent)
        val header = buildString {
            appendLine("---")
            if (name != null) appendLine("name: $name")
            appendLine("description: $description")
            appendLine("---")
        }
        Files.writeString(file, header + "\n# Corpo\n")
    }

    @Test
    fun findsTheSkillsOfTheProjectTheHomeAndAFolderTheUserPointedTo() {
        val root = temp.newFolder("projeto").toPath()
        val home = temp.newFolder("home").toPath()
        val extra = temp.newFolder("extra").toPath()
        skill(root, ".claude/skills/tdd", "tdd")
        skill(root, ".cursor/skills/revisar", "revisar")
        skill(home, ".claude/skills/grilling", "grilling")
        skill(extra, "skills/qa", "qa")

        val skills = ExtraSkills.discover(root, home, listOf(extra.resolve("skills").toString()))

        assertEquals(listOf("grilling", "qa", "revisar", "tdd"), skills.map { it.name })
        assertEquals(
            mapOf("tdd" to SkillScope.PROJECT, "revisar" to SkillScope.PROJECT, "grilling" to SkillScope.GLOBAL, "qa" to SkillScope.CUSTOM),
            skills.associate { it.name to it.scope },
        )
    }

    @Test
    fun theFolderThePointedToCanBeASingleSkill() {
        val extra = temp.newFolder("uma").toPath()
        skill(extra, "minha-skill", "minha-skill")

        val skills = ExtraSkills.discover(null, null, listOf(extra.resolve("minha-skill").toString()))

        assertEquals(listOf("minha-skill"), skills.map { it.name })
    }

    @Test
    fun skipsTheSpecKitSkillsAndKeepsTheProjectOneWhenTheNameRepeats() {
        val root = temp.newFolder("projeto").toPath()
        val home = temp.newFolder("home").toPath()
        skill(root, ".claude/skills/speckit-plan", "speckit-plan")
        skill(root, ".claude/skills/tdd", "tdd", "do projeto")
        skill(home, ".claude/skills/tdd", "tdd", "global")

        val skills = ExtraSkills.discover(root, home, emptyList())

        assertEquals(listOf("tdd"), skills.map { it.name })
        assertEquals("do projeto", skills.single().description)
        assertEquals(SkillScope.PROJECT, skills.single().scope)
    }

    @Test
    fun aSkillWithoutNameUsesItsFolderName() {
        val root = temp.newFolder("projeto").toPath()
        skill(root, ".agents/skills/sem-nome", null)

        assertEquals(listOf("sem-nome"), ExtraSkills.discover(root, null, emptyList()).map { it.name })
    }

    @Test
    fun missingFoldersGiveNoSkills() {
        assertTrue(ExtraSkills.discover(temp.newFolder("vazio").toPath(), temp.root.toPath().resolve("nao-existe"), listOf("/nao/existe")).isEmpty())
    }

    @Test
    fun readsTheHeaderWithQuotesAndFoldedDescriptions() {
        assertEquals("a" to "b c", ExtraSkills.header("---\nname: a\ndescription: \"b c\"\n---\ncorpo"))
        assertEquals("a" to "linha um linha dois", ExtraSkills.header("---\nname: a\ndescription: >\n  linha um\n  linha dois\nother: x\n---\n"))
        assertEquals("a" to "x", ExtraSkills.header("---\r\nname: 'a'\r\ndescription: x\r\n---\r\n"))
        assertEquals(null to null, ExtraSkills.header("# sem cabeçalho"))
        assertEquals(null to null, ExtraSkills.header("---\nname: a\nsem fim"))
    }

    @Test
    fun theInstructionsCarryTheWholeSkillTheFeatureAndTheUserText() {
        val skill = Skill("tdd", "faz testes primeiro", Path.of("/home/x/.claude/skills/tdd/SKILL.md"), SkillScope.GLOBAL)

        val text = ExtraSkills.instructions(skill, "---\nname: tdd\n---\n# TDD\nEscreva o teste antes.", "specs/001-login", "só o módulo de auth")

        assertTrue("/tdd" in text)
        assertTrue("`specs/001-login`" in text)
        assertTrue("só o módulo de auth" in text)
        assertTrue("Escreva o teste antes." in text)
        assertTrue("tdd/SKILL.md" in text && "global" in text)
    }

    @Test
    fun withoutFeatureOrTextTheInstructionsSayNone() {
        val skill = Skill("qa", "", Path.of("/x/qa/SKILL.md"), SkillScope.CUSTOM)

        val text = ExtraSkills.instructions(skill, "corpo", null, "  ")

        assertTrue("**Feature ativa:** nenhuma" in text)
        assertTrue("**Instrução do usuário:** nenhuma" in text)
    }

    @Test
    fun aLongSkillIsCutAndPointsToTheFile() {
        val skill = Skill("grande", "", Path.of("/x/grande/SKILL.md"), SkillScope.PROJECT)

        val text = ExtraSkills.instructions(skill, "a".repeat(ExtraSkills.CONTENT_MAX + 10), null, "")

        assertTrue("foi cortada" in text)
        assertTrue("a".repeat(ExtraSkills.CONTENT_MAX) in text)
        assertTrue("a".repeat(ExtraSkills.CONTENT_MAX + 1) !in text)
    }
}
