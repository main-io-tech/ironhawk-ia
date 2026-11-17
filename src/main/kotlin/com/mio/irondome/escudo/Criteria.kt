package com.mio.irondome.escudo

import java.text.Normalizer

/** O nível em que um teste prova um critério: a tag `nivel:` do teste e o `exige` do critério. */
enum class Level(val tag: String, val label: String, val initial: String) {
    UNIT("unidade", "Unidade", "U"),
    INTEGRATION("integracao", "Integração", "I"),
    ACCEPTANCE("aceitacao", "Aceitação", "A");

    companion object {
        fun of(text: String): Level? = normalize(text).let { name -> entries.firstOrNull { it.tag == name } }
    }
}

/** Um critério de aceite do catálogo `ironhawk/iron-dome/criterios/` (arquivos `.yaml`). [group] nulo vale o nome do arquivo. */
class Criterion(
    val id: String,
    val title: String,
    val group: String?,
    val origin: String?,
    /** Os níveis que precisam cobrir o critério; vazio vale qualquer nível. */
    val requires: Set<Level>,
    val file: String,
    val line: Int,
) {
    val groupName: String get() = group ?: file.substringBeforeLast('.').replaceFirstChar { it.uppercase() }
}

/** Um problema do contrato num arquivo do catálogo, com a linha. */
class CatalogError(val file: String, val line: Int, val message: String) {
    override fun toString() = "$file:$line: $message"
}

class CriteriaCatalog(val criteria: List<Criterion>, val errors: List<CatalogError>) {

    val isEmpty: Boolean get() = criteria.isEmpty()

    private val byId = criteria.associateBy { it.id.lowercase() }

    fun find(id: String): Criterion? = byId[id.lowercase()]

    companion object {
        val EMPTY = CriteriaCatalog(emptyList(), emptyList())

        /** Junta os arquivos; o ID é único no conjunto, e o repetido vira erro e fica de fora. */
        fun merge(catalogs: List<CriteriaCatalog>): CriteriaCatalog {
            val seen = mutableMapOf<String, Criterion>()
            val errors = catalogs.flatMap { it.errors }.toMutableList()
            val criteria = mutableListOf<Criterion>()
            for (criterion in catalogs.flatMap { it.criteria }) {
                val first = seen[criterion.id.lowercase()]
                if (first == null) {
                    seen[criterion.id.lowercase()] = criterion
                    criteria += criterion
                } else {
                    errors += CatalogError(criterion.file, criterion.line, "ID ${criterion.id} repetido (já está em ${first.file}:${first.line})")
                }
            }
            return CriteriaCatalog(criteria, errors)
        }
    }
}

/**
 * Lê um arquivo do catálogo: uma lista YAML de critérios, cada um com `id`, `titulo` e, opcionalmente, `grupo`,
 * `origem` e `exige`. É o subconjunto do YAML que o contrato usa (valores numa linha, texto entre aspas e lista
 * `[a, b]` ou em bloco, um `- item` por linha), então não depende de biblioteca.
 */
object CriteriaCatalogParser {

    private val key = Regex("""^([\p{L}_][\p{L}\p{N}_]*)\s*:(?:\s+(.*))?$""")
    private val validId = Regex("""[^\s,()&|!"'#]+""")
    private val fields = setOf("id", "titulo", "grupo", "origem", "exige")
    /** Os campos que aceitam lista, em linha (`[a, b]`) ou em bloco, um `- item` por linha. */
    private val listFields = setOf("exige")

    private class Entry(val line: Int, val values: MutableMap<String, Pair<Int, String>> = mutableMapOf())

    fun parse(file: String, text: String): CriteriaCatalog {
        val errors = mutableListOf<CatalogError>()
        val entries = mutableListOf<Entry>()
        var current: Entry? = null
        /** O recuo do `-` que abriu o critério atual. */
        var entryIndent = 0
        /** O campo de lista que ficou sem valor na linha dele, à espera dos itens em lista de bloco (`- aceitacao`). */
        var openList: String? = null

        fun field(number: Int, content: String) {
            openList = null
            val match = key.matchEntire(content)
            if (match == null) {
                errors += CatalogError(file, number, "linha inválida: esperava `campo: valor`")
                return
            }
            val name = normalize(match.groupValues[1])
            if (name !in fields) {
                errors += CatalogError(file, number, "campo desconhecido: ${match.groupValues[1]}")
                return
            }
            val value = match.groupValues[2].trim().takeUnless { it.startsWith("#") }.orEmpty()
            if (value.startsWith("|") || value.startsWith(">")) {
                errors += CatalogError(file, number, "o valor de $name deve estar numa linha só")
                return
            }
            val entry = current ?: return
            if (name in entry.values) {
                errors += CatalogError(file, number, "campo repetido: $name")
            } else {
                entry.values[name] = number to value
                if (name in listFields && value.isEmpty()) openList = name
            }
        }

        /** Um item de lista de bloco vira mais um elemento da lista `[a, b]` do campo. */
        fun listItem(entry: Entry, name: String, item: String) {
            val value = scalar(item.substringBefore(" #")).orEmpty().trim()
            if (value.isEmpty()) return
            val (line, previous) = entry.values.getValue(name)
            entry.values[name] = line to if (previous.isEmpty()) "[$value]" else "${previous.removeSuffix("]")}, $value]"
        }

