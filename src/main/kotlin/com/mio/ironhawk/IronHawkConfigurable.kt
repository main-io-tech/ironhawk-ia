package com.mio.ironhawk

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.options.ConfigurationException
import com.intellij.openapi.ui.Messages
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.UIUtil
import javax.swing.JComponent
import javax.swing.JPanel

/** Settings > Tools > IronHawk IA: liga e desliga a defesa (Iron Dome) e o ataque (Tomahawk). */
class IronHawkConfigurable : Configurable {

    private val boxes = IronHawkSystem.entries.associateWith { JBCheckBox(it.title) }

    override fun getDisplayName() = "IronHawk IA"

    override fun createComponent(): JComponent = FormBuilder.createFormBuilder()
        .apply {
            boxes.forEach { (system, box) ->
                addComponent(box)
                addComponent(comment(system.description))
            }
        }
        .addVerticalGap(8)
        .addComponent(comment("Desligado, o sistema sai do painel IronHawk IA e perde as ações e os avisos; o que já está nos editores abertos sai ao reiniciar a IDE."))
        .addComponentFillVertically(JPanel(), 0)
        .panel

    override fun isModified() = boxes.any { (system, box) -> box.isSelected != system.enabled }

    override fun apply() {
        if (boxes.values.none { it.isSelected }) {
            throw ConfigurationException("Deixe pelo menos um sistema ligado. Para desligar os dois, desative o plugin em Settings > Plugins.")
        }
        val changed = boxes.filter { (system, box) -> box.isSelected != system.enabled }.keys
        if (changed.isEmpty()) return
        changed.forEach { it.enabled = boxes.getValue(it).isSelected }
        val restart = Messages.showYesNoDialog(
            "Reinicie a IDE para a mudança valer também nos editores abertos e no que roda ao abrir o projeto.",
            "IronHawk IA", "Reiniciar Agora", "Depois", Messages.getQuestionIcon(),
        ) == Messages.YES
        if (restart) ApplicationManager.getApplication().invokeLater { ApplicationManager.getApplication().restart() }
    }

    override fun reset() {
        boxes.forEach { (system, box) -> box.isSelected = system.enabled }
    }

    private fun comment(text: String) = JBLabel(text).apply {
        componentStyle = UIUtil.ComponentStyle.SMALL
        foreground = UIUtil.getContextHelpForeground()
    }
}
