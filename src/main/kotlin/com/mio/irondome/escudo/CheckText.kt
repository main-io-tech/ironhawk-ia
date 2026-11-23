package com.mio.irondome.escudo

/**
 * A verificação de um teste dita em português, para quem confere sem ler código: `assertThat(total).isEqualTo(10)` vira
 * "total é igual a 10". A tradução é literal e só cobre as formas conhecidas do JUnit, do AssertJ, do Mockito e do
 * MockMvc; diante de qualquer coisa que não reconhece por inteiro, devolve nulo, e a tela mostra o código como está,
 * em vez de arriscar dizer o que o teste não verifica.
 */
object CheckText {

    private class Call(val name: String, val args: List<String>)

    /** A frase da verificação [statement], ou nulo se ela tem algo que a tradução não conhece. */
    fun describe(statement: String): String? {
        val code = statement.trim().removeSuffix(";").trim()
        if (code.startsWith("espera a exceção")) return code
        infix(code)?.let { return it }
        val calls = chain(code) ?: return expectations(code)
        val head = calls.first()
        val rest = calls.drop(1)
        return when (head.name) {
            "assertThat", "then", "assertThatObject" -> head.args.singleOrNull()?.let { assertJ(tidy(it), rest) }
            "assertThatThrownBy", "assertThatCode" -> thrown(rest)
            "assertThatExceptionOfType" -> head.args.singleOrNull()?.takeIf { rest.firstOrNull()?.name == "isThrownBy" }?.let { type ->
                join(listOf("lança ${typeName(type)}") + (messages(rest.drop(1)) ?: return null))
            }
            "assertEquals", "assertSame", "assertIterableEquals", "assertArrayEquals", "assertLinesMatch" ->
                head.takeIf { rest.isEmpty() && it.args.size in 2..3 }?.let { "${tidy(it.args[1])} é igual a ${tidy(it.args[0])}" }
            "assertNotEquals", "assertNotSame" ->
                head.takeIf { rest.isEmpty() && it.args.size in 2..3 }?.let { "${tidy(it.args[1])} é diferente de ${tidy(it.args[0])}" }
            "assertTrue" -> one(head, rest)?.let { "${tidy(it)} é verdadeiro" }
            "assertFalse" -> one(head, rest)?.let { "${tidy(it)} é falso" }
            "assertNull" -> one(head, rest)?.let { "${tidy(it)} é nulo" }
            "assertNotNull" -> one(head, rest)?.let { "${tidy(it)} existe" }
            "assertThrows", "assertThrowsExactly" -> head.takeIf { rest.isEmpty() && it.args.size >= 2 }?.let { "lança ${typeName(it.args[0])}" }
            "assertDoesNotThrow" -> "não lança exceção".takeIf { rest.isEmpty() }
            "verify" -> verify(head, rest)
            "verifyNoInteractions", "verifyZeroInteractions" -> "não usa ${head.args.joinToString(", ", transform = ::tidy)}".takeIf { rest.isEmpty() && head.args.isNotEmpty() }
            "verifyNoMoreInteractions" -> "não usa ${head.args.joinToString(", ", transform = ::tidy)} para mais nada".takeIf { rest.isEmpty() && head.args.isNotEmpty() }
            else -> expectations(code)
        }
    }

    private fun one(head: Call, rest: List<Call>): String? = head.args.takeIf { rest.isEmpty() && it.size in 1..2 }?.first()

