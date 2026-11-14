package com.mio.irondome.impacto

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.mio.irondome.base.IronDomeIcons
import com.mio.irondome.base.ReviewToolWindowTab
import javax.swing.Icon
import javax.swing.JComponent

class DependencyTab : ReviewToolWindowTab {

    override val title: String = TITLE
    override val icon: Icon = IronDomeIcons.impacto
    override val description = "O diagrama de classes da classe aberta e das que dependem dela ou de que ela depende"

    override fun createComponent(project: Project, toolWindow: ToolWindow): JComponent = DependencyPanel(project, toolWindow)

    companion object {
        const val TITLE = "Raio de Impacto"
    }
}
