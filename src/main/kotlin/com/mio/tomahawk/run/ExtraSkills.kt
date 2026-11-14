package com.mio.tomahawk.run

import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.util.messages.Topic
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path

/** De onde veio a skill: do projeto, da máquina (global) ou de uma pasta que o usuário indicou. */
enum class SkillScope(val label: String) {
    PROJECT("Projeto"),
    GLOBAL("Global"),
    CUSTOM("Pasta indicada"),
}

/** Uma skill (`SKILL.md`) achada no disco: [file] é o `SKILL.md`; [name] e [description] vêm do cabeçalho dele. */
data class Skill(val name: String, val description: String, val file: Path, val scope: SkillScope)

/**
 * As skills extras que o usuário quer ver no Ataque, além das do Spec Kit: onde procurá-las, quais ele marcou e o
 * texto que leva a IA a rodar uma. A escolha é por usuário (nas configurações da IDE), nunca do projeto.
 */
object ExtraSkills {

    /** Onde as ferramentas de IA guardam skills, dentro de um projeto ou da pasta pessoal. */
    val FOLDERS = listOf(".claude/skills", ".cursor/skills", ".agents/skills", ".codex/skills")

    const val SKILL_FILE = "SKILL.md"
    const val CONTENT_MAX = 30_000
    private const val SELECTED = "IronHawk.Skills.Selected"
    private const val FOLDERS_KEY = "IronHawk.Skills.Folders"
    private const val SPEC_KIT_PREFIX = "speckit"
    private val props: PropertiesComponent get() = PropertiesComponent.getInstance()

    fun interface Listener {
        fun changed()

        companion object {
            @Topic.AppLevel
            val TOPIC = Topic(Listener::class.java, Topic.BroadcastDirection.NONE)
        }
    }

    /** Os nomes das skills marcadas para o Ataque. */
    var selected: Set<String>
        get() = props.getValue(SELECTED).orEmpty().lines().map(String::trim).filter(String::isNotEmpty).toSet()
        set(value) = save(SELECTED, value.sorted())

    /** As pastas que o usuário apontou: uma pasta com várias skills dentro, ou a de uma skill só. */
    var customFolders: List<String>
        get() = props.getValue(FOLDERS_KEY).orEmpty().lines().map(String::trim).filter(String::isNotEmpty)
        set(value) = save(FOLDERS_KEY, value.map(String::trim).filter(String::isNotEmpty).distinct())

    /** Avisa o Arsenal e o Lançador de que a escolha ou as pastas mudaram. */
    fun notifyChanged() = ApplicationManager.getApplication().messageBus.syncPublisher(Listener.TOPIC).changed()

    private fun save(key: String, values: List<String>) {
        if (values.isEmpty()) props.unsetValue(key) else props.setValue(key, values.joinToString("\n"))
    }

    /**
     * As skills achadas no projeto, na pasta pessoal e em [custom], sem as do próprio Spec Kit. Com o mesmo nome em
     * mais de um lugar, vale a do projeto, depois a da pasta indicada e por último a global. Ordenadas por nome.
     */
    fun discover(root: Path?, home: Path?, custom: List<String> = customFolders): List<Skill> {
        val found = buildList {
            if (root != null) FOLDERS.forEach { addAll(inFolder(root.resolve(it), SkillScope.PROJECT)) }
            custom.mapNotNull(::pathOf).forEach { addAll(inFolder(it, SkillScope.CUSTOM)) }
            if (home != null) FOLDERS.forEach { addAll(inFolder(home.resolve(it), SkillScope.GLOBAL)) }
        }
        return found.filterNot { it.name.lowercase().startsWith(SPEC_KIT_PREFIX) }
            .distinctBy { it.name.lowercase() }
            .sortedBy { it.name.lowercase() }
    }

    private fun pathOf(text: String): Path? = try {
        Path.of(text).normalize()
    } catch (_: InvalidPathException) {
        null
    }

