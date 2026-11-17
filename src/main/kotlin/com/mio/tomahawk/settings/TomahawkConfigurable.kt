package com.mio.tomahawk.settings

import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.ui.JBColor
import com.intellij.ui.TitledSeparator
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.mio.tomahawk.run.TomahawkSettings
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * Settings > Tools > IronHawk IA > Ataque: Spec Kit e Proteções: o que é só do Ataque. As proteções das missões e o `specify` do Spec
 * Kit; a IA (CLI, modelo, chave, onde rodar e o teste) fica no Arsenal, comum aos dois sistemas.
 */
class TomahawkConfigurable : Configurable {

    private val guardrails = JBCheckBox("Proteções nas missões: regras que barram comandos perigosos, vigia e ponto de restauração")
    private val specify = TextFieldWithBrowseButton().apply {
        addBrowseFolderListener(null, FileChooserDescriptorFactory.createSingleFileNoJarsDescriptor().withTitle("Executável do specify"))
    }
    private val specifyFound = small()

    override fun getDisplayName() = DISPLAY_NAME

    override fun createComponent(): JComponent {
        reset()
        return FormBuilder.createFormBuilder()
            .addComponent(small("A IA das missões é a do Arsenal (IA); na aba, elas rodam só no devcontainer do projeto."))
            .addComponent(TitledSeparator("Missões"))
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
        guardrails.isSelected != TomahawkSettings.guardrails ||
            specify.text.trim() != TomahawkSettings.specifyExecutable.orEmpty()

    override fun apply() {
        TomahawkSettings.guardrails = guardrails.isSelected
        TomahawkSettings.specifyExecutable = specify.text
        updateHints()
    }

    override fun reset() {
        guardrails.isSelected = TomahawkSettings.guardrails
        specify.text = TomahawkSettings.specifyExecutable.orEmpty()
        updateHints()
    }

    private fun updateHints() {
        val specifyPath = TomahawkSettings.resolveSpecify(specify.text.trim().ifEmpty { null })
        specifyFound.text = specifyPath?.let { "Encontrado: $it" } ?: "Não encontrado no PATH nem em ~/.local/bin."
        specifyFound.foreground = if (specifyPath != null) UIUtil.getContextHelpForeground() else JBColor.RED
    }

    companion object {
        const val DISPLAY_NAME = "Ataque: Spec Kit e Proteções"

        private fun small(text: String = "") = JBLabel("<html>$text</html>").apply {
            componentStyle = UIUtil.ComponentStyle.SMALL
            foreground = UIUtil.getContextHelpForeground()
        }
    }
}
