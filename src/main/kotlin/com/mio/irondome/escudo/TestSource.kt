package com.mio.irondome.escudo

/**
 * Uma linha da prova de um teste, como a ficha mostra. Numa asserção direta, [text] é a frase em português ou, quando
 * [code], o código como está. Num passo ([step]), que é a chamada a um método que verifica (o `ana.conferirRecusa(...)`
 * de um teste de aceitação), [text] é o passo em palavras e [inner] são as asserções de dentro dele.
 */
class ProofLine(
    val text: String,
    val code: Boolean = false,
    val inner: List<ProofLine> = emptyList(),
    val step: Boolean = false,
    /**
     * Um passo que não promete conferir (a preparação e a ação, como `ana.criaRoteiro()`), mas tem asserção dentro:
     * a ficha o guarda à parte, para a lista ficar com o que o teste se propõe a verificar.
     */
    val support: Boolean = false,
)

/** A prova que um teste dá, lida do código: as verificações que ele faz ([checks]). */
class TestEvidence(
    val id: String,
    val checks: List<String>,
    /** O `@DisplayName` do método, ou o nome dele: o das execuções de um parametrizado é o do método. */
    val name: String = "",
    /** Cada verificação de [checks] como a ficha mostra, na mesma ordem. */
    val proof: List<ProofLine> = emptyList(),
)

/**
 * Lê o texto de um método de teste, de Java ou de Kotlin, sem depender do PSI: separa as instruções e acha as que
 * verificam alguma coisa.
 */
object TestSource {

    private const val MAX_CHECK = 180
    private const val MAX_DEPTH = 4
    private const val NAME = """[\p{L}\p{N}_]"""
    private const val BLOCK = " { … }"

