package com.mio.tomahawk.delivery.github

import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.mio.ironhawk.IronHawkSystem
import com.mio.tomahawk.delivery.PrRadioService

/** Liga o Rádio ao abrir o projeto, para os comentários dos PRs chegarem mesmo com a aba fechada; registrada em `ironhawk-github.xml`. */
class RadioStartup : ProjectActivity {
    override suspend fun execute(project: Project) {
        if (!IronHawkSystem.ATTACK.enabled) return
        PrRadioService.getInstance(project).start()
    }
}
