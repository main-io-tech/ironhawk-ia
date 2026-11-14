package com.mio.irondome.interceptor

import com.intellij.openapi.project.Project
import com.mio.irondome.base.DefenseTabs

/** A aba Arsenal no painel do IronHawk IA. */
object InterceptorTab {
    const val TITLE = "Arsenal"

    /** Abre o painel do IronHawk IA na aba Arsenal. */
    fun show(project: Project) {
        DefenseTabs.showTab(project, TITLE)
    }
}