        text.removePrefix("﻿").lines().forEachIndexed { index, raw ->
            val number = index + 1
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#") || line == "---") return@forEachIndexed
            val indent = raw.length - raw.trimStart().length
            if (line == "-" || line.startsWith("- ")) {
                val entry = current
                val list = openList
                // Mais recuado que o `-` do critério, logo depois de `exige:` sem valor: é um item da lista, não outro critério.
                if (entry != null && list != null && indent > entryIndent) {
                    listItem(entry, list, line.removePrefix("-").trim())
                    return@forEachIndexed
                }
                openList = null
                entryIndent = indent
                current = Entry(number).also { entries += it }
                line.removePrefix("-").trim().takeIf { it.isNotEmpty() }?.let { field(number, it) }
            } else if (current == null) {
                errors += CatalogError(file, number, "campo fora de um critério: comece cada critério com `- `")
            } else {
                field(number, line)
            }
        }

        val criteria = entries.mapNotNull { entry -> criterionOf(file, entry, errors) }
        return CriteriaCatalog(criteria, errors)
    }

    private fun criterionOf(file: String, entry: Entry, errors: MutableList<CatalogError>): Criterion? {
        fun text(name: String): String? = entry.values[name]?.let { (line, raw) ->
            scalar(raw).also { if (it == null && raw.isNotEmpty()) errors += CatalogError(file, line, "valor inválido em $name") }
        }?.takeIf { it.isNotBlank() }

        val id = text("id")
        val title = text("titulo")
        var valid = true
        if (id == null) {
            errors += CatalogError(file, entry.line, "critério sem id")
            valid = false
        } else if (!validId.matches(id)) {
            errors += CatalogError(file, entry.values.getValue("id").first, "id inválido: $id (sem espaços nem , ( ) & | !)")
            valid = false
        } else if (id.equals(CriterionTags.NONE, ignoreCase = true)) {
            errors += CatalogError(file, entry.values.getValue("id").first, "`$id` é reservado para os testes a que nenhum critério se aplica")
            valid = false
        }
        if (title == null) {
            errors += CatalogError(file, entry.line, "critério ${id ?: ""} sem titulo".replace("  ", " "))
            valid = false
        }
        val requires = mutableSetOf<Level>()
        entry.values["exige"]?.let { (line, raw) ->
            val items = list(raw)
            if (items == null) {
                errors += CatalogError(file, line, "exige deve ser uma lista, como [aceitacao]")
                valid = false
            } else {
                items.forEach { name ->
                    Level.of(name)?.let(requires::add)
                        ?: run {
                            errors += CatalogError(file, line, "nível desconhecido em exige: $name (use unidade, integracao ou aceitacao)")
                            valid = false
                        }
                }
            }
        }
        if (!valid || id == null || title == null) return null
        return Criterion(id, title, text("grupo"), text("origem"), requires, file, entry.line)
    }

    /** O valor sem aspas e sem o comentário do fim; nulo se as aspas não fecham. */
    private fun scalar(raw: String): String? {
        val value = raw.trim()
        if (value.isEmpty()) return ""
        val quote = value[0]
        if (quote == '"' || quote == '\'') {
            val out = StringBuilder()
            var i = 1
            while (i < value.length) {
                val c = value[i]
                when {
                    quote == '"' && c == '\\' && i + 1 < value.length -> { out.append(value[i + 1]); i++ }
                    quote == '\'' && c == '\'' && value.getOrNull(i + 1) == '\'' -> { out.append('\''); i++ }
                    c == quote -> {
                        val rest = value.substring(i + 1).trim()
                        return if (rest.isEmpty() || rest.startsWith("#")) out.toString() else null
                    }
                    else -> out.append(c)
                }
                i++
            }
            return null
        }
        return value.substringBefore(" #").trim()
    }

    private fun list(raw: String): List<String>? {
        val value = raw.substringBefore(" #").trim()
        if (!value.startsWith("[") || !value.endsWith("]")) return null
        return value.substring(1, value.length - 1).split(',').map { scalar(it) ?: return null }.filter { it.isNotBlank() }
    }
}

/** O que as tags de um teste dizem: os critérios que ele cobre, o nível e se nenhum critério se aplica. */
class TestCriteria(val ids: List<String>, val notApplicable: Boolean, val level: Level?)

object CriterionTags {
    const val CRITERION = "criterio:"
    const val LEVEL = "nivel:"
    const val NONE = "nenhum"

