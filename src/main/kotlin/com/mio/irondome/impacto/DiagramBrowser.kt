package com.mio.irondome.impacto

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.util.Disposer
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBPanelWithEmptyText
import com.intellij.ui.jcef.JBCefApp
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.ui.jcef.JBCefBrowserBase
import com.intellij.ui.jcef.JBCefJSQuery
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.NamedColorUtil
import com.intellij.util.ui.UIUtil
import java.awt.Color
import javax.swing.JComponent

/**
 * Diagrama de classes (UML) desenhado em SVG no navegador embutido. Mensagens da página chegam em
 * [onMessage] na EDT, no formato `acao:nomeQualificado`.
 */
class DiagramBrowser(parent: Disposable, private val onMessage: (String) -> Unit) {

    private val browser: JBCefBrowser?
    private val query: JBCefJSQuery?
    val component: JComponent

    init {
        if (JBCefApp.isSupported()) {
            val browser = JBCefBrowser()
            Disposer.register(parent, browser)
            val query = JBCefJSQuery.create(browser as JBCefBrowserBase)
            Disposer.register(parent, query)
            query.addHandler { message ->
                ApplicationManager.getApplication().invokeLater { onMessage(message) }
                null
            }
            this.browser = browser
            this.query = query
            component = browser.component
        } else {
            browser = null
            query = null
            component = JBPanelWithEmptyText().withEmptyText("O navegador embutido (JCEF) não está disponível nesta IDE")
        }
    }

    fun show(graph: DependencyGraph) {
        val browser = browser ?: return
        // O tema é lido na EDT; a montagem da página acontece fora dela, e só a carga volta para a EDT.
        val theme = theme()
        val bridge = query!!.inject("message")
        val data = toJson(graph)
        val generation = ++loads
        ApplicationManager.getApplication().executeOnPooledThread {
            val html = template
                .replace("/*IRON_DOME_THEME_CSS*/", theme.entries.joinToString(" ") { (key, value) -> "--$key: $value;" })
                .replace("/*IRON_DOME_BRIDGE*/", bridge)
                .replace("/*IRON_DOME_DATA*/", Json.safeForScript(data))
            ApplicationManager.getApplication().invokeLater {
                if (generation == loads && !browser.isDisposed) browser.loadHTML(html)
            }
        }
    }

    /** Só a última página pedida é carregada, quando duas análises terminam em sequência. */
    private var loads = 0

    fun showMessage(text: String) {
        val theme = theme()
        val accent = theme["accent"]
        loads++
        browser?.loadHTML(
            """
            <html><head><style>
              body { margin: 0; height: 100vh; display: flex; flex-direction: column; gap: 18px; align-items: center;
                justify-content: center; background: radial-gradient(circle, ${accent}14 0%, ${theme["bg"]} 60%), ${theme["bg"]};
                color: ${theme["muted"]}; font: 13px "Segoe UI", -apple-system, sans-serif; text-align: center;
                padding: 24px; box-sizing: border-box; }
              .radar { position: relative; width: 150px; height: 150px; border-radius: 50%;
                border: 1px solid ${accent}55; box-shadow: 0 0 40px ${accent}22 inset; }
              .radar::before, .radar::after { content: ''; position: absolute; border-radius: 50%; border: 1px dashed ${accent}44; }
              .radar::before { inset: 25px; } .radar::after { inset: 52px; }
              /* Poucas voltas e para: animação sem fim mantém o navegador embutido repintando a aba o tempo todo. */
              .sweep { position: absolute; inset: 0; border-radius: 50%; animation: spin 4s linear 4;
                background: conic-gradient(from 0deg, transparent 0deg 280deg, ${accent}66 360deg); }
              .core { position: absolute; left: 50%; top: 50%; width: 12px; height: 12px; margin: -6px; border-radius: 50%;
                background: $accent; box-shadow: 0 0 12px $accent; }
              .blip { position: absolute; width: 7px; height: 7px; border-radius: 50%; animation: blink 4s 4; opacity: .5; }
              .text { max-width: 440px; line-height: 1.6; }
              .title { color: ${theme["fg"]}; font-size: 15px; font-weight: 600; margin-bottom: 4px; }
              @keyframes spin { to { transform: rotate(360deg); } }
              @keyframes blink { 0%, 100% { opacity: .15; } 50% { opacity: 1; } }
              @media (prefers-reduced-motion: reduce) { .sweep, .blip { animation: none; } }
            </style></head><body>
              <div class="radar"><div class="sweep"></div><div class="core"></div>
                <div class="blip" style="left: 104px; top: 34px; background: ${theme["highSolid"]}; animation-delay: .4s"></div>
                <div class="blip" style="left: 30px; top: 92px; background: ${theme["mediumSolid"]}; animation-delay: 2.2s"></div>
                <div class="blip" style="left: 96px; top: 112px; background: ${theme["lowSolid"]}; animation-delay: 3.1s"></div>
              </div>
              <div class="text"><div class="title">Raio de Impacto</div>${Json.escapeHtml(text)}</div>
            </body></html>
            """.trimIndent(),
        )
    }

