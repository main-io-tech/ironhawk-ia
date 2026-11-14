package com.mio.irondome.caixapreta

import com.intellij.openapi.project.Project
import com.mio.irondome.patrulha.GitHubClient
import com.mio.irondome.patrulha.GitHubException
import com.mio.irondome.patrulha.GitHubRepo
import org.jetbrains.plugins.github.authentication.GHAccountsUtil
import org.jetbrains.plugins.github.util.GHCompatibilityUtil
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/** O que deu errado ao criar a issue: [needsAccount] quando falta entrar no GitHub, para o aviso levar às configurações. */
class BugReportException(message: String, val needsAccount: Boolean = false, val noPlugin: Boolean = false) : Exception(message)

/** Uma issue criada no repositório do plugin. */
class BugIssue(val number: Int, val url: String)

/**
 * O destino dos bugs do plugin: as issues do repositório do próprio Iron Dome IA, criadas com a conta do GitHub que
 * o usuário já tem no IntelliJ (a mesma da Patrulha). Quem não tem permissão no repositório, se ele ficar privado,
 * recebe um aviso claro. Faz rede: chame fora da EDT.
 */
object BugGitHub {

    val REPOSITORY = GitHubRepo("github.com", "main-io-tech", "ironhawk-ia")
    const val GITHUB_SETTINGS = "GitHub"
    private const val ISSUE_URL_BODY_MAX = 6_000

    /** A conta do GitHub para o host do repositório, se houver; nula sem os plugins do GitHub (ou sem conta). */
    private fun account(project: Project) = GHAccountsUtil.accounts
        .filter { it.server.host.equals(REPOSITORY.host, ignoreCase = true) }
        .let { accounts -> GHAccountsUtil.getSingleOrDefaultAccount(project)?.takeIf { it in accounts } ?: accounts.firstOrNull() }

    /** Se os plugins do GitHub existem e há uma conta para o repositório. Pode ler o cofre de senhas: fora da EDT. */
    fun hasAccount(project: Project): Boolean = runCatching { account(project) != null }.getOrDefault(false)

    /**
     * Cria a issue [title] com [body]. Sem os plugins do GitHub da IDE a exceção traz [BugReportException.noPlugin], e o
     * chamador abre a página de nova issue no navegador; sem conta, [BugReportException.needsAccount].
     */
    fun createIssue(project: Project, title: String, body: String): BugIssue {
        val client = try {
            val account = account(project) ?: throw BugReportException(
                "Nenhuma conta do GitHub no IntelliJ. Entre em Settings > Version Control > GitHub e tente de novo.",
                needsAccount = true,
            )
            val token = GHCompatibilityUtil.getOrRequestToken(account, project) ?: throw BugReportException(
                "Sem token para a conta ${account.name}. Entre de novo em Settings > Version Control > GitHub.",
                needsAccount = true,
            )
            GitHubClient(account.server.toApiUrl(), account.server.toGraphQLUrl(), token)
        } catch (error: LinkageError) {
            throw BugReportException("Os plugins Git e GitHub da IDE estão ausentes ou desativados.", noPlugin = true)
        }
        try {
            val issue = client.createIssue(REPOSITORY, title, body)
            return BugIssue(issue.number, issue.url)
        } catch (error: GitHubException) {
            throw BugReportException(describe(error))
        }
    }

    /** O erro do GitHub em palavras de quem reporta: 403 e 404 aqui são, na prática, falta de permissão. */
    internal fun describe(error: GitHubException): String = when (error.status) {
        401 -> error.message.orEmpty()
        403, 404 -> "Sem permissão neste repositório (${REPOSITORY.fullName}): só quem tem acesso a ele pode reportar bugs."
        else -> error.message ?: error.javaClass.simpleName
    }

    /** A página de nova issue do repositório, já com o título e o texto, para quem não tem os plugins do GitHub. */
    fun newIssueUrl(title: String, body: String): String {
        val text = if (body.length > ISSUE_URL_BODY_MAX) body.take(ISSUE_URL_BODY_MAX) + "\n\n_(texto cortado para caber no endereço)_" else body
        fun encode(value: String) = URLEncoder.encode(value, StandardCharsets.UTF_8)
        return "https://${REPOSITORY.host}/${REPOSITORY.fullName}/issues/new?title=${encode(title)}&body=${encode(text)}"
    }
}
