package com.mio.irondome.escudo

import java.io.StringReader
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import org.xml.sax.InputSource

/** Um cenário de um `.feature`, com as tags dele e as da funcionalidade, sem o `@`. */
class FeatureScenario(val name: String, val tags: List<String>)

class FeatureFile(val name: String, val tags: List<String>, val scenarios: List<FeatureScenario>)

/**
 * O Cucumber rodando fora do runner do JUnit (como `io.cucumber.core.cli.Main` no Maven) não chega à árvore de testes
 * da IDE. Aqui o Escudo lê o XML do plugin `junit:` do Cucumber e junta as tags dos `.feature`, para os cenários
 * entrarem na árvore e nos critérios como qualquer teste.
 */
object CucumberResults {

    const val GROUP = "Cucumber"

    private const val MAX_DEPTH = 8
    private const val MAX_BYTES = 5_000_000L
    private val PRUNED = setOf(".git", ".idea", ".gradle", ".mvn", "node_modules", "classes", "test-classes", "generated-sources", "dependency")
    private val BUILD_DIRS = setOf("target", "build", "out")

    private val FEATURE = Regex("""^(?:Funcionalidade|Característica|Caracteristica|Feature|Business Need|Ability)\s*:\s*(.*)$""", RegexOption.IGNORE_CASE)
    private val SCENARIO = Regex(
        """^(?:Esquema do Cenário|Esquema do Cenario|Cenário|Cenario|Scenario Outline|Scenario Template|Scenario|Exemplo|Example)\s*:\s*(.*)$""",
        RegexOption.IGNORE_CASE,
    )
    private val RESETS_TAGS = Regex("""^(?:Exemplos|Cenários|Cenarios|Examples|Scenarios|Contexto|Fundo|Background|Regra|Rule)\s*:""", RegexOption.IGNORE_CASE)