    /** Chamadas que verificam: asserções do JUnit, AssertJ, Hamcrest, Mockito, Kotest, MockMvc, StepVerifier e afins. */
    private val CALL = Regex(
        """(?<!$NAME)(assert\w*|verify\w*|expect\w*|fail|andExpect\w*|then|should\w*|must\w*|check\w*|require(?!NonNull)\w*|confirmVerified)\s*[(<{]""",
    )
    /** A forma infixa do Kotest e do Strikt: `total shouldBe 10`. */
    private val INFIX = Regex("""\s(should\w*|must\w*)\s""")
    /** `@Test(expected = X.class)`, do JUnit 4. */
    private val EXPECTED = Regex("""expected\s*=\s*([\w.]+)""")
    /** Qualquer chamada: o nome antes do parêntese. */
    private val ANY_CALL = Regex("""(?<!$NAME)([\p{L}_]$NAME*)\s*\(""")
    /** Um passo que promete conferir, pelo nome: `confereRecusa`, `verificaSaldo`, `deveRecusar`, `assertTotal`. */
    private val STEP_VERB = Regex("""^(confer|verific|valid|garant|assegur|deve|devem|esper|ensure)""", RegexOption.IGNORE_CASE)
    /** Uma asserção escrita à mão: `throw new AssertionError(...)`. */
    private val THROWS = Regex("""(?<!$NAME)throw\s+(?:new\s+)?[\w.]*Assertion\w*""")
    /** Uma instrução que é só uma chamada, com ou sem o objeto na frente: `ana.conferirRecusa("X")`. */
    private val SINGLE_CALL = Regex("""^(?:[\p{L}_]$NAME*\s*\.\s*)*([\p{L}_]$NAME*)\s*\(""")
    /** Onde um nome em camelCase quebra: `ColiseuESegundoDia` dá "Coliseu", "E", "Segundo", "Dia". */
    private val WORD_BREAK = Regex("""(?<=[\p{Ll}\p{N}])(?=\p{Lu})|(?<=\p{Lu})(?=\p{Lu}\p{Ll})|_+""")
    private val LAST_IDENTIFIER = Regex("""([\p{L}_]$NAME*)\s*$""")
    private val STRING = Regex(""""(?:\\.|[^"\\])*"""")
    private val SPACES = Regex("\\s+")

    /** O identificador de um teste, pela URL que o runner dá: `loja.FreteTest#calcula`. */
    fun idOf(locationUrl: String): String =
        locationUrl.substringAfter("://").replace('/', '#').substringBefore('(').substringBefore('[')

    /**
     * A prova de um teste. [name] é o `@DisplayName` (ou o nome que o JUnit deu); [declaration], o método inteiro, com
     * as anotações; [helpers], o nome e o método inteiro dos outros métodos da classe; [external], os dos métodos de
     * outras classes do projeto que o teste alcança, como os do ator de um teste de aceitação; nulo quando as chamadas
     * não foram seguidas.
     */
    fun evidence(
        id: String,
        name: String,
        declaration: String,
        helpers: Map<String, String> = emptyMap(),
        external: Map<String, String>? = null,
    ): TestEvidence {
        val range = bodyRange(declaration)
        val body = range?.let { declaration.substring(it.first, it.last + 1) }.orEmpty()
        val header = range?.let { declaration.substring(0, it.first) } ?: declaration
        val found = read(body, Helpers(helpers, external.orEmpty()), header)
        return TestEvidence(id, found.map { shortened(it.statement) }, name = name, proof = found.map { it.line })
    }

    /**
     * As verificações de um teste: as instruções de [body] que chamam uma asserção ou um método de [helpers] (nome e
     * corpo) que verifica alguma coisa. [header] são as anotações e a assinatura, de onde sai o `expected` do JUnit 4.
     */
    fun checks(body: String, helpers: Map<String, String> = emptyMap(), header: String = ""): List<String> =
        read(body, Helpers(helpers, emptyMap()), header).map { shortened(it.statement) }

    private fun shortened(check: String) = if (check.length > MAX_CHECK) check.take(MAX_CHECK) + "…" else check

    /** Um método que o teste pode chamar: o corpo e os nomes dos parâmetros. */
    private class Helper(val body: String, val parameters: List<String>, val varargs: Boolean)

    /**
     * Os métodos que o teste pode chamar: os da própria classe ([own]), chamados sem objeto na frente, e os de outras
     * classes ([external]), chamados de qualquer jeito.
     */
    private class Helpers(own: Map<String, String>, external: Map<String, String>) {
        private val own = own.mapValues { helperOf(it.value) }
        private val external = external.filterKeys { it !in own }.mapValues { helperOf(it.value) }

        private operator fun get(name: String): Helper? = own[name] ?: external[name]

        /** Os métodos conhecidos que [code] chama, na ordem em que aparecem; [code] já sem comentários. */
        fun calledIn(code: String): List<String> {
            if (own.isEmpty() && external.isEmpty()) return emptyList()
            val clean = withoutStrings(code)
            val names = LinkedHashSet<String>()
            for (match in ANY_CALL.findAll(clean)) {
                val name = match.groupValues[1]
                val qualified = clean.substring(0, match.range.first).trimEnd().endsWith(".")
                if (name in external || (name in own && !qualified)) names += name
            }
            return names.toList()
        }

        /**
         * As asserções de dentro de [name], com os parâmetros trocados pelos [arguments] da chamada, seguindo os
         * métodos conhecidos que ele chama.
         */
        fun leaves(name: String, arguments: List<String>?, path: Set<String> = emptySet()): List<String> {
            val helper = this[name] ?: return emptyList()
            if (path.size >= MAX_DEPTH || name in path) return emptyList()
            val mapping = mappingOf(helper, arguments)
            return statements(helper.body).flatMap { raw ->
                val statement = substitute(raw, mapping)
                val called = calledIn(statement)
                val inner = called.flatMap { leaves(it, argumentsOf(statement, it), path + name) }
                if (isCheck(blank(statement, judged(called)))) listOf(statement) + inner else inner
            }
        }

        private fun helperOf(declaration: String): Helper {
            val text = withoutComments(declaration)
            val open = parameters(text)
            val close = open?.let { matching(text, it) }
            val pieces = if (open != null && close != null) splitTop(text.substring(open + 1, close)) else emptyList()
            val names = pieces.mapNotNull { piece ->
                // Kotlin: `nome: Tipo`; Java: `Tipo nome`.
                val declared = if (':' in piece) piece.substringBefore(':') else piece.substringBefore('=')
                LAST_IDENTIFIER.find(declared.trim())?.groupValues?.get(1)
            }
            val varargs = pieces.lastOrNull()?.let { "..." in it || Regex("""(?<!$NAME)vararg\s""").containsMatchIn(it) } == true
            return Helper(bodyOf(declaration), if (names.size == pieces.size) names else emptyList(), varargs)
        }

        /** O nome de cada parâmetro com o argumento da chamada; vazio quando a conta não fecha. */
        private fun mappingOf(helper: Helper, arguments: List<String>?): Map<String, String> {
            val names = helper.parameters
            if (arguments == null || names.isEmpty()) return emptyMap()
            return when {
                helper.varargs && arguments.size >= names.size - 1 ->
                    names.dropLast(1).zip(arguments).toMap() + (names.last() to arguments.drop(names.size - 1).joinToString(", "))
                arguments.size == names.size -> names.zip(arguments).toMap()
                else -> emptyMap()
            }
        }
    }

    /** Os argumentos da primeira chamada a [name] em [statement]; nulo se não achar a chamada inteira. */
    private fun argumentsOf(statement: String, name: String): List<String>? {
        // As strings viram espaços do mesmo tamanho: o que está escrito nelas não é chamada, e as posições não mudam.
        val masked = STRING.replace(statement) { "\"" + " ".repeat(it.value.length - 2) + "\"" }
        val match = ANY_CALL.findAll(masked).firstOrNull { it.groupValues[1] == name } ?: return null
        val close = matching(statement, match.range.last) ?: return null
        return splitTop(statement.substring(match.range.last + 1, close))
    }

    /** As partes de [text] separadas por vírgula fora de parênteses, colchetes, chaves e strings. */
    private fun splitTop(text: String): List<String> {
        val parts = mutableListOf<String>()
        val current = StringBuilder()
        var depth = 0
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '"' || c == '\'') {
                val end = if (text.startsWith("\"\"\"", i)) textBlockEnd(text, i) else stringEnd(text, i)
                current.append(text, i, end)
                i = end
                continue
            }
            when {
                c in "([{" -> depth++
                c in ")]}" -> depth = maxOf(0, depth - 1)
            }
            if (c == ',' && depth == 0) {
                parts += current.toString().trim()
                current.clear()
            } else {
                current.append(c)
            }
            i++
        }
        parts += current.toString().trim()
        return parts.filter { it.isNotEmpty() }
    }

    /** Troca, fora das strings, o nome de cada parâmetro pelo argumento da chamada. */
    private fun substitute(statement: String, mapping: Map<String, String>): String {
        if (mapping.isEmpty()) return statement
        // Um lambda com o mesmo nome (`titulo -> titulo.isBlank()`) esconde o parâmetro: ele fica como está.
        val visible = mapping.filterKeys { name -> !Regex("""(?<!$NAME)${Regex.escape(name)}\s*->""").containsMatchIn(statement) }
        if (visible.isEmpty()) return statement
        // Depois de um ponto ou de `::` é membro de outro objeto, e antes de um parêntese é uma chamada: não é o parâmetro.
        val names = Regex("""(?<![\p{L}\p{N}_.:])(${visible.keys.joinToString("|") { Regex.escape(it) }})(?!$NAME)(?!\s*\()""")
        val out = StringBuilder()
        var position = 0
        for (literal in STRING.findAll(statement)) {
            out.append(names.replace(statement.substring(position, literal.range.first)) { mapping.getValue(it.groupValues[1]) })
            out.append(literal.value)
            position = literal.range.last + 1
        }
        out.append(names.replace(statement.substring(position)) { mapping.getValue(it.groupValues[1]) })
        return out.toString()
    }

    /** Tira o nome dos métodos conhecidos, para o nome deles não passar por asserção: quem diz se verificam é o corpo. */
    private fun blank(statement: String, names: List<String>): String =
        if (names.isEmpty()) statement
        else statement.replace(Regex("""(?<!$NAME)(?:${names.joinToString("|") { Regex.escape(it) }})(?=\s*\()"""), "_")

    /** Uma verificação achada no corpo do teste: a instrução, a linha da ficha e as asserções que ela vale. */
    private class Found(val statement: String, val line: ProofLine, val leaves: List<String>)

    private fun read(body: String, known: Helpers, header: String): List<Found> {
        val found = mutableListOf<Found>()
        EXPECTED.find(withoutComments(header))?.let {
            val text = "espera a exceção ${it.groupValues[1].removeSuffix(".class")}"
            found += Found(text, ProofLine(text), listOf(text))
        }
        for (statement in statements(body)) {
            val called = known.calledIn(statement)
            val inner = called.flatMap { known.leaves(it, argumentsOf(statement, it)) }
            val direct = isCheck(blank(statement, judged(called)))
            if (!direct && inner.isEmpty()) continue
            val innerLines = inner.map(::lineOf)
            val line = if (direct) {
                lineOf(statement).let { ProofLine(it.text, it.code, innerLines) }
            } else {
                val label = stepLabel(statement)
                val promises = called.any { STEP_VERB.containsMatchIn(it) || CALL.containsMatchIn("$it(") }
                ProofLine(label ?: shortened(statement), code = label == null, inner = innerLines, step = true, support = !promises)
            }
            found += Found(statement, line, if (direct) listOf(statement) + inner else inner)
        }
        // Nenhum passo com nome de conferência (outro idioma, outro costume): todos valem como verificação.
        if (found.all { it.line.support }) {
            found.replaceAll { Found(it.statement, ProofLine(it.line.text, it.line.code, it.line.inner, it.line.step), it.leaves) }
        }
        return found
    }

    /** A asserção em português, quando há frase segura; senão, o código. */
    private fun lineOf(statement: String): ProofLine =
        CheckText.describe(statement.removeSuffix(BLOCK))?.let { ProofLine(it) } ?: ProofLine(shortened(statement), code = true)

    /**
     * O passo em palavras, com os argumentos: `ana.conferirRecusa("DIA_NAO_ENCONTRADO")` vira
     * "conferir recusa: DIA_NAO_ENCONTRADO". Nulo quando a instrução não é só uma chamada.
     */
    internal fun stepLabel(statement: String): String? {
        val match = SINGLE_CALL.find(statement) ?: return null
        val close = matching(statement, match.range.last) ?: return null
        if (close != statement.length - 1) return null
        val arguments = splitTop(statement.substring(match.range.last + 1, close)).map { argument ->
            if (STRING.matches(argument)) argument.substring(1, argument.length - 1) else argument
        }
        val name = words(match.groupValues[1])
        return if (arguments.isEmpty()) name else "$name: ${arguments.joinToString(", ")}"
    }

    /** `confereDestinoAusente` vira "confere destino ausente". */
    internal fun words(name: String): String = name.split(WORD_BREAK).filter { it.isNotEmpty() }.joinToString(" ") { it.lowercase() }

    private fun isCheck(statement: String): Boolean {
        val code = withoutStrings(statement)
        return CALL.containsMatchIn(code) || INFIX.containsMatchIn(code) || THROWS.containsMatchIn(code)
    }

    /**
     * Os métodos conhecidos julgados pelo corpo. Um com nome de asserção (`assertPedido`, `verifyTotal`) continua
     * valendo pelo nome, como uma asserção de biblioteca: o corpo dele pode verificar de um jeito que a leitura não vê.
     */
    private fun judged(names: List<String>): List<String> = names.filterNot { CALL.containsMatchIn("$it(") }

    private fun withoutStrings(statement: String): String = statement.replace(STRING, "\"\"")

    /** Comentários viram espaços; as strings ficam, para o conteúdo delas não confundir a leitura das chaves. */
    internal fun withoutComments(text: String): String {
        val out = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                text.startsWith("\"\"\"", i) -> {
                    val end = textBlockEnd(text, i)
                    out.append(text, i, end)
                    i = end
                }
                c == '"' || c == '\'' -> {
                    val end = stringEnd(text, i)
                    out.append(text, i, end)
                    i = end
                }
                text.startsWith("//", i) -> {
                    val end = text.indexOf('\n', i).let { if (it < 0) text.length else it }
                    repeat(end - i) { out.append(' ') }
                    i = end
                }
                text.startsWith("/*", i) -> {
                    val end = text.indexOf("*/", i + 2).let { if (it < 0) text.length else it + 2 }
                    for (k in i until end) out.append(if (text[k] == '\n') '\n' else ' ')
                    i = end
                }
                else -> {
                    out.append(c)
                    i++
                }
            }
        }
        return out.toString()
    }

    private fun textBlockEnd(text: String, start: Int): Int =
        text.indexOf("\"\"\"", start + 3).let { if (it < 0) text.length else it + 3 }

    /** O índice depois da aspa que fecha a string aberta em [start]; a linha acaba a string que não fecha. */
    private fun stringEnd(text: String, start: Int): Int {
        val quote = text[start]
        var i = start + 1
        while (i < text.length) {
            when (text[i]) {
                '\\' -> i++
                quote -> return i + 1
                '\n' -> return i
            }
            i++
        }
        return text.length
    }

    /**
     * As instruções de [body], cada uma numa linha: acabam em `;`, na chave de um bloco ou na quebra de linha, quando
     * os parênteses estão fechados e a linha seguinte não continua a chamada com um ponto.
     */
    internal fun statements(body: String): List<String> {
        val text = withoutComments(body)
        val statements = mutableListOf<String>()
        val current = StringBuilder()
        var depth = 0
        var i = 0
        fun close(suffix: String = "") {
            val statement = current.toString().replace(SPACES, " ").trim()
            if (statement.isNotEmpty()) statements += statement + suffix
            current.clear()
        }
        while (i < text.length) {
            val c = text[i]
            if (text.startsWith("\"\"\"", i) || c == '"' || c == '\'') {
                val end = if (c == '"' && text.startsWith("\"\"\"", i)) textBlockEnd(text, i) else stringEnd(text, i)
                current.append(text, i, end)
                i = end
                continue
            }
            when {
                c == '(' || c == '[' -> depth++
                c == ')' || c == ']' -> depth = maxOf(0, depth - 1)
            }
            val ends = depth == 0 && (c == ';' || c == '{' || c == '}' || (c == '\n' && !continues(current, text, i + 1)))
            if (ends) close(if (c == '{') BLOCK else "") else current.append(c)
            i++
        }
        close()
        return statements
    }

    /** A instrução segue na linha de baixo: ela começa com um ponto, ou esta acaba num operador. */
    private fun continues(current: CharSequence, text: String, next: Int): Boolean {
        var k = next
        while (k < text.length && text[k].isWhitespace()) k++
        if (text.startsWith(".", k) || text.startsWith("?.", k) || text.startsWith("?:", k)) return true
        val last = current.trimEnd().lastOrNull() ?: return false
        return last in ",.+-*/=&|<(:"
    }

    /** Só o corpo de [declaration]: o que fica entre as chaves ou, no Kotlin de uma expressão, depois do `=`. */
    fun bodyOf(declaration: String): String = bodyRange(declaration)?.let { declaration.substring(it.first, it.last + 1) }.orEmpty()

    private fun bodyRange(declaration: String): IntRange? {
        val text = withoutComments(declaration)
        val paren = parameters(text) ?: return null
        val close = matching(text, paren) ?: return null
        var i = close + 1
        while (i < text.length) {
            when (text[i]) {
                '{' -> return matching(text, i)?.let { i + 1 until it }
                '=' -> return i + 1 until text.length
                ';' -> return null
            }
            i++
        }
        return null
    }

    /** O parêntese dos parâmetros: o primeiro fora de uma anotação, que vêm antes, cada uma com os seus. */
    private fun parameters(text: String): Int? {
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                c == '@' -> {
                    var k = i + 1
                    while (k < text.length && (text[k].isLetterOrDigit() || text[k] == '.' || text[k] == '_')) k++
                    var after = k
                    while (after < text.length && text[after] == ' ') after++
                    i = if (after < text.length && text[after] == '(') (matching(text, after) ?: after) + 1 else k
                }
                c == '`' -> i = text.indexOf('`', i + 1).let { if (it < 0) text.length else it + 1 }
                c == '(' -> return i
                else -> i++
            }
        }
        return null
    }

    /** O índice do fechamento do parêntese ou da chave aberta em [open], pulando as strings. */
    private fun matching(text: String, open: Int): Int? {
        val opening = text[open]
        val closing = if (opening == '(') ')' else '}'
        var depth = 0
        var i = open
        while (i < text.length) {
            val c = text[i]
            if (c == '"' || c == '\'') {
                i = if (text.startsWith("\"\"\"", i)) textBlockEnd(text, i) else stringEnd(text, i)
                continue
            }
            if (c == opening) depth++
            if (c == closing && --depth == 0) return i
            i++
        }
        return null
    }
}
