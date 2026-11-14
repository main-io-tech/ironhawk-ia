package com.mio.tomahawk.ui

import com.intellij.openapi.Disposable
import com.intellij.openapi.diff.DiffColors
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.LineNumberConverter
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.highlighter.EditorHighlighterFactory
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.JPanel

/** Uma linha do trecho do diff, com o número dela antes ([old]) e depois ([new]) da mudança. */
data class HunkLine(val kind: Kind, val old: Int?, val new: Int?, val text: String) {
    enum class Kind { CONTEXT, ADD, DEL, HEADER, NOTE }
}

/** O trecho do diff que o GitHub guarda na conversa de código, linha a linha. */
object DiffHunk {

    private val HEADER = Regex("""^@@ -(\d+)(?:,\d+)? \+(\d+)(?:,\d+)? @@ ?(.*)$""")

    fun parse(text: String): List<HunkLine> {
        var old = 0
        var new = 0
        return text.trimEnd('\n', '\r').lines().map { raw ->
            val line = raw.trimEnd('\r')
            val header = HEADER.find(line)
            when {
                header != null -> {
                    old = header.groupValues[1].toInt()
                    new = header.groupValues[2].toInt()
                    HunkLine(HunkLine.Kind.HEADER, null, null, header.groupValues[3])
                }
                line.startsWith("+") -> HunkLine(HunkLine.Kind.ADD, null, new++, line.substring(1))
                line.startsWith("-") -> HunkLine(HunkLine.Kind.DEL, old++, null, line.substring(1))
                line.startsWith("\\") -> HunkLine(HunkLine.Kind.NOTE, null, null, line.removePrefix("\\").trim())
                else -> HunkLine(HunkLine.Kind.CONTEXT, old++, new++, line.removePrefix(" "))
            }
        }
    }
}

/**
 * O trecho do diff de uma conversa de código num editor só de leitura: o realce do arquivo, as cores de diff do
 * esquema da IDE e o número de cada linha no arquivo novo (ou no antigo, nas removidas).
 */
class HunkView(private val project: Project) : JPanel(BorderLayout()), Disposable {

    private var editor: EditorEx? = null

    init {
        border = JBUI.Borders.customLine(JBColor.border(), 1)
        isVisible = false
    }

    fun show(hunk: String?, path: String?) {
        release()
        val lines = hunk?.let(DiffHunk::parse).orEmpty().filter { it.kind != HunkLine.Kind.HEADER && it.kind != HunkLine.Kind.NOTE }
        isVisible = lines.isNotEmpty()
        if (lines.isEmpty()) return
        val factory = EditorFactory.getInstance()
        val document = factory.createDocument(lines.joinToString("\n") { it.text })
        val viewer = factory.createViewer(document, project) as EditorEx
        val fileType = path?.substringAfterLast('/')?.let(FileTypeManager.getInstance()::getFileTypeByFileName) ?: PlainTextFileType.INSTANCE
        viewer.highlighter = EditorHighlighterFactory.getInstance().createEditorHighlighter(project, fileType)
        viewer.settings.apply {
            isLineNumbersShown = true
            isFoldingOutlineShown = false
            isLineMarkerAreaShown = false
            isIndentGuidesShown = false
            isCaretRowShown = false
            isRightMarginShown = false
            additionalLinesCount = 0
            additionalColumnsCount = 0
        }
        viewer.gutterComponentEx.setLineNumberConverter(object : LineNumberConverter {
            override fun convert(editor: Editor, lineNumber: Int): Int? = lines.getOrNull(lineNumber - 1)?.let { it.new ?: it.old }

            override fun getMaxLineNumber(editor: Editor): Int = lines.maxOf { it.new ?: it.old ?: 0 }
        })
        lines.forEachIndexed { index, line ->
            val key = when (line.kind) {
                HunkLine.Kind.ADD -> DiffColors.DIFF_INSERTED
                HunkLine.Kind.DEL -> DiffColors.DIFF_DELETED
                else -> null
            } ?: return@forEachIndexed
            viewer.markupModel.addLineHighlighter(key, index, HighlighterLayer.SELECTION - 1)
        }
        val visible = lines.size.coerceAtMost(MAX_LINES)
        viewer.component.preferredSize = Dimension(JBUI.scale(200), viewer.lineHeight * visible + JBUI.scale(8))
        editor = viewer
        add(viewer.component, BorderLayout.CENTER)
        revalidate()
        repaint()
    }

    private fun release() {
        editor?.let { EditorFactory.getInstance().releaseEditor(it) }
        editor = null
        removeAll()
    }

    override fun dispose() = release()

    private companion object {
        const val MAX_LINES = 12
    }
}