    fun select(qualifiedName: String) {
        val cef = browser?.cefBrowser ?: return
        cef.executeJavaScript("window.ironDome && window.ironDome.select(${Json.string(qualifiedName)})", cef.url, 0)
    }

    private fun toJson(graph: DependencyGraph): String = Json.obj(
        "focus" to graph.focus,
        "truncated" to graph.truncated,
        "nodes" to graph.nodes.map { node ->
            Json.raw(
                Json.obj(
                    "id" to node.qualifiedName,
                    "name" to node.name,
                    "pkg" to node.packageName,
                    "kind" to node.kind.name,
                    "afferent" to node.afferent,
                    "efferent" to node.efferent,
                    "level" to node.level,
                    "attributes" to node.attributes,
                    "operations" to node.operations,
                    "hiddenAttributes" to node.hiddenAttributes,
                    "hiddenOperations" to node.hiddenOperations,
                ),
            )
        },
        "edges" to graph.edges.map { edge ->
            Json.raw(Json.obj("source" to edge.from, "target" to edge.to, "kind" to edge.kind.name))
        },
    )

    private fun theme(): Map<String, String> {
        val dark = !JBColor.isBright()
        return linkedMapOf(
            "bg" to hex(UIUtil.getPanelBackground()),
            "panel" to hex(JBColor.namedColor("ToolWindow.background", UIUtil.getPanelBackground())),
            "fg" to hex(UIUtil.getLabelForeground()),
            "muted" to hex(NamedColorUtil.getInactiveTextColor()),
            "border" to hex(JBColor.border()),
            "accent" to hex(JBUI.CurrentTheme.Focus.focusColor()),
            "edge" to if (dark) "#8b949e" else "#57606a",
            "match" to if (dark) "#d2a8ff" else "#8250df",
            "box" to if (dark) "#22272e" else "#ffffff",
            "kindClass" to if (dark) "#1f3a5f" else "#ddf4ff",
            "kindAbstract" to if (dark) "#3b2a5c" else "#fbefff",
            "kindInterface" to if (dark) "#1c4529" else "#dafbe1",
            "kindEnum" to if (dark) "#5a3a12" else "#fff1e5",
            "kindAnnotation" to if (dark) "#3a3f47" else "#eaeef2",
            "lowSolid" to if (dark) "#3fb950" else "#1a7f37",
            "mediumSolid" to if (dark) "#d29922" else "#9a6700",
            "highSolid" to if (dark) "#f85149" else "#cf222e",
        )
    }

    private fun hex(color: Color) = "#%02x%02x%02x".format(color.red, color.green, color.blue)

    private companion object {
        val template: String by lazy {
            DiagramBrowser::class.java.getResourceAsStream("/diagram/diagram.html")!!.use { it.readBytes().toString(Charsets.UTF_8) }
        }
    }
}

/** JSON mínimo para mandar o grafo à página, sem depender de biblioteca. */
internal object Json {

    class Raw(val json: String)

    fun raw(json: String) = Raw(json)

    fun obj(vararg entries: Pair<String, Any?>): String = obj(entries.toMap())

    fun obj(entries: Map<String, Any?>): String =
        entries.entries.joinToString(",", "{", "}") { (key, value) -> "${string(key)}:${value(value)}" }

    fun string(text: String): String = buildString {
        append('"')
        for (char in text) {
            when {
                char == '"' -> append("\\\"")
                char == '\\' -> append("\\\\")
                char == '\n' -> append("\\n")
                char == '\r' -> append("\\r")
                char == '\t' -> append("\\t")
                char < ' ' || char == '\u2028' || char == '\u2029' -> append("\\u%04x".format(char.code))
                else -> append(char)
            }
        }
        append('"')
    }

    /** Impede que um `</script>` dentro dos dados feche o bloco de script da página. */
    fun safeForScript(json: String): String = json.replace("</", "<\\/")

    fun escapeHtml(text: String): String =
        text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private fun value(value: Any?): String = when (value) {
        null -> "null"
        is Raw -> value.json
        is String -> string(value)
        is Boolean, is Int, is Long -> value.toString()
        is Double -> if (value.isFinite()) "%.4f".format(java.util.Locale.ROOT, value) else "null"
        is Collection<*> -> value.joinToString(",", "[", "]") { value(it) }
        else -> string(value.toString())
    }
}
