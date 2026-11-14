package com.mio.irondome.caixapreta

import com.intellij.openapi.options.Configurable
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.UIUtil
import javax.swing.JComponent

/** Settings > Tools > Iron Dome IA: a página-mãe das configurações do plugin, com as de cada mecanismo dentro dela. */
class IronDomeConfigurable : Configurable {

    override fun getDisplayName() = "Iron Dome IA"

    override fun createComponent(): JComponent = FormBuilder.createFormBuilder()
        .addComponent(
            JBLabel(
                "<html>As configurações do plugin ficam nas páginas abaixo desta. Os bugs do plugin são reportados como issues em " +
                    "${BugGitHub.REPOSITORY.fullName}, com a conta do GitHub que você já tem no IntelliJ " +
                    "(Settings &gt; Version Control &gt; GitHub).</html>",
            ).apply { foreground = UIUtil.getContextHelpForeground() },
        )
        .addComponentFillVertically(javax.swing.JPanel(), 0)
        .panel

    override fun isModified() = false

    override fun apply() = Unit
}
