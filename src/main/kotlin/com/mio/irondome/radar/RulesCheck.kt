package com.mio.irondome.radar

import com.google.gson.JsonObject
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.util.ExecUtil
import com.intellij.openapi.components.Service
import com.mio.irondome.interceptor.AgentEvents
import java.nio.charset.StandardCharsets
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

/** Uma regra de IA que o código alterado viola: [line] é 1-based; [rule] é a regra em poucas palavras. */
data class RuleViolation(
    val rule: String,
    val ruleFile: String,
    val path: String,
    val line: Int,
    val snippet: String,
    val why: String,
)

/**
 * O resultado de uma verificação: as violações achadas, as regras que a IA não conseguiu avaliar no código
 * ([unverifiable]) e o que foi verificado.
 */
class RulesCheckResult(
    val violations: List<RuleViolation>,
    val unverifiable: List<String>,
    val ruleFiles: List<String>,
    val changedFiles: Int,
)

/** A última verificação, só na memória: cada execução substitui a anterior. */
@Service(Service.Level.PROJECT)
class RulesCheckState {
    @Volatile
    var last: RulesCheckResult? = null
}

/**
 * Verificar se as alterações do projeto cumprem as regras de IA dele (`CLAUDE.md`, `AGENTS.md` e afins). A IA só
 * lê: o plugin acha as regras e as alterações, pede o JSON e lê a resposta.
 */
object RulesCheck {

    const val MAX_VIOLATIONS = 30
    const val MAX_FILES = 150
    const val INSTRUCTIONS_FILE = "verificacao-regras.md"

    private const val TIMEOUT_MS = 30_000
    private const val DEPTH = 5

    /** Pastas que não têm regras do projeto e podem ser enormes. */
    private val SKIPPED = setOf(
        ".git", "node_modules", "build", "target", "out", "dist", ".gradle", ".idea", ".venv", "venv", "__pycache__", "ironhawk",
    )
    private val SINGLE_FILES = setOf("claude.md", "agents.md", ".cursorrules")
    private const val COPILOT = ".github/copilot-instructions.md"
    private val RULE_FOLDERS = listOf(".claude/rules/", ".cursor/rules/")
    private val FOLDER_EXTENSIONS = setOf("md", "mdc", "markdown")

    /** Os arquivos de regras de IA em [root] e nas subpastas, relativos a ele, com `/`. */
    fun detectRuleFiles(root: Path): List<String> {
        val found = sortedSetOf<String>()
        Files.walkFileTree(
            root,
            emptySet(),
            DEPTH,
            object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                    val name = dir.fileName?.toString().orEmpty()
                    return if (dir != root && name in SKIPPED) FileVisitResult.SKIP_SUBTREE else FileVisitResult.CONTINUE
                }

                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    val relative = root.relativize(file).toString().replace('\\', '/')
                    if (isRuleFile(relative)) found += relative
                    return FileVisitResult.CONTINUE
                }