    fun of(tags: List<String>): TestCriteria {
        val ids = mutableListOf<String>()
        var none = false
        var level: Level? = null
        for (tag in tags) {
            val normalized = normalize(tag.trim())
            when {
                normalized.startsWith(CRITERION) -> {
                    val id = tag.trim().substring(CRITERION.length).trim()
                    when {
                        id.isEmpty() -> Unit
                        id.equals(NONE, ignoreCase = true) -> none = true
                        ids.none { it.equals(id, ignoreCase = true) } -> ids += id
                    }
                }
                normalized.startsWith(LEVEL) -> if (level == null) level = Level.of(normalized.removePrefix(LEVEL))
            }
        }
        return TestCriteria(ids, none, level)
    }

    /** As referências a critérios num texto de código ou de `.feature`: `criterio:ROT-03` em `@Tag` ou no Gherkin. */
    fun idsIn(text: String): Set<String> = REFERENCE.findAll(text)
        .map { it.groupValues[1] }
        .filterNot { it.equals(NONE, ignoreCase = true) }
        .toSet()

    private val REFERENCE = Regex("""(?<![\p{L}\p{N}])crit[eé]rio:([^\s"',()&|!@#]+)""", RegexOption.IGNORE_CASE)
}

/** Sem acento e em minúsculas, para comparar tags e níveis. */
internal fun normalize(text: String): String =
    Normalizer.normalize(text, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").lowercase()

enum class CriterionStatus(val label: String, val symbol: String) {
    MET("Atendido", "✔"),
    PARTIAL("Parcial", "◐"),
    FAILING("Falhando", "✖"),
    NOT_RUN("Não executado", "…"),
    UNTESTED("Sem teste", "∅"),
}

/** Um critério com os testes que o cobrem nesta execução e o status que isso dá. */
class CriterionCoverage(
    val criterion: Criterion,
    val tests: List<TestNode>,
    val status: CriterionStatus,
    /** Os níveis das tags dos testes que passaram. */
    val levels: Set<Level>,
    /** Os níveis de `exige` que ainda não têm teste passando. */
    val missing: Set<Level>,
)

/** Uma tag `criterio:` que aponta para um ID que o catálogo não tem. */
class OrphanTag(val id: String, val tests: List<TestNode>)

class CriteriaReport(
    val coverages: List<CriterionCoverage>,
    val orphans: List<OrphanTag>,
    /** Testes sem nenhuma tag `criterio:`. */
    val withoutCriterion: List<TestNode>,
    /** Testes marcados com `criterio:nenhum`. */
    val notApplicable: List<TestNode>,
) {
    fun count(status: CriterionStatus) = coverages.count { it.status == status }
}

object Criteria {

    /**
     * Cruza o catálogo com os testes executados. [sourceReferences] são os IDs que o código-fonte cita em tags,
     * rodando ou não: um critério citado cujo teste não rodou está "não executado", e não "sem teste".
     */
    fun report(catalog: CriteriaCatalog, tests: List<TestNode>, sourceReferences: Set<String> = emptySet()): CriteriaReport {
        val leaves = tests.filter { it.kind == TestKind.TEST }.distinctBy { it.key }
        val parsed = leaves.map { it to CriterionTags.of(it.tags) }
        val cited = sourceReferences.map { it.lowercase() }.toSet()

        val coverages = catalog.criteria.map { criterion ->
            val covering = parsed.filter { (_, tags) -> tags.ids.any { it.equals(criterion.id, ignoreCase = true) } }
            coverage(criterion, covering, criterion.id.lowercase() in cited)
        }
        val orphans = parsed
            .flatMap { (test, tags) -> tags.ids.filter { catalog.find(it) == null }.map { id -> id to test } }
            .groupBy { it.first.lowercase() }
            .values
            .map { group -> OrphanTag(group.first().first, group.map { it.second }) }
        return CriteriaReport(
            coverages,
            orphans.sortedBy { it.id.lowercase() },
            parsed.filter { (_, tags) -> tags.ids.isEmpty() && !tags.notApplicable }.map { it.first },
            parsed.filter { (_, tags) -> tags.ids.isEmpty() && tags.notApplicable }.map { it.first },
        )
    }

    private fun coverage(criterion: Criterion, covering: List<Pair<TestNode, TestCriteria>>, cited: Boolean): CriterionCoverage {
        val tests = covering.map { it.first }
        // Ignorado não conta como cobertura.
        val counted = covering.filter { it.first.outcome != TestOutcome.IGNORED }
        val outcomes = counted.map { it.first.outcome }
        val passed = counted.filter { it.first.outcome == TestOutcome.PASSED }
        val levels = passed.mapNotNull { it.second.level }.toSet()
        val status = when {
            TestOutcome.FAILED in outcomes -> CriterionStatus.FAILING
            counted.isEmpty() -> if (cited || tests.isNotEmpty()) CriterionStatus.NOT_RUN else CriterionStatus.UNTESTED
            outcomes.any { it != TestOutcome.PASSED } -> CriterionStatus.NOT_RUN
            criterion.requires.any { it !in levels } -> CriterionStatus.PARTIAL
            else -> CriterionStatus.MET
        }
        val missing = if (status == CriterionStatus.MET || status == CriterionStatus.PARTIAL) criterion.requires - levels else emptySet()
        return CriterionCoverage(criterion, tests, status, levels, missing)
    }
}
