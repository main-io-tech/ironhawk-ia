package com.mio.irondome.caixapreta

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

private const val DEFENSE = "com.mio.irondome"

/** Parte do plugin onde o bug apareceu, com o pacote do código, para quem for corrigir saber por onde começar. */
enum class Mechanism(val title: String, val qualifiedPackage: String, val contents: String, private val tabs: Set<String> = setOf(title)) {
    RADAR("Radar", "$DEFENSE.radar", "comentários, formulário, margem do editor, cartões e a aba Radar"),
    COMANDO("Centro de Comando", "$DEFENSE.comando", "geração e leitura do .md, histórico consolidado e as ações de salvar"),
    SENTINELA("Sentinela", "$DEFENSE.sentinela", "aba Sentinela e o diff dos arquivos não commitados"),
    ESCUDO("Escudo", "$DEFENSE.escudo", "execução dos testes JUnit e a aba Escudo"),
    PATRULHA("Patrulha", "$DEFENSE.patrulha", "pull requests do GitHub, envio dos pedidos de alteração e a verificação deles"),
    DEFESA("Inteligência da IA", "$DEFENSE.defesa", "dossiê das classes, histórico do git, aprendizado e conflitos entre comentários"),
    INTERCEPTOR("Arsenal", "$DEFENSE.interceptor", "integração com a IA pelo CLI (Cursor, Claude Code, Codex ou Antigravity): configuração, execução e registro"),
    BASE("Base", "$DEFENSE.base", "tool window, abas extras, barra flutuante do editor e componentes visuais compartilhados"),
    CAIXA_PRETA("Caixa-Preta", "$DEFENSE.caixapreta", "o próprio Reportar Bug: formulário, capturas e envio como issue no GitHub"),
    ATAQUE(
        "Ataque (Tomahawk)", "com.mio.tomahawk", "Lançador, Avaliação, Telemetria, Rádio e Extração: as missões do Spec Kit e os PRs",
        setOf("Lançador", "Avaliação", "Telemetria", "Rádio"),
    ),
    IRONHAWK("Painel do IronHawk", "com.mio.ironhawk", "o painel único, a aba Arsenal, as configurações e a migração dos plugins antigos");

    val sourceDir get() = "src/main/kotlin/${qualifiedPackage.replace('.', '/')}/"

    companion object {
        /** Os pacotes do plugin: a Defesa, o Ataque e o que é comum aos dois. */
        val PACKAGES = listOf(DEFENSE, "com.mio.tomahawk", "com.mio.ironhawk")

        /** [text] cita código do plugin: o logger, a mensagem ou um frame da pilha. */
        fun isPluginCode(text: String): Boolean = PACKAGES.any { it in text }

        fun ofTab(title: String?): Mechanism? = entries.firstOrNull { title in it.tabs }

        /** Pelo pacote de um frame da pilha, como `com.mio.irondome.radar.CommentForm.submit(CommentForm.kt:120)`. */
        fun ofFrame(frame: String): Mechanism? = entries.firstOrNull { frame.startsWith("${it.qualifiedPackage}.") }
    }
}

/**
 * Erro ou aviso do log da IDE que passou pelo código do plugin. [stack] traz as linhas depois da mensagem;
 * [category] é o logger que registrou, como `#com.mio.irondome.base.DefenseTabs`.
 */
data class PluginError(val time: String, val level: String, val message: String, val stack: List<String>, val category: String = "") {

    /** Primeiro frame do plugin na pilha: o ponto mais provável do bug. */
    val origin: String?
        get() = stack.firstNotNullOfOrNull { line ->
            line.trim().removePrefix("at ").takeIf { frame -> Mechanism.PACKAGES.any { frame.startsWith("$it.") } }
        }

    /** Pela origem na pilha ou, sem ela, pelo logger que registrou. */
    val mechanism: Mechanism?
        get() = origin?.let(Mechanism::ofFrame) ?: Mechanism.ofFrame(category.removePrefix("#"))

    /** A exceção que abre a pilha, como `java.lang.IllegalStateException: comentário sem arquivo`. */
    val exception: String? get() = stack.firstOrNull { !it.trim().startsWith("at ") }?.trim()
}

/** Ocorrências do mesmo erro: [latest] é a mais recente; [firstTime], quando ele apareceu pela primeira vez. */
data class ErrorGroup(val latest: PluginError, val count: Int, val firstTime: String)

/** [errors] vem do mais recente para o mais antigo; [context] são pares de nome e valor do ambiente. */
data class BugReport(
    val title: String,
    val mechanism: Mechanism,
    val description: String,
    val steps: String,
    val expected: String,
    val errors: List<PluginError>,
    val context: List<Pair<String, String>>,
    val screenshot: Boolean,
    val recording: Boolean,
    val createdAt: LocalDateTime = LocalDateTime.now(),
    /** A IA organizou o texto em partes; sem ela, [description] guarda o texto como foi escrito. */
    val structured: Boolean = true,
)

object BugReportMarkdown {
    const val FILE_NAME = "bug.md"
    const val SCREENSHOT = "print.png"
    const val RECORDING = "gravacao.gif"