                override fun visitFileFailed(file: Path, exc: java.io.IOException) = FileVisitResult.CONTINUE
            },
        )
        return found.toList()
    }

    /** Se [relative] é um arquivo de regras de IA. */
    fun isRuleFile(relative: String): Boolean {
        val lower = relative.lowercase()
        val name = lower.substringAfterLast('/')
        if (name in SINGLE_FILES) return true
        if (lower == COPILOT || lower.endsWith("/$COPILOT")) return true
        val extension = name.substringAfterLast('.', "")
        return extension in FOLDER_EXTENSIONS && RULE_FOLDERS.any { folder -> lower.startsWith(folder) || "/$folder" in lower }
    }

    /**
     * Os arquivos que o trabalho atual mexeu: os não commitados e os da branch em relação à base. Sem os de
     * `ironhawk/` e `.idea/`, sem os removidos e sem os próprios arquivos de regras. Fora da EDT.
     */
    fun changedFiles(root: Path): List<String> {
        val uncommitted = git(root, "status", "--porcelain=v1", "-z", "-uall")?.let(::parseStatus).orEmpty()
        val branch = baseRef(root)
            ?.let { base -> git(root, "merge-base", "HEAD", base)?.trim()?.takeIf { it.isNotEmpty() } }
            ?.let { mergeBase -> git(root, "diff", "--name-only", "--no-renames", "-z", mergeBase, "HEAD") }
            ?.let(::parseNames)
            .orEmpty()
        return (uncommitted + branch).distinct().filter { keeps(root, it) }.sorted()
    }

    private fun keeps(root: Path, path: String): Boolean =
        !path.startsWith("ironhawk/") && !path.startsWith(".idea/") && !isRuleFile(path) && Files.isRegularFile(root.resolve(path))

    /** A saída de `git status --porcelain=v1 -z`: `XY caminho`, e o nome antigo logo depois nos renomeados. */
    fun parseStatus(output: String): List<String> {
        val entries = output.split('\u0000').filter { it.isNotEmpty() }
        val paths = mutableListOf<String>()
        var index = 0
        while (index < entries.size) {
            val entry = entries[index]
            index++
            if (entry.length < 4) continue
            val state = entry.substring(0, 2)
            paths += entry.substring(3).replace('\\', '/')
            if ('R' in state || 'C' in state) index++
        }
        return paths
    }

    /** A saída de `git diff --name-only -z`. */
    fun parseNames(output: String): List<String> = output.split('\u0000').map { it.trim().replace('\\', '/') }.filter { it.isNotEmpty() }

    /** A branch de onde a atual saiu: a padrão do remoto ou, na falta, a primeira das mais comuns que existir. */
    private fun baseRef(root: Path): String? {
        val remoteDefault = git(root, "symbolic-ref", "--short", "refs/remotes/origin/HEAD")?.trim()?.takeIf { it.isNotEmpty() }
        val candidates = listOfNotNull(remoteDefault) + listOf("origin/main", "origin/master", "main", "master", "develop")
        return candidates.firstOrNull { git(root, "rev-parse", "--verify", "--quiet", it) != null }
    }

    private fun git(root: Path, vararg args: String): String? = runCatching {
        val line = GeneralCommandLine("git", *args).withWorkDirectory(root.toString()).withCharset(StandardCharsets.UTF_8)
        ExecUtil.execAndGetOutput(line, TIMEOUT_MS).takeIf { it.exitCode == 0 && !it.isTimeout }?.stdout
    }.getOrNull()

    /** O texto que a IA lê: as regras a seguir, os arquivos a conferir e o JSON que ela deve devolver. */
    fun instructions(ruleFiles: List<String>, changed: List<String>): String = buildString {
        appendLine("# Verificação das regras de IA do projeto")
        appendLine()
        appendLine("Confira se o código **alterado** cumpre as regras de IA do projeto. Você só lê: **não altere nenhum arquivo**.")
        appendLine()
        appendLine("## Regras")
        appendLine()
        appendLine("Leia estes arquivos de regras, inteiros:")
        appendLine()
        ruleFiles.forEach { appendLine("- `$it`") }
        appendLine()
        appendLine("## Arquivos a conferir")
        appendLine()
        appendLine("Só estes, alterados e ainda não revisados (leia cada um no projeto e use `git diff` para ver o que mudou):")
        appendLine()
        changed.take(MAX_FILES).forEach { appendLine("- `$it`") }
        if (changed.size > MAX_FILES) appendLine("- … e mais ${changed.size - MAX_FILES} arquivos, que ficaram de fora.")
        appendLine()
        appendLine("## Como avaliar")
        appendLine()
        appendLine("- Aponte só violações **verificáveis no código**: uma regra que dá para confirmar lendo o código ou a configuração")
        appendLine("  (por exemplo, \"nunca desabilite a verificação de certificados\" ou \"toda classe de teste leva `@DisplayName`\").")
        appendLine("- Não chute: se não tiver certeza de que o trecho viola a regra, não aponte.")
        appendLine("- Regras que o código não permite conferir (estilo de conversa, tom de resposta, processo de trabalho) vão em")
        appendLine("  `unverifiable`, com o texto da regra. Não as avalie.")
        appendLine("- Prefira trechos que o trabalho atual alterou, não código antigo que já estava assim.")
        appendLine("- No máximo $MAX_VIOLATIONS violações, das mais graves para as menos.")
        appendLine("- `line` é 1-based e se refere ao arquivo como está agora no projeto.")
        appendLine()
        appendLine("Responda **só** com um bloco ```json, assim:")
        appendLine()
        appendLine("```json")
        appendLine(
            """{"violations": [{"rule": "Nunca desabilitar a verificação de certificados", "ruleFile": "CLAUDE.md", """ +
                """"path": "src/main/Client.kt", "line": 12, "snippet": "trustAll = true", """ +
                """"why": "O cliente aceita qualquer certificado, o que a regra proíbe."}], "unverifiable": ["Responder em português"]}""",
        )
        appendLine("```")
        appendLine()
        appendLine("Se não houver violações, responda com `\"violations\": []`. Mantenha `unverifiable` mesmo assim.")
    }

    /** O resultado da resposta da IA, ou nulo se ela não trouxe o JSON esperado. */
    fun parse(answer: String, ruleFiles: List<String>, changedFiles: Int): RulesCheckResult? {
        val json = AgentEvents.extractJson(answer) ?: return null
        val violationsJson = if (json.isJsonArray) json.asJsonArray else json.asJsonObject.getAsJsonArray("violations") ?: return null
        val violations = violationsJson.mapNotNull { element ->
            val item = element as? JsonObject ?: return@mapNotNull null
            val rule = item.text("rule")?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val path = item.text("path")?.trim()?.removePrefix("./")?.replace('\\', '/')?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            RuleViolation(
                rule = rule,
                ruleFile = item.text("ruleFile")?.trim().orEmpty(),
                path = path,
                line = (item.int("line") ?: item.int("startLine") ?: 1).coerceAtLeast(1),
                snippet = item.text("snippet")?.trim().orEmpty(),
                why = item.text("why")?.trim().orEmpty(),
            )
        }.take(MAX_VIOLATIONS)
        val unverifiable = (json as? JsonObject)?.getAsJsonArray("unverifiable")
            ?.mapNotNull { it?.takeIf { e -> e.isJsonPrimitive }?.asString?.trim()?.takeIf { text -> text.isNotEmpty() } }
            .orEmpty()
        return RulesCheckResult(violations, unverifiable, ruleFiles, changedFiles)
    }

    /** O texto do alvo que vai para o Radar. */
    fun commentText(violation: RuleViolation): String = buildString {
        append("**Regra violada:** ${mention(violation)}")
        if (violation.ruleFile.isNotEmpty()) append(" (`${violation.ruleFile}`)")
        if (violation.why.isNotEmpty()) append("\n\n${violation.why}")
        append("\n\nCorrija o código para cumprir a regra, sem mudar o comportamento que ela não pede para mudar.")
    }

    /** O que identifica o alvo de uma regra num arquivo, para não marcar de novo o que já está no Radar. */
    fun mention(violation: RuleViolation): String = "«${violation.rule}»"

    private fun JsonObject.text(key: String): String? = get(key)?.takeIf { it.isJsonPrimitive }?.asString

    private fun JsonObject.int(key: String): Int? = get(key)?.takeIf { it.isJsonPrimitive }?.let { runCatching { it.asInt }.getOrNull() }
}
