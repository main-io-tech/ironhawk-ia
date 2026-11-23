package com.mio.irondome.escudo

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.psi.PsiCompiledElement
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiMethodCallExpression
import com.intellij.psi.util.PsiTreeUtil

/**
 * Lê do código a prova de cada teste executado: o método, os métodos da classe que ele chama e os de outras classes de
 * teste que verificam por ele (o ator de um teste de aceitação).
 */
object EvidenceReader {

    private const val MAX_METHOD_CHARS = 20_000
    /** Até onde as chamadas são seguidas e quantos métodos de fora entram na prova de um teste. */
    private const val MAX_DEPTH = 4
    private const val MAX_EXTERNAL = 60

    private class Source(
        val id: String,
        val name: String,
        val declaration: String,
        val helpers: Map<String, String>,
        /** Os métodos de outras classes que o teste alcança; nulo quando as chamadas não foram seguidas (Kotlin). */
        val external: Map<String, String>?,
    )

    /** A prova dos testes de [urls] (as URLs do runner), pelo identificador. Fora da EDT: espera a indexação. */
    fun read(project: Project, urls: Collection<String>): Map<String, TestEvidence> {
        val wanted = urls.distinctBy(TestSource::idOf)
        val sources = ReadAction.nonBlocking<List<Source>> { wanted.mapNotNull { source(project, it) } }
            .inSmartMode(project)
            .executeSynchronously()
        return sources.associate { source ->
            source.id to TestSource.evidence(source.id, source.name, source.declaration, source.helpers, source.external)
        }
    }

    private fun source(project: Project, url: String): Source? {
        ProgressManager.checkCanceled()
        val method = JUnitDisplayNames.resolve(project, url) as? PsiMethod ?: return null
        val declaration = method.navigationElement.text?.takeIf { it.length <= MAX_METHOD_CHARS } ?: return null
        val helpers = method.containingClass?.methods.orEmpty()
            .filter { it != method }
            .mapNotNull { helper -> helper.navigationElement.text?.takeIf { it.length <= MAX_METHOD_CHARS }?.let { helper.name to it } }
            .toMap()
        return Source(
            id = TestSource.idOf(url),
            name = JUnitDisplayNames.displayNameOf(method) ?: method.name,
            declaration = declaration,
            helpers = helpers,
            external = if (method.navigationElement.containingFile is PsiJavaFile) externalHelpers(project, method) else null,
        )
    }

    /**
     * Os métodos de outras classes que [method] alcança, pelo nome: o ator de um teste de aceitação (`ana.conferir…`),
     * a classe base, um método de apoio. Só os que estão em fontes de teste do projeto: uma asserção de biblioteca é
     * lida pelo nome, e o código de produção não é prova.
     */
    private fun externalHelpers(project: Project, method: PsiMethod): Map<String, String> {
        val index = ProjectFileIndex.getInstance(project)
        val testRoot = method.navigationElement.containingFile?.virtualFile?.let(index::getSourceRootForFile)
        val own = method.containingClass
        val found = LinkedHashMap<String, String>()
        val seen = HashSet<PsiMethod>()
        val queue = ArrayDeque<Pair<PsiElement, Int>>()
        queue += method.navigationElement to 0
        while (queue.isNotEmpty() && found.size < MAX_EXTERNAL) {
            ProgressManager.checkCanceled()
            val (element, depth) = queue.removeFirst()
            for (call in PsiTreeUtil.findChildrenOfType(element, PsiMethodCallExpression::class.java)) {
                val target = call.resolveMethod() ?: continue
                if (target is PsiCompiledElement || !seen.add(target)) continue
                val source = target.navigationElement
                if (source is PsiCompiledElement) continue
                val file = source.containingFile?.virtualFile ?: continue
                if (!index.isInTestSourceContent(file) && (testRoot == null || index.getSourceRootForFile(file) != testRoot)) continue
                val text = source.text?.takeIf { it.length <= MAX_METHOD_CHARS } ?: continue
                if (target.containingClass != own) found.putIfAbsent(target.name, text)
                if (depth + 1 < MAX_DEPTH) queue += source to depth + 1
            }
        }
        return found
    }
}
