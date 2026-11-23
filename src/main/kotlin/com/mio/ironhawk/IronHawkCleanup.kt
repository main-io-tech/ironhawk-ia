package com.mio.ironhawk

import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile

/**
 * O que o IronHawk grava em `ironhawk/`, por categoria. [defaultSelected]: a categoria só guarda registros que o plugin
 * não volta a ler, e entra marcada na limpeza; as outras guardam dados que algum mecanismo usa e começam desmarcadas.
 */
enum class CleanupCategory(val title: String, val description: String, val defaultSelected: Boolean) {
    DEFENSE_AI("Execuções da IA na Defesa", "registros, pedidos e respostas da IA em iron-dome/ia", true),
    ATTACK_RUNS("Execuções das missões do Ataque", "registros das missões em tomahawk/execucoes", true),
    ATTACK_INPUTS("Pedidos das missões do Ataque", "o que foi enviado à IA em cada missão, em tomahawk/pedidos", true),
    RADAR_HISTORY("Revisões encerradas do Radar", "os .md das revisões encerradas e o histórico consolidado; a revisão aberta fica", false),
    PATROL("Registros dos PRs da Patrulha", "pedidos de alteração enviados e pendentes de cada PR, em iron-dome/pull-requests", false),
    LEARNING("Aprendizado da Inteligência da IA", "o que a IA aprendeu com as suas revisões, em iron-dome/defesa", false),
    CRITERIA("Critérios de aceite do Escudo", "o catálogo em iron-dome/criterios; o Escudo não usa mais", true),
    CONFERENCES("Conferências do Escudo", "o que o Conferido do Escudo gravava em iron-dome/escudo; o Escudo não usa mais", true),
    OTHER("Outros arquivos", "o que sobrou de versões antigas ou não tem categoria", false),
}

class CleanupFile(val path: Path, val relative: String, val size: Long)

class CleanupGroup(val category: CleanupCategory, val files: List<CleanupFile>) {
    val size: Long get() = files.sumOf { it.size }
}

object IronHawkCleanup {

    private val REVIEW = Regex("""ordem-interceptacao-\d+\.md""")
    private const val ARCHIVE = "historico-interceptacoes.md"

    /** Recriada a cada missão; apagá-la no meio de uma deixaria a IA sem as regras de proteção. */
    private const val GUARDRAILS = "tomahawk/protecoes/"

    /** A categoria de um arquivo, pelo caminho relativo a `ironhawk/`, com `/`; nulo se ele nunca deve ser apagado. */
    fun categoryOf(relative: String): CleanupCategory? = when {
        relative.startsWith(GUARDRAILS) -> null
        // É o que mantém a pasta fora do git.
        relative == IronHawkFolder.GITIGNORE -> null
        relative.startsWith("iron-dome/ia/") -> CleanupCategory.DEFENSE_AI
        relative.startsWith("tomahawk/execucoes/") -> CleanupCategory.ATTACK_RUNS
        relative.startsWith("tomahawk/pedidos/") -> CleanupCategory.ATTACK_INPUTS
        relative.startsWith("iron-dome/pull-requests/") -> CleanupCategory.PATROL
        relative.startsWith("iron-dome/defesa/") -> CleanupCategory.LEARNING
        relative.startsWith("iron-dome/criterios/") -> CleanupCategory.CRITERIA
        relative.startsWith("iron-dome/escudo/") -> CleanupCategory.CONFERENCES
        relative.startsWith("iron-dome/") && relative.count { it == '/' } == 1 &&
            (REVIEW.matches(relative.substringAfter('/')) || relative.endsWith("/$ARCHIVE")) -> CleanupCategory.RADAR_HISTORY
        else -> CleanupCategory.OTHER
    }

    /**
     * Os arquivos de `ironhawk/` em [root], por categoria, na ordem de [CleanupCategory] e sem categorias vazias.
     * [keep] são os arquivos em uso (a revisão aberta), que nunca entram.
     */
    fun scan(root: Path, keep: Set<Path> = emptySet()): List<CleanupGroup> {
        val folder = root.resolve(IronHawkFolder.ROOT)
        if (!folder.isDirectory()) return emptyList()
        val kept = keep.map { it.toAbsolutePath().normalize() }.toSet()
        val files = Files.walk(folder).use { paths -> paths.filter { it.isRegularFile() }.toList() }
            .filter { it.toAbsolutePath().normalize() !in kept }
            .mapNotNull { path ->
                val relative = folder.relativize(path).toString().replace('\\', '/')
                val category = categoryOf(relative) ?: return@mapNotNull null
                category to CleanupFile(path, relative, runCatching { Files.size(path) }.getOrDefault(0L))
            }
        return CleanupCategory.entries.mapNotNull { category ->
            files.filter { it.first == category }.map { it.second }.sortedByDescending { it.relative }
                .takeIf { it.isNotEmpty() }?.let { CleanupGroup(category, it) }
        }
    }

    /**
     * As pastas que vão ficar vazias quando [deleted] sair, das mais fundas para as mais rasas, sem passar de
     * `ironhawk/` em [root], que fica. Chame antes de apagar.
     */
    fun emptyFolders(root: Path, deleted: Collection<Path>): List<Path> {
        val folder = root.resolve(IronHawkFolder.ROOT).toAbsolutePath().normalize()
        val gone = deleted.mapTo(mutableSetOf()) { it.toAbsolutePath().normalize() }
        val candidates = gone.flatMap { path ->
            generateSequence(path.parent) { it.parent }.takeWhile { it.startsWith(folder) && it != folder }.toList()
        }.distinct().sortedByDescending { it.nameCount }
        return candidates.filter { dir ->
            val empty = dir.isDirectory() && Files.list(dir).use { entries -> entries.allMatch { it.toAbsolutePath().normalize() in gone } }
            if (empty) gone.add(dir)
            empty
        }
    }

    fun formatSize(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        else -> String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024.0)).replace('.', ',')
    }
}
