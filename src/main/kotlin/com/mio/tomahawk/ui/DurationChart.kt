package com.mio.tomahawk.ui

import com.intellij.ui.JBColor
import com.intellij.util.ui.GraphicsUtil
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.mio.tomahawk.run.RunRecord
import com.mio.tomahawk.run.RunStatus
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.geom.RoundRectangle2D
import java.nio.file.Path
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.swing.JComponent
import javax.swing.ToolTipManager

/**
 * As durações das últimas execuções em barras, da mais antiga para a mais recente, na cor da situação de cada uma.
 * Clicar numa barra escolhe a execução; a selecionada ganha contorno.
 */
class DurationChart(private val onPick: (RunRecord) -> Unit) : JComponent() {

    /** Da mais antiga para a mais recente. */
    var runs: List<RunRecord> = emptyList()
        set(value) {
            field = value
            repaint()
        }

    var selected: Path? = null
        set(value) {
            field = value
            repaint()
        }

    private var hovered = -1

    init {
        isOpaque = false
        ToolTipManager.sharedInstance().registerComponent(this)
        val mouse = object : MouseAdapter() {
            override fun mouseMoved(e: MouseEvent) {
                val index = indexAt(e.x)
                if (index != hovered) {
                    hovered = index
                    cursor = if (index >= 0) Cursor.getPredefinedCursor(Cursor.HAND_CURSOR) else Cursor.getDefaultCursor()
                    repaint()
                }
            }

            override fun mouseExited(e: MouseEvent) {
                hovered = -1
                repaint()
            }

            override fun mouseClicked(e: MouseEvent) {
                runs.getOrNull(indexAt(e.x))?.let(onPick)
            }
        }
        addMouseListener(mouse)
        addMouseMotionListener(mouse)
    }

    override fun getPreferredSize() = Dimension(JBUI.scale(200), JBUI.scale(BARS_HEIGHT + LEGEND_HEIGHT))

    override fun getToolTipText(event: MouseEvent): String? {
        val run = runs.getOrNull(indexAt(event.x)) ?: return null
        val time = if (run.startedAt > 0) LocalDateTime.ofInstant(Instant.ofEpochMilli(run.startedAt), ZoneId.systemDefault()).format(WHEN) else "—"
        val duration = if (run.status == RunStatus.TERMINAL) "no terminal" else elapsed(run.durationSeconds)
        return "${run.title} · ${run.status.label} · $duration · $time"
    }

    private fun barWidth(): Int {
        if (runs.isEmpty()) return 0
        val gap = JBUI.scale(GAP)
        return ((width - gap * (runs.size - 1)) / runs.size).coerceIn(JBUI.scale(MIN_BAR), JBUI.scale(MAX_BAR))
    }

    private fun indexAt(x: Int): Int {
        val bar = barWidth()
        if (bar == 0) return -1
        val step = bar + JBUI.scale(GAP)
        val index = x / step
        return if (index in runs.indices && x - index * step <= bar) index else -1
    }

    override fun paintComponent(g: Graphics) {
        if (runs.isEmpty()) return
        val g2 = g.create() as Graphics2D
        try {
            GraphicsUtil.setupAAPainting(g2)
            val bars = JBUI.scale(BARS_HEIGHT)
            val bar = barWidth()
            val gap = JBUI.scale(GAP)
            val peak = runs.maxOf { it.durationSeconds }.coerceAtLeast(1)
            g2.color = JBColor.border()
            g2.fillRect(0, bars - 1, width, 1)
            val arc = JBUI.scale(5).toFloat()
            runs.forEachIndexed { index, run ->
                val fraction = if (run.status == RunStatus.TERMINAL) 0.12 else 0.12 + 0.88 * run.durationSeconds / peak
                val height = (bars * fraction).toInt().coerceAtLeast(JBUI.scale(4))
                val x = index * (bar + gap)
                val shape = RoundRectangle2D.Float(x.toFloat(), (bars - height).toFloat(), bar.toFloat(), height.toFloat(), arc, arc)
                val accent = run.status.tone.accent
                g2.color = if (index == hovered) accent else Color(accent.red, accent.green, accent.blue, 200)
                g2.fill(shape)
                if (run.file == selected) {
                    g2.color = UIUtil.getLabelForeground()
                    g2.stroke = BasicStroke(JBUI.scale(1.5f))
                    g2.draw(shape)
                }
            }
            g2.font = JBFont.small()
            g2.color = UIUtil.getContextHelpForeground()
            val metrics = g2.fontMetrics
            val baseline = bars + JBUI.scale(4) + metrics.ascent
            val right = (runs.size * (bar + gap) - gap).coerceAtMost(width)
            g2.drawString("mais antiga", 0, baseline)
            val peakText = "pico: ${elapsed(peak)}"
            g2.drawString(peakText, (right - metrics.stringWidth(peakText)) / 2, baseline)
            val newest = "mais recente"
            g2.drawString(newest, right - metrics.stringWidth(newest), baseline)
        } finally {
            g2.dispose()
        }
    }

    private companion object {
        const val BARS_HEIGHT = 56
        const val LEGEND_HEIGHT = 20
        const val GAP = 4
        const val MIN_BAR = 6
        const val MAX_BAR = 26
        val WHEN: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM HH:mm")
    }
}