    /** As asserções encadeadas do AssertJ sobre [subject]: cada uma vira uma frase, ligadas por "e". */
    private fun assertJ(subject: String, calls: List<Call>): String? {
        var current = subject
        val clauses = mutableListOf<String>()
        for (call in calls) {
            val args = call.args
            val first = args.firstOrNull()?.let(::tidy)
            val all = args.joinToString(", ", transform = ::tidy)
            val clause: String? = when (call.name) {
                "as", "describedAs", "withFailMessage", "overridingErrorMessage", "usingRecursiveComparison", "asString", "asList", "isNotNull" ->
                    if (call.name == "isNotNull") "$current existe" else ""
                "extracting", "map" -> {
                    current = extracted(current, args) ?: return null
                    ""
                }
                "singleElement" -> { current = "o único item de $current"; "" }
                "first" -> { current = "o primeiro item de $current"; "" }
                "last" -> { current = "o último item de $current"; "" }
                "element" -> { current = "o item ${first ?: return null} de $current"; "" }
                "filteredOn" -> { current = "os itens de $current com ${filter(args) ?: return null}"; "" }
                "isEqualTo", "isEqualByComparingTo", "isSameAs", "hasToString", "isEqualToIgnoringCase", "isEqualToNormalizingWhitespace" -> "$current é igual a ${first ?: return null}"
                "isNotEqualTo", "isNotSameAs" -> "$current é diferente de ${first ?: return null}"
                "isTrue" -> "$current é verdadeiro"
                "isFalse" -> "$current é falso"
                "isNull" -> "$current é nulo"
                "isEmpty", "isNullOrEmpty", "isNotPresent" -> "$current está vazio"
                "isNotEmpty" -> "$current não está vazio"
                "isBlank" -> "$current está em branco"
                "isNotBlank" -> "$current não está em branco"
                "isPresent" -> "$current tem valor"
                "hasValue", "contains" -> if (call.name == "hasValue") "$current tem o valor ${first ?: return null}" else "$current contém $all"
                "containsExactly", "containsExactlyElementsOf", "containsExactlyEntriesOf" ->
                    if (args.size == 1 && !call.name.endsWith("Of")) "$current tem só $all" else "$current é exatamente $all, nesta ordem"
                "containsExactlyInAnyOrder", "containsExactlyInAnyOrderElementsOf", "containsOnly" ->
                    if (args.size == 1 && !call.name.endsWith("Of")) "$current tem só $all" else "$current é exatamente $all, em qualquer ordem"
                "containsOnlyOnce" -> "$current contém $all uma vez só"
                "containsAnyOf" -> "$current contém algum de $all"
                "doesNotContain" -> "$current não contém $all"
                "doesNotHaveDuplicates" -> "$current não tem repetidos"
                "containsKey", "containsKeys" -> "$current tem a chave $all"
                "doesNotContainKey", "doesNotContainKeys" -> "$current não tem a chave $all"
                "containsEntry" -> args.takeIf { it.size == 2 }?.let { "$current tem ${tidy(it[0])} = ${tidy(it[1])}" } ?: return null
                "hasSize" -> first?.let { "$current tem $it ${if (it == "1") "item" else "itens"}" } ?: return null
                "isInstanceOf", "isExactlyInstanceOf" -> "$current é um ${typeName(args.singleOrNull() ?: return null)}"
                "hasFieldOrPropertyWithValue" -> args.takeIf { it.size == 2 }?.let { "$current.${unquote(it[0])} é igual a ${tidy(it[1])}" } ?: return null
                "hasMessage" -> "a mensagem de $current é ${first ?: return null}"
                "hasMessageContaining", "hasMessageStartingWith" -> "a mensagem de $current contém ${first ?: return null}"
                "isGreaterThan" -> "$current é maior que ${first ?: return null}"
                "isGreaterThanOrEqualTo" -> "$current é maior ou igual a ${first ?: return null}"
                "isLessThan" -> "$current é menor que ${first ?: return null}"
                "isLessThanOrEqualTo" -> "$current é menor ou igual a ${first ?: return null}"
                "isBetween" -> args.takeIf { it.size == 2 }?.let { "$current está entre ${tidy(it[0])} e ${tidy(it[1])}" } ?: return null
                "isZero" -> "$current é zero"
                "isPositive" -> "$current é positivo"
                "isNegative" -> "$current é negativo"
                "startsWith" -> "$current começa com $all"
                "endsWith" -> "$current termina com $all"
                "matches" -> "$current segue o padrão ${first ?: return null}"
                "isIn" -> "$current é um de $all"
                "isNotIn" -> "$current não é nenhum de $all"
                "isAfter" -> "$current é depois de ${first ?: return null}"
                "isBefore" -> "$current é antes de ${first ?: return null}"
                "isInstanceOfSatisfying" -> args.takeIf { it.size == 2 }?.let { "$current é um ${typeName(it[0])}, e ${nested(it[1]) ?: return null}" } ?: return null
                "satisfies", "hasValueSatisfying" -> nested(args.singleOrNull() ?: return null) ?: return null
                "anySatisfy" -> "em algum item de $current, ${nested(args.singleOrNull() ?: return null) ?: return null}"
                "allSatisfy" -> "em todo item de $current, ${nested(args.singleOrNull() ?: return null) ?: return null}"
                "noneSatisfy" -> "em nenhum item de $current, ${nested(args.singleOrNull() ?: return null) ?: return null}"
                else -> return null
            }
            if (!clause.isNullOrEmpty()) clauses += clause
        }
        return join(clauses)
    }