    /** As skills de [folder]: as pastas filhas com `SKILL.md` ou, se a própria [folder] tem um, ele. */
    private fun inFolder(folder: Path, scope: SkillScope): List<Skill> {
        if (!Files.isDirectory(folder)) return emptyList()
        val own = folder.resolve(SKILL_FILE)
        if (Files.isRegularFile(own)) return listOfNotNull(read(own, scope))
        val children = runCatching { Files.list(folder).use { it.sorted().toList() } }.getOrDefault(emptyList())
        return children.filter { Files.isDirectory(it) }.mapNotNull { dir -> dir.resolve(SKILL_FILE).takeIf(Files::isRegularFile)?.let { read(it, scope) } }
    }

    /** A skill de [file]; sem `name` no cabeçalho, vale o nome da pasta dela. */
    fun read(file: Path, scope: SkillScope): Skill? {
        val text = runCatching { Files.readString(file) }.getOrNull() ?: return null
        val (name, description) = header(text)
        val fallback = file.parent?.fileName?.toString() ?: return null
        return Skill((name ?: fallback).trim(), description.orEmpty().trim(), file, scope)
    }

    /** `name` e `description` do cabeçalho YAML (entre `---`); a descrição aceita aspas e blocos `>` e `|`. */
    fun header(text: String): Pair<String?, String?> {
        val lines = text.replace("\r\n", "\n").lines()
        if (lines.firstOrNull()?.trim() != "---") return null to null
        val end = lines.drop(1).indexOfFirst { it.trim() == "---" }
        if (end < 0) return null to null
        val block = lines.subList(1, end + 1)
        var name: String? = null
        var description: String? = null
        var index = 0
        while (index < block.size) {
            val line = block[index]
            val key = line.substringBefore(':', "").trim()
            val value = line.substringAfter(':', "").trim()
            index++
            if (key != "name" && key != "description") continue
            val text = if (value == ">" || value == "|" || value == ">-" || value == "|-") {
                val folded = mutableListOf<String>()
                while (index < block.size && (block[index].startsWith(" ") || block[index].isBlank())) folded += block[index++].trim()
                folded.filter(String::isNotEmpty).joinToString(" ")
            } else {
                value.removeSurrounding("\"").removeSurrounding("'")
            }
            if (key == "name") name = text.ifEmpty { null } else description = text.ifEmpty { null }
        }
        return name to description
    }

    /**
     * O texto que a IA lê para rodar [skill], como se o usuário tivesse digitado `/nome`: a feature ativa, a
     * instrução dele e o `SKILL.md` inteiro, porque no devcontainer a IA só enxerga o projeto e não acharia uma skill
     * global. [content] é o texto do `SKILL.md`.
     */
    fun instructions(skill: Skill, content: String, feature: String?, userText: String): String = buildString {
        appendLine("# Skill extra: ${skill.name}")
        appendLine()
        appendLine("Execute a skill **${skill.name}** como se o usuário tivesse digitado `/${skill.name}` no chat, seguindo as")
        appendLine("instruções dela, na íntegra, abaixo. Termine com um resumo curto do que foi gerado ou alterado, em português do Brasil.")
        appendLine()
        appendLine("## Contexto")
        appendLine()
        appendLine("- **Feature ativa:** ${feature?.let { "`$it` (a pasta com spec, plano e tarefas)" } ?: "nenhuma"}")
        appendLine("- **Instrução do usuário:** ${userText.trim().ifEmpty { "nenhuma" }}")
        appendLine()
        appendLine("## Instruções da skill (de `${skill.file.parent?.fileName ?: skill.name}/$SKILL_FILE`, ${skill.scope.label.lowercase()})")
        appendLine()
        val trimmed = content.take(CONTENT_MAX)
        appendLine(trimmed)
        if (content.length > CONTENT_MAX) appendLine("\n… (a skill é maior e foi cortada aqui: leia `${skill.file}` se precisar do resto)")
    }
}