    /** Frames de fora do plugin mantidos no início de cada exceção; o resto vira uma linha de resumo. */
    private const val LEADING_FRAMES = 4
    private val DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss")

    private const val UNSTRUCTURED_NOTE = "_Texto como o autor escreveu: não foi organizado em partes._"

    /**
     * [inline] mostra as capturas pelo nome do arquivo, ao lado do `bug.md`; fora dele, no corpo de uma issue, o GitHub não
     * recebe imagens pela API, então o texto avisa que o autor as anexa à issue.
     */
    fun render(report: BugReport, inline: Boolean = true): String = buildString {
        val mechanism = report.mechanism
        val origin = report.errors.firstNotNullOfOrNull { it.origin }

        appendLine("# Bug: ${report.title.oneLine()}")
        appendLine()
        appendLine("Relatado em ${report.createdAt.format(DATE)} pelo Reportar Bug do IronHawk IA.")
        appendLine()
        appendLine("## Onde no plugin")
        appendLine()
        appendLine("- **Mecanismo:** ${mechanism.title}, pacote `${mechanism.qualifiedPackage}` (${mechanism.contents}).")
        appendLine("- **Código:** `${mechanism.sourceDir}`")
        if (origin != null) {
            val other = Mechanism.ofFrame(origin)?.takeIf { it != mechanism }
            append("- **Origem provável:** `$origin`, o primeiro ponto do plugin na pilha do erro mais recente")
            appendLine(if (other != null) ", que fica no mecanismo ${other.title} (`${other.sourceDir}`)." else ".")
        } else {
            appendLine("- **Origem provável:** nenhum erro do plugin no log da IDE; comece pelo pacote do mecanismo.")
        }
        appendLine()
        appendLine("> **Para quem for corrigir, pessoa ou IA:** reproduza com os passos abaixo, comece pela origem provável e")
        appendLine("> pelo pacote indicado e confira os erros do log no fim do relatório.")

        if (report.structured) {
            section("O que aconteceu", report.description)
            section("Passos para reproduzir", report.steps)
            section("O que era esperado", report.expected)
        } else {
            section("O que aconteceu", report.description)
            appendLine()
            appendLine(UNSTRUCTURED_NOTE)
        }

        if (report.screenshot || report.recording) {
            appendLine()
            appendLine("## Capturas")
            if (inline) {
                if (report.screenshot) {
                    appendLine()
                    appendLine("![Print da janela da IDE]($SCREENSHOT)")
                }
                if (report.recording) {
                    appendLine()
                    appendLine("![Gravação da janela da IDE]($RECORDING)")
                }
            } else {
                appendLine()
                val what = listOfNotNull("o print".takeIf { report.screenshot }, "a gravação".takeIf { report.recording }).joinToString(" e ")
                appendLine("O autor do relatório vai anexar $what nos comentários desta issue.")
            }
        }

        appendLine()
        appendLine("## Erros do plugin no log da IDE")
        appendLine()
        if (report.errors.isEmpty()) {
            appendLine("Nenhum erro ou aviso do plugin no `idea.log`.")
        } else {
            report.errors.forEachIndexed { index, error ->
                if (index > 0) appendLine()
                appendLine("### ${error.time} · ${error.level}: ${error.message.oneLine().take(140)}")
                error.origin?.let {
                    appendLine()
                    appendLine("Origem no plugin: `$it`")
                }
                if (error.stack.isNotEmpty()) {
                    appendLine()
                    appendLine("```text")
                    compactStack(error.stack).forEach(::appendLine)
                    appendLine("```")
                }
            }
        }

        appendLine()
        appendLine("## Ambiente")
        appendLine()
        appendLine("| Item | Valor |")
        appendLine("| --- | --- |")
        report.context.forEach { (name, value) -> appendLine("| $name | ${value.oneLine().replace("|", "\\|")} |") }
    }

    /**
     * Mantém as mensagens, os frames do plugin e os primeiros de cada exceção, que mostram onde ela
     * nasceu; os demais frames da plataforma viram uma linha com quantos foram omitidos.
     */
    internal fun compactStack(stack: List<String>): List<String> {
        val result = mutableListOf<String>()
        var framesInBlock = 0
        var skipped = 0
        fun flushSkipped() {
            if (skipped > 0) result += "\t... ${if (skipped == 1) "1 linha omitida" else "$skipped linhas omitidas"}"
            skipped = 0
        }
        for (line in stack) {
            val trimmed = line.trim()
            if (!trimmed.startsWith("at ")) {
                if (trimmed.startsWith("...") && trimmed.endsWith("more")) {
                    skipped++
                    continue
                }
                flushSkipped()
                result += line
                framesInBlock = 0
                continue
            }
            framesInBlock++
            if (framesInBlock <= LEADING_FRAMES || Mechanism.isPluginCode(trimmed)) {
                flushSkipped()
                result += line
            } else {
                skipped++
            }
        }
        flushSkipped()
        return result
    }

    private fun StringBuilder.section(title: String, text: String) {
        appendLine()
        appendLine("## $title")
        appendLine()
        appendLine(text.trim().ifEmpty { "_Não informado._" })
    }

    private fun String.oneLine() = lines().joinToString(" ") { it.trim() }.trim()
}
