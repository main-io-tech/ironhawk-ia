package com.mio.irondome.patrulha

import com.intellij.execution.runners.ExecutionUtil
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.Toggleable
import com.intellij.openapi.project.DumbAwareAction
import com.mio.ironhawk.IronHawkSystem

/**
 * As ações do editor sobre os comentários do PR em revisão: os não enviados, os pedidos enviados e as conversas.
 * Aparecem na barra do canto do editor e no menu do botão direito, só durante a revisão de um PR.
 */
abstract class PullRequestEditorAction : DumbAwareAction() {

    override fun getActionUpdateThread() = ActionUpdateThread.EDT

    final override fun update(e: AnActionEvent) {
        if (!IronHawkSystem.DEFENSE.showIn(e)) return
        val patrulha = e.project?.let(PatrulhaService::getInstance)
        val number = patrulha?.reviewing() ?: 0
        e.presentation.isEnabledAndVisible = number != 0
        if (number != 0) update(e, patrulha!!, number)
    }

    final override fun actionPerformed(e: AnActionEvent) {
        val patrulha = e.project?.let(PatrulhaService::getInstance) ?: return
        val number = patrulha.reviewing().takeIf { it != 0 } ?: return
        perform(patrulha, number)
    }

    protected abstract fun update(e: AnActionEvent, patrulha: PatrulhaService, number: Int)

    protected abstract fun perform(patrulha: PatrulhaService, number: Int)
}

/** Minimiza todos os cartões do PR numa barra de uma linha, ou expande todos quando já estão minimizados. */
class MinimizePullRequestCommentsAction : PullRequestEditorAction() {

    override fun update(e: AnActionEvent, patrulha: PatrulhaService, number: Int) {
        val minimize = patrulha.hasExpandedInEditor(number)
        e.presentation.text = if (minimize) "Minimizar Comentários do PR" else "Expandir Comentários do PR"
        e.presentation.description = if (minimize) {
            "Recolhe os comentários do PR #$number no editor; o cabeçalho de cada um abre só ele"
        } else {
            "Abre todos os comentários do PR #$number no editor"
        }
        e.presentation.icon = if (minimize) AllIcons.Actions.Collapseall else AllIcons.Actions.Expandall
    }

    override fun perform(patrulha: PatrulhaService, number: Int) =
        patrulha.setAllExpandedInEditor(number, !patrulha.hasExpandedInEditor(number))
}

/**
 * Tira do editor todos os comentários do PR, ou traz de volta, inclusive os ocultados um a um pelo Ocultar do
 * cartão; pressionado enquanto eles aparecem.
 */
class TogglePullRequestCommentsAction : PullRequestEditorAction(), Toggleable {

    override fun update(e: AnActionEvent, patrulha: PatrulhaService, number: Int) {
        val shown = patrulha.isAnyShownInEditor(number)
        Toggleable.setSelected(e.presentation, shown)
        e.presentation.text = if (shown) "Ocultar Comentários do PR" else "Mostrar Comentários do PR"
        e.presentation.description = if (shown) {
            "Tira do editor os comentários do PR #$number, sem apagar nenhum"
        } else {
            "Mostra no editor os comentários do PR #$number, inclusive os ocultados um a um"
        }
        e.presentation.icon = if (shown) ExecutionUtil.getLiveIndicator(AllIcons.Actions.Show) else AllIcons.Actions.Show
    }

    override fun perform(patrulha: PatrulhaService, number: Int) =
        patrulha.setAllShownInEditor(number, !patrulha.isAnyShownInEditor(number))
}