    /** `extracting("codigo")`, `extracting(Tarefa::texto)` ou `map(Tarefa::texto)`: a verificação passa a ser sobre o campo. */
    private fun extracted(subject: String, args: List<String>): String? {
        if (args.isEmpty()) return null
        val fields = args.map { arg -> field(arg) ?: return null }
        return if (fields.size == 1) "$subject.${fields.single()}" else "$subject (${fields.joinToString(", ")})"
    }

    private fun field(argument: String): String? {
        val arg = argument.trim()
        QUOTED.matchEntire(arg)?.let { return it.groupValues[1] }
        REFERENCE.matchEntire(arg)?.let { return getter(it.groupValues[1]) }
        // `e -> e.campo() + ":" + e.codigo()` vira `campo + ":" + codigo`: o que se tira de cada item.
        LAMBDA.matchEntire(arg)?.let { match ->
            val (parameter, expression) = match.destructured
            if (expression.trimStart().startsWith("{")) return null
            return tidy(expression).replace(Regex("""(?<![\w.])${Regex.escape(parameter)}\."""), "")
        }
        return null
    }

    /** `filteredOn("feita", true)` ou `filteredOn(Tarefa::feita)`. */
    private fun filter(args: List<String>): String? = when (args.size) {
        1 -> field(args[0])
        2 -> field(args[0])?.let { "$it = ${tidy(args[1])}" }
        else -> null
    }

    /** O que uma função passada a `satisfies` e afins verifica: as asserções de dentro dela. */
    private fun nested(lambda: String): String? {
        val body = lambda.substringAfter("->", "").trim().ifEmpty { return null }
        val statements = if (body.startsWith("{") && body.endsWith("}")) split(body.substring(1, body.length - 1), ';') else listOf(body)
        val described = statements.map { it.trim() }.filter { it.isNotEmpty() }.map { describe(it) ?: return null }
        return join(described)
    }

    private fun thrown(rest: List<Call>): String? {
        val type = rest.firstOrNull { it.name == "isInstanceOf" || it.name == "isExactlyInstanceOf" }
        if (rest.singleOrNull()?.name == "doesNotThrowAnyException") return "não lança exceção"
        val others = rest.filter { it !== type }
        val head = type?.args?.singleOrNull()?.let { "lança ${typeName(it)}" } ?: "lança exceção"
        return join(listOf(head) + (messages(others) ?: return null))
    }

    /** O que mais se confere numa exceção esperada; nulo se há algo além da mensagem e dos campos. */
    private fun messages(calls: List<Call>): List<String>? = calls.map { call ->
        val first = call.args.firstOrNull()?.let(::tidy)
        when (call.name) {
            "hasMessage", "withMessage" -> "com a mensagem ${first ?: return null}"
            "hasMessageContaining", "withMessageContaining" -> "com a mensagem contendo ${first ?: return null}"
            "hasFieldOrPropertyWithValue" -> call.args.takeIf { it.size == 2 }?.let { "com ${unquote(it[0])} igual a ${tidy(it[1])}" } ?: return null
            else -> return null
        }
    }

