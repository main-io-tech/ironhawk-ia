package com.mio.irondome.escudo

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.VfsUtilCore
import com.mio.ironhawk.IronHawkFolder
import com.mio.irondome.base.IronDomeRoot
import com.mio.irondome.base.rootPath
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.isDirectory
import kotlin.io.path.name

/** Onde o catálogo de critérios mora e como ler os arquivos dele e as tags do código. */
object CriteriaFiles {

    const val DIR = "criterios"
    private val EXTENSIONS = setOf("yaml", "yml")
    private val SOURCE_EXTENSIONS = setOf("java", "kt", "groovy", "scala", "feature")
    private const val MAX_SOURCE_BYTES = 1_000_000L

    /** `ironhawk/iron-dome/criterios/` dentro de [root]. */
    fun folder(root: Path): Path = root.resolve(IronHawkFolder.DEFENSE).resolve(DIR)

    /** A pasta do projeto e as de cada projeto da janela que o Iron Dome vigia. */
    fun roots(project: Project): List<Path> =
        (listOfNotNull(project.rootPath()) + IronDomeRoot.getInstance(project).candidates().map { it.path })
            .map { Path.of(it).normalize() }.distinct()

    /** Junta os `.yaml` da pasta de cada raiz. Um arquivo ilegível vira erro de contrato, não falha. */
    fun load(roots: List<Path>): CriteriaCatalog = CriteriaCatalog.merge(
        roots.map(::folder).filter { it.isDirectory() }.flatMap { dir ->
            val files = runCatching { Files.list(dir).use { stream -> stream.filter { it.extension.lowercase() in EXTENSIONS }.sorted().toList() } }
                .getOrDefault(emptyList())
            files.map { file ->
                runCatching { CriteriaCatalogParser.parse(file.name, Files.readString(file)) }
                    .getOrElse { CriteriaCatalog(emptyList(), listOf(CatalogError(file.name, 1, "não consegui ler o arquivo: ${it.message}"))) }
            }
        },
    )

    /** Os IDs que as tags dos testes do projeto citam, rodando ou não. Roda em segundo plano, com read action. */
    fun sourceReferences(project: Project): Set<String> = ReadAction.compute<Set<String>, RuntimeException> {
        val found = mutableSetOf<String>()
        val index = ProjectFileIndex.getInstance(project)
        index.iterateContent { file ->
            if (!file.isDirectory && file.extension?.lowercase() in SOURCE_EXTENSIONS && file.length <= MAX_SOURCE_BYTES && index.isInTestSourceContent(file)) {
                runCatching { found += CriterionTags.idsIn(VfsUtilCore.loadText(file)) }
            }
            true
        }
        found
    }
}
