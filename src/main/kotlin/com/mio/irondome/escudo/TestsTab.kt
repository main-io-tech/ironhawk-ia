package com.mio.irondome.escudo

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.mio.irondome.base.IronDomeIcons
import com.mio.irondome.base.ReviewToolWindowTab
import javax.swing.Icon
import javax.swing.JComponent

class TestsTab : ReviewToolWindowTab {

    override val title: String = TITLE
    override val icon: Icon = IronDomeIcons.escudo
    override val description = "Executa os testes JUnit e mostra o resultado por tag ou por critério de aceite"

    override fun createComponent(project: Project, toolWindow: ToolWindow): JComponent = TestsPanel(project)

    companion object {
        const val TITLE = "Escudo"
    }
}