    /** `verify(mock).metodo(args)` do Mockito, com `never()` e `times(n)`. */
    private fun verify(head: Call, rest: List<Call>): String? {
        val mock = head.args.firstOrNull()?.let(::tidy) ?: return null
        val called = rest.singleOrNull() ?: return null
        val what = "$mock.${called.name}(${called.args.joinToString(", ", transform = ::tidy)})"
        val mode = head.args.getOrNull(1)?.trim()
        return when {
            mode == null -> "chama $what"
            mode == "never()" || mode == "times(0)" -> "não chama $what"
            mode == "atLeastOnce()" -> "chama $what ao menos uma vez"
            TIMES.matchEntire(mode) != null -> TIMES.matchEntire(mode)!!.groupValues[1].let { "chama $what $it ${if (it == "1") "vez" else "vezes"}" }
            else -> null
        }
    }

    /** As expectativas do MockMvc: `andExpect(status().isOk())` e `andExpect(jsonPath("$.a").value(1))`. */
    private fun expectations(code: String): String? {
        val found = EXPECT.findAll(code).map { it.range.last }.toList()
        if (found.isEmpty()) return null
        val clauses = found.map { open ->
            val close = closing(code, open) ?: return null
            expectation(code.substring(open + 1, close).trim()) ?: return null
        }
        return join(clauses)
    }

    private fun expectation(argument: String): String? {
        val calls = chain(argument) ?: return null
        val head = calls.first()
        val matcher = calls.getOrNull(1) ?: return null
        if (calls.size != 2) return null
        return when (head.name) {
            "status" -> STATUS[matcher.name]?.let { "a resposta é $it" }
                ?: matcher.takeIf { it.name == "is" }?.args?.singleOrNull()?.let { "a resposta é ${tidy(it)}" }
            "jsonPath" -> head.args.firstOrNull()?.let(::tidy)?.let { path ->
                when (matcher.name) {
                    "value" -> matcher.args.singleOrNull()?.let { "no JSON, $path é ${tidy(it)}" }
                    "exists" -> "o JSON tem $path"
                    "doesNotExist" -> "o JSON não tem $path"
                    "isEmpty" -> "no JSON, $path está vazio"
                    "isArray" -> "no JSON, $path é uma lista"
                    else -> null
                }
            }
            else -> null
        }
    }

    /** A forma infixa do Kotest: `total shouldBe 10`. */
    private fun infix(code: String): String? {
        val match = INFIX.matchEntire(code) ?: return null
        val (subject, matcher, value) = match.destructured
        val what = when (matcher) {
            "shouldBe" -> "é igual a"
            "shouldNotBe" -> "é diferente de"
            "shouldContain" -> "contém"
            "shouldNotContain" -> "não contém"
            "shouldStartWith" -> "começa com"
            else -> return null
        }
        return "${tidy(subject)} $what ${tidy(value)}"
    }

    /** `cabeca(args).a(x).b(y)` em chamadas, ou nulo se o texto não é só uma cadeia de chamadas. */
    private fun chain(code: String): List<Call>? {
        val head = HEAD.find(code) ?: return null
        val calls = mutableListOf<Call>()
        var name = head.groupValues[1]
        var open = head.range.last
        while (true) {
            val close = closing(code, open) ?: return null
            calls += Call(name, split(code.substring(open + 1, close), ','))
            val next = NEXT.find(code, close + 1)?.takeIf { it.range.first == close + 1 }
            if (next == null) return calls.takeIf { code.substring(close + 1).isBlank() }
            name = next.groupValues[1]
            open = next.range.last
        }
    }

