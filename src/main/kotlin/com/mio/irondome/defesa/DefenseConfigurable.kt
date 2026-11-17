package com.mio.irondome.defesa

import com.intellij.openapi.options.Configurable
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.UIUtil
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * Settings > Tools > IronHawk IA > Defesa: Inteligência da IA: o que vai junto dos pedidos de revisão à IA e o aviso de
 * conflito entre comentários. Hoje o dossiê e o aprendizado só entram na Revisão com IA da Patrulha.
 */
class DefenseConfigurable : Configurable {

    private val feedback = JBCheckBox("Aprender com o que o time aceita e descarta das sugestões da IA")
    private val dossier = JBCheckBox("Anexar aos pedidos o dossiê das classes (histórico, comentários antigos, decisões)")
    private val conflicts = JBCheckBox("Avisar quando dois comentários apontam para o mesmo trecho")

    override fun getDisplayName() = DISPLAY_NAME

    override fun createComponent(): JComponent = FormBuilder.createFormBuilder()
        .addComponent(comment("Dossiê e aprendizado entram na Revisão com IA da Patrulha; o aviso vale para os comentários pendentes."))
        .addVerticalGap(8)
        .addComponent(feedback)
        .addComponent(comment("Fica em ironhawk/iron-dome/defesa/aprendizado-revisao.md: escreva ali também regras do time, uma por linha."))
        .addComponent(dossier)
        .addComponent(comment("Decisões por classe em ironhawk/iron-dome/defesa/decisoes.md, numa seção ## Arquivo.kt com itens - ."))
        .addComponent(conflicts)
        .addComponent(comment("O aviso oferece pedir à IA uma síntese do que os dois comentários querem."))
        .addComponentFillVertically(JPanel(), 0)
        .panel

    private fun pairs() = listOf(
        feedback to DefenseSettings::feedbackLearning,
        dossier to DefenseSettings::dossier,
        conflicts to DefenseSettings::conflictDetection,
    )

    override fun isModified() = pairs().any { (box, property) -> box.isSelected != property.get() }

    override fun apply() {
        pairs().forEach { (box, property) -> if (box.isSelected != property.get()) property.set(box.isSelected) }
    }

    override fun reset() {
        pairs().forEach { (box, property) -> box.isSelected = property.get() }
    }

    private fun comment(text: String) = JBLabel(text).apply {
        componentStyle = UIUtil.ComponentStyle.SMALL
        foreground = UIUtil.getContextHelpForeground()
    }

    companion object {
        const val DISPLAY_NAME = "Defesa: Inteligência da IA"
    }
}
