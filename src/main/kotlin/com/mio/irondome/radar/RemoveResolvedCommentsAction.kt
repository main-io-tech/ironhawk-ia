package com.mio.irondome.radar

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.components.service
import com.intellij.openapi.project.DumbAwareAction
import com.mio.ironhawk.IronHawkSystem

/**
 * Oculta do editor todos os alvos interceptados, sem alterar o `.md`; [ShowResolvedCommentsAction] traz de volta.
 * Fica no ⋮ da aba Radar.
 */
class RemoveResolvedCommentsAction : DumbAwareAction(
    "Ocultar Alvos Interceptados",
    "Tira do editor os alvos já interceptados, sem alterar o .md",
    AllIcons.Actions.ToggleVisibility,
) {

    override fun getActionUpdateThread() = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        if (!IronHawkSystem.DEFENSE.showIn(e)) return
        val project = e.project
        e.presentation.isEnabled = project != null &&
            project.service<ReviewCommentService>().resolvedComments().isNotEmpty()
    }

    override fun actionPerformed(e: AnActionEvent) {
        val service = e.project?.service<ReviewCommentService>() ?: return
        service.hide(service.resolvedComments())
    }
}

/** Volta a mostrar no editor os alvos interceptados que foram ocultados. Fica no ⋮ da aba Radar. */
class ShowResolvedCommentsAction : DumbAwareAction(
    "Mostrar Alvos Interceptados",
    "Volta a mostrar no editor os alvos interceptados ocultados",
    AllIcons.Actions.Show,
) {

    override fun getActionUpdateThread() = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        if (!IronHawkSystem.DEFENSE.showIn(e)) return
        val project = e.project
        e.presentation.isEnabled = project != null &&
            project.service<ReviewCommentService>().hiddenComments().isNotEmpty()
    }

    override fun actionPerformed(e: AnActionEvent) {
        val service = e.project?.service<ReviewCommentService>() ?: return
        service.show(service.hiddenComments())
    }
}
