package com.mio.irondome.radar

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.components.service
import com.intellij.openapi.project.DumbAwareAction
import com.mio.ironhawk.IronHawkSystem

/**
 * Cancelar Alvo do Trecho: remove os comentários não resolvidos que cruzam a seleção ou a linha do cursor. Os ainda
 * não salvos saem na hora, os demais ficam marcados até o próximo salvamento. No menu do editor, só aparece onde há alvo.
 */
class RemoveReviewCommentAction : DumbAwareAction() {

    override fun getActionUpdateThread() = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        if (!IronHawkSystem.DEFENSE.showIn(e)) return
        val project = e.project
        val editor = e.editorAndFile()?.first
        e.presentation.isEnabledAndVisible = project != null && editor != null &&
            project.service<ReviewCommentService>().commentsAt(editor.document, editor.selectedLines())
                .any { !it.resolved }
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val (editor, _) = e.editorAndFile() ?: return
        val service = project.service<ReviewCommentService>()
        service.markForRemoval(service.commentsAt(editor.document, editor.selectedLines()))
    }
}
