package com.mio.ironhawk

import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.mio.irondome.base.IronDomeRoot
import com.mio.irondome.base.rootPath
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isDirectory
import kotlin.io.path.name

/**
 * A pasta que o IronHawk grava no projeto: `ironhawk/`, com `iron-dome` para a Defesa e `tomahawk` para o Ataque.
 * Versões antigas gravavam as duas na raiz; [migrate] as leva para dentro dela.
 */
object IronHawkFolder {
    const val ROOT = "ironhawk"
    const val DEFENSE = "$ROOT/iron-dome"
    const val ATTACK = "$ROOT/tomahawk"

    /** A pasta antiga na raiz, para onde ela vai e o que mostra que ela é do plugin, e não do projeto. */
    private class Legacy(val name: String, val target: String, val marks: (String) -> Boolean)

    private val LEGACY = listOf(
        Legacy("iron-dome", DEFENSE) { it in setOf("ia", "pull-requests", "defesa", "bugs", "historico-interceptacoes.md") || it.startsWith("ordem-interceptacao") },
        Legacy("tomahawk", ATTACK) { it in setOf("execucoes", "pedidos", "testes", "protecoes") },
    )

    /**
     * Move as pastas `iron-dome` e `tomahawk` da raiz de [root] para `ironhawk/`, quando têm o que o plugin grava. Se o
     * destino já existe, junta os dois sem sobrescrever: o que já está lá fica, e o que colidiria fica na pasta
     * antiga. Devolve as pastas que moveu.
     */
    fun migrate(root: Path): List<String> = LEGACY.mapNotNull { legacy ->
        val from = root.resolve(legacy.name)
        if (!from.isDirectory() || !ours(from, legacy)) return@mapNotNull null
        val to = root.resolve(legacy.target)
        Files.createDirectories(to.parent)
        merge(from, to)
        legacy.name
    }

    const val GITIGNORE = ".gitignore"

    /** [keepLocal] na pasta do projeto que o plugin vigia. */
    fun keepLocal(project: Project) {
        project.rootPath()?.let { keepLocal(Path.of(it)) }
    }

    /** Ignora tudo o que está na pasta, inclusive este arquivo. */
    internal val GITIGNORE_TEXT = """
        # O que o IronHawk IA grava aqui é de quem está trabalhando no projeto nesta máquina: revisões, registros da IA,
        # conferências. Nada disso vai para o repositório. Para compartilhar algo desta pasta, edite este arquivo.
        *
    """.trimIndent() + "\n"

    /**
     * Tira `ironhawk/` do git com um `.gitignore` dentro da própria pasta, sem mexer no `.gitignore` do projeto, que é
     * da equipe. Um `.gitignore` que já esteja lá, mesmo vazio, é de quem o editou e fica como está. Com [create],
     * cria a pasta se ela ainda não existe; sem ele, só age numa pasta que já existe.
     */
    fun keepLocal(root: Path, create: Boolean = true) {
        val folder = root.resolve(ROOT)
        val ignore = folder.resolve(GITIGNORE)
        runCatching {
            if (!folder.isDirectory()) {
                if (!create) return
                Files.createDirectories(folder)
            }
            if (!Files.exists(ignore)) Files.writeString(ignore, GITIGNORE_TEXT)
        }
    }

    /**
     * Deixa em [dir] só os [keep] arquivos mais novos (pela data de alteração) cujo nome termina com [suffix]; as
     * subpastas ficam. Devolve quantos apagou. São os registros que o próprio plugin grava, e o usuário escolhe quantos
     * guardar.
     */
    fun prune(dir: Path, keep: Int, suffix: String = ".md"): Int {
        if (!dir.isDirectory()) return 0
        val files = runCatching {
            Files.list(dir).use { entries -> entries.filter { Files.isRegularFile(it) && it.name.endsWith(suffix) }.toList() }
        }.getOrDefault(emptyList())
        val old = files.sortedWith(
            compareByDescending<Path> { runCatching { Files.getLastModifiedTime(it).toMillis() }.getOrDefault(0L) }.thenByDescending { it.name },
        ).drop(keep.coerceAtLeast(0))
        return old.count { runCatching { Files.deleteIfExists(it) }.getOrDefault(false) }
    }

    private fun ours(dir: Path, legacy: Legacy): Boolean =
        runCatching { Files.list(dir).use { entries -> entries.anyMatch { legacy.marks(it.name.lowercase()) } } }.getOrDefault(false)

    private fun merge(from: Path, to: Path) {
        if (!Files.exists(to)) {
            Files.move(from, to)
            return
        }
        if (!from.isDirectory() || !to.isDirectory()) return
        Files.list(from).use { it.toList() }.forEach { merge(it, to.resolve(it.name)) }
        if (Files.list(from).use { !it.findAny().isPresent }) Files.delete(from)
    }
}

/** Ao abrir o projeto: as pastas antigas do projeto e de cada projeto da janela que o Iron Dome vigia vão para `ironhawk/`. */
class IronHawkFolderStartup : ProjectActivity {
    override suspend fun execute(project: Project) {
        val roots = (listOfNotNull(project.basePath) + IronDomeRoot.getInstance(project).candidates().map { it.path })
            .map { Path.of(it).normalize() }.distinct()
        roots.forEach { root ->
            val moved = runCatching { IronHawkFolder.migrate(root) }
                .onFailure { LOG.warn("Não deu para mover as pastas antigas de $root para ${IronHawkFolder.ROOT}/", it) }
                .getOrDefault(emptyList())
            // Projetos que já usavam o plugin ganham o .gitignore da pasta na primeira abertura.
            IronHawkFolder.keepLocal(root, create = false)
            if (moved.isEmpty()) return@forEach
            LOG.info("Pastas ${moved.joinToString()} de $root movidas para ${IronHawkFolder.ROOT}/")
            LocalFileSystem.getInstance().refreshAndFindFileByNioFile(root)?.let { VfsUtil.markDirtyAndRefresh(true, true, true, it) }
        }
    }

    private companion object {
        val LOG = logger<IronHawkFolderStartup>()
    }
}