    /** O parêntese que fecha o aberto em [open], pulando strings e os parênteses de dentro. */
    private fun closing(code: String, open: Int): Int? {
        var depth = 0
        var quote: Char? = null
        var i = open
        while (i < code.length) {
            val c = code[i]
            when {
                quote != null -> if (c == '\\') i++ else if (c == quote) quote = null
                c == '"' || c == '\'' -> quote = c
                c == '(' -> depth++
                c == ')' -> if (--depth == 0) return i
            }
            i++
        }
        return null
    }

    /** As partes de [text] separadas por [separator] fora de strings, parênteses, colchetes e chaves. */
    private fun split(text: String, separator: Char): List<String> {
        val parts = mutableListOf<String>()
        val current = StringBuilder()
        var depth = 0
        var quote: Char? = null
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                quote != null -> {
                    current.append(c)
                    if (c == '\\' && i + 1 < text.length) current.append(text[++i]) else if (c == quote) quote = null
                }
                c == '"' || c == '\'' -> { quote = c; current.append(c) }
                c in "([{" -> { depth++; current.append(c) }
                c in ")]}" -> { depth--; current.append(c) }
                c == separator && depth == 0 -> { parts += current.toString(); current.clear() }
                else -> current.append(c)
            }
            i++
        }
        parts += current.toString()
        return parts.map { it.trim() }.filter { it.isNotEmpty() }
    }

    private fun join(clauses: List<String>): String? = clauses.filter { it.isNotBlank() }.takeIf { it.isNotEmpty() }?.joinToString(" e ")

    /** O valor como aparece na frase: sem `get`, sem os parênteses de quem não recebe nada e sem `.class`. */
    private fun tidy(expression: String): String = expression.trim()
        .replace(CLASS_LITERAL, "$1")
        .replace(GETTER) { ".${getter(it.groupValues[1])}" }
        .replace(EMPTY_CALL, "$1")
        .replace(TUPLE, "(")
        .removePrefix("this.")

    private fun getter(name: String): String =
        GET_PREFIX.matchEntire(name)?.let { it.groupValues[1].replaceFirstChar(Char::lowercase) } ?: name

    private fun typeName(argument: String): String = argument.trim().removeSuffix(".class").removeSuffix("::class.java").removeSuffix("::class").substringAfterLast('.')

    private fun unquote(text: String): String = text.trim().trim('"')

    private val HEAD = Regex("""^(?:[\w.]+\.)?(\w+)\s*(?:<[^<>()]*>)?\s*\(""")
    private val NEXT = Regex("""\s*\.\s*(\w+)\s*(?:<[^<>()]*>)?\s*\(""")
    private val EXPECT = Regex("""\.\s*andExpect\s*\(""")
    private val INFIX = Regex("""^(.+?)\s+(should\w+)\s+(.+)$""")
    private val TIMES = Regex("""times\((\d+)\)""")
    private val QUOTED = Regex(""""([\w.]+)"""")
    private val REFERENCE = Regex("""[\w.]+::(\w+)""")
    private val LAMBDA = Regex("""(\w+)\s*->\s*(.+)""", RegexOption.DOT_MATCHES_ALL)
    private val TUPLE = Regex("""\btuple\(""")
    private val CLASS_LITERAL = Regex("""\b([\w.]+)\.class\b""")
    private val GETTER = Regex("""\.((?:get|is)\p{Lu}\w*)\(\)""")
    private val GET_PREFIX = Regex("""(?:get|is)(\p{Lu}\w*)""")
    private val EMPTY_CALL = Regex("""(\.\w+)\(\)""")
    private val STATUS = mapOf(
        "isOk" to "200 (OK)", "isCreated" to "201 (criado)", "isAccepted" to "202 (aceito)", "isNoContent" to "204 (sem conteúdo)",
        "isBadRequest" to "400 (pedido inválido)", "isUnauthorized" to "401 (não autenticado)", "isForbidden" to "403 (proibido)",
        "isNotFound" to "404 (não encontrado)", "isConflict" to "409 (conflito)", "isUnprocessableEntity" to "422 (não processável)",
        "isInternalServerError" to "500 (erro interno)",
    )
}
