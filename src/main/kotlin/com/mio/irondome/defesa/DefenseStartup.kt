package com.mio.irondome.defesa

import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.mio.ironhawk.IronHawkSystem

/** Ao abrir o projeto: o histórico do git já começa a ser lido e os comentários no mesmo trecho passam a ser vigiados. */
class DefenseStartup : ProjectActivity {
    override suspend fun execute(project: Project) {
        if (!IronHawkSystem.DEFENSE.enabled) return
        ChangeHistoryService.getInstance(project).refresh()
        CommentConflictWatcher.getInstance(project)
    }
}