    /** As tags de cada cenário: as da funcionalidade mais as do próprio cenário. */
    fun parseFeature(text: String): FeatureFile? {
        var featureName: String? = null
        var featureTags = emptyList<String>()
        var pending = mutableListOf<String>()
        val scenarios = mutableListOf<FeatureScenario>()
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) {
                continue
            } else if (line.startsWith("@")) {
                pending += line.substringBefore(" #").split(Regex("\\s+")).filter { it.startsWith("@") && it.length > 1 }.map { it.substring(1) }
            } else {
                val feature = FEATURE.matchEntire(line)
                val scenario = SCENARIO.matchEntire(line)
                when {
                    feature != null && featureName == null -> {
                        featureName = feature.groupValues[1].trim()
                        featureTags = pending
                        pending = mutableListOf()
                    }
                    scenario != null -> {
                        scenarios += FeatureScenario(scenario.groupValues[1].trim(), (featureTags + pending).distinct())
                        pending = mutableListOf()
                    }
                    RESETS_TAGS.containsMatchIn(line) -> pending = mutableListOf()
                }
            }
        }
        return featureName?.let { FeatureFile(it, featureTags, scenarios) }
    }

    private class Case(val feature: String, val name: String, val seconds: Double?, val outcome: TestOutcome, val message: String?, val details: String?)

    private fun cases(xml: String): List<Case> {
        val factory = DocumentBuilderFactory.newInstance().apply {
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            isXIncludeAware = false
            isExpandEntityReferences = false
        }
        val document = factory.newDocumentBuilder().parse(InputSource(StringReader(xml)))
        val elements = document.getElementsByTagName("testcase")
        return (0 until elements.length).map { index ->
            val element = elements.item(index) as Element
            fun child(tag: String) = element.getElementsByTagName(tag).takeIf { it.length > 0 }?.item(0) as? Element
            val failure = child("failure") ?: child("error")
            val outcome = when {
                failure != null -> TestOutcome.FAILED
                child("skipped") != null -> TestOutcome.IGNORED
                else -> TestOutcome.PASSED
            }
            Case(
                feature = element.getAttribute("classname").ifBlank { GROUP },
                name = element.getAttribute("name"),
                seconds = element.getAttribute("time").replace(',', '.').toDoubleOrNull(),
                outcome = outcome,
                message = failure?.getAttribute("message")?.takeIf { it.isNotBlank() },
                details = failure?.textContent?.takeIf { it.isNotBlank() },
            )
        }
    }

    /** Os cenários dos [xmls] agrupados por funcionalidade, com as tags dos [features]; nulo se não houver cenário. */
    fun build(xmls: List<String>, features: List<FeatureFile>): TestNode? {
        val all = xmls.flatMap { runCatching { cases(it) }.getOrDefault(emptyList()) }
        if (all.isEmpty()) return null
        val classes = all.groupBy { it.feature }.map { (featureName, group) ->
            val file = features.firstOrNull { normalize(it.name) == normalize(featureName) }
            val tests = group.mapIndexed { index, case ->
                val wanted = normalize(case.name)
                val scenario = file?.scenarios?.firstOrNull { normalize(it.name) == wanted }
                    ?: file?.scenarios?.firstOrNull { wanted.startsWith(normalize(it.name)) && it.name.isNotBlank() }
                TestNode(
                    kind = TestKind.TEST,
                    key = "cucumber:$featureName|${case.name}|$index",
                    label = case.name,
                    codeName = null,
                    locationUrl = null,
                    durationMillis = case.seconds?.let { (it * 1000).toLong() },
                    errorMessage = case.message,
                    stacktrace = case.details,
                    children = emptyList(),
                    leafOutcome = case.outcome,
                    tags = scenario?.tags ?: file?.tags.orEmpty(),
                )
            }
            TestNode(
                kind = TestKind.CLASS,
                key = "cucumber:$featureName",
                label = featureName,
                codeName = null,
                locationUrl = null,
                durationMillis = tests.mapNotNull { it.durationMillis }.takeIf { it.isNotEmpty() }?.sum(),
                errorMessage = null,
                stacktrace = null,
                children = tests,
                leafOutcome = TestOutcome.NOT_RUN,
                convention = Convention.OK,
                tags = file?.tags.orEmpty(),
            )
        }.sortedBy { it.label.lowercase() }
        return TestNode(
            kind = TestKind.KEY,
            key = "key:cucumber",
            label = GROUP,
            codeName = null,
            locationUrl = null,
            durationMillis = classes.mapNotNull { it.durationMillis }.takeIf { it.isNotEmpty() }?.sum(),
            errorMessage = null,
            stacktrace = null,
            children = classes,
            leafOutcome = TestOutcome.NOT_RUN,
        )
    }

    /** Procura o XML do Cucumber (`*cucumber*.xml` em `target`, `build` ou `out`) e os `.feature` sob [roots]. */
    fun load(roots: List<Path>): TestNode? {
        val xmls = mutableListOf<Path>()
        val features = mutableListOf<Path>()
        for (root in roots) {
            runCatching {
                Files.walkFileTree(root, emptySet(), MAX_DEPTH, object : SimpleFileVisitor<Path>() {
                    override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult =
                        if (dir != root && dir.fileName?.toString() in PRUNED) FileVisitResult.SKIP_SUBTREE else FileVisitResult.CONTINUE

                    override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                        val name = file.fileName.toString().lowercase()
                        val inBuild = root.relativize(file).any { it.toString() in BUILD_DIRS }
                        when {
                            attrs.size() > MAX_BYTES -> Unit
                            name.endsWith(".xml") && "cucumber" in name && inBuild -> xmls.add(file)
                            name.endsWith(".feature") && !inBuild -> features.add(file)
                        }
                        return FileVisitResult.CONTINUE
                    }

                    override fun visitFileFailed(file: Path, exc: java.io.IOException) = FileVisitResult.CONTINUE
                })
            }
        }
        val parsed = features.mapNotNull { runCatching { parseFeature(Files.readString(it)) }.getOrNull() }
        return build(xmls.distinct().mapNotNull { runCatching { Files.readString(it) }.getOrNull() }, parsed)
    }
}
