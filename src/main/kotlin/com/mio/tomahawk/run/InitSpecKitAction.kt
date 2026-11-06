package com.mio.tomahawk.run

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction
import com.mio.ironhawk.IronHawkSystem

class InitSpecKitAction : DumbAwareAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        MissionRunner.getInstance(project).initSpecKit()
    }

    override fun update(e: AnActionEvent) {
        if (!IronHawkSystem.ATTACK.showIn(e)) return
        e.presentation.isEnabled = e.project?.basePath != null
    }

    override fun getActionUpdateThread() = ActionUpdateThread.BGT
}
