package com.mio.tomahawk.settings

import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.ui.JBColor
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.TitledSeparator
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.mio.tomahawk.run.DevContainer
import com.mio.tomahawk.run.RunMode
import com.mio.tomahawk.run.TomahawkSettings
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * Settings > Tools > IronHawk IA > Tomahawk IA: o que é só do Ataque. Onde lançar as missões, as proteções delas e o
 * `specify` do Spec Kit; a IA (CLI, modelo, chave e o teste) fica no Arsenal, comum aos dois sistemas.
 */
class TomahawkConfigurable : Configurable {

    private val runMode = ComboBox(RunMode.entries.toTypedArray()).apply { renderer = SimpleListCellRenderer.create("") { it.label } }
    private val guardrails = JBCheckBox("Proteções nas missões: regras que barram comandos perigosos, vigia e ponto de restauração")
    private val specify = TextFieldWithBrowseButton().apply {
        addBrowseFolderListener(null, FileChooserDescriptorFactory.createSingleFileNoJarsDescriptor().withTitle("Executável do specify"))
    }
    private val specifyFound = small()

    override fun getDisplayName() = "Tomahawk IA"

    override fun createComponent(): JComponent {
        reset()
        return FormBuilder.createFormBuilder()
            .addComponent(small("A IA das missões (CLI, modelo, chave e o teste) é a do Arsenal, em IronHawk IA > Arsenal (IA)."))
            .addComponent(TitledSeparator("Lançamento"))
            .addLabeledComponent("Onde lançar:", runMode)
            .addComponentToRightColumn(small(
                "Na aba, a missão roda só no devcontainer do projeto (${DevContainer.CONFIG}), a única forma de a IA rodar sem perguntar. " +
                    "No terminal da IDE, modo agente sem permissão total: a IA pede a sua aprovação para os comandos.",
            ))
            .addComponent(guardrails)
            .addComponent(TitledSeparator("Spec Kit"))
            .addLabeledComponent("specify:", specify)
            .addComponentToRightColumn(specifyFound)
            .addComponentToRightColumn(small("Instale com: uv tool install specify-cli. Usado por Inicializar Spec Kit (specify init --here --integration …)."))
            .addComponentFillVertically(JPanel(), 0)
            .panel
            .apply { border = JBUI.Borders.empty(4) }
    }

    override fun isModified(): Boolean =
        runMode.selectedItem != TomahawkSettings.runMode ||
            guardrails.isSelected != TomahawkSettings.guardrails ||
            specify.text.trim() != TomahawkSettings.specifyExecutable.orEmpty()

    override fun apply() {
        TomahawkSettings.runMode = runMode.selectedItem as RunMode
        TomahawkSettings.guardrails = guardrails.isSelected
        TomahawkSettings.specifyExecutable = specify.text
        updateHints()
    }

    override fun reset() {
        runMode.selectedItem = TomahawkSettings.runMode
        guardrails.isSelected = TomahawkSettings.guardrails
        specify.text = TomahawkSettings.specifyExecutable.orEmpty()
        updateHints()
    }

    private fun updateHints() {
        val specifyPath = TomahawkSettings.resolveSpecify(specify.text.trim().ifEmpty { null })
        specifyFound.text = specifyPath?.let { "Encontrado: $it" } ?: "Não encontrado no PATH nem em ~/.local/bin."
        specifyFound.foreground = if (specifyPath != null) UIUtil.getContextHelpForeground() else JBColor.RED
    }

    private companion object {
        fun small(text: String = "") = JBLabel("<html>$text</html>").apply {
            componentStyle = UIUtil.ComponentStyle.SMALL
            foreground = UIUtil.getContextHelpForeground()
        }
    }
}
