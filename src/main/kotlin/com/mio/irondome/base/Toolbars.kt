package com.mio.irondome.base

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.DefaultActionGroup

/**
 * As ações secundárias de uma barra, atrás do ⋮: a barra mostra só as principais, e o resto fica a um clique,
 * com o nome escrito. Um `null` em [actions] vira um separador, para agrupar por assunto.
 */
fun moreActions(vararg actions: AnAction?): DefaultActionGroup = moreActions(actions.toList())

fun moreActions(actions: List<AnAction?>): DefaultActionGroup = DefaultActionGroup("Mais Ações", true).apply {
    templatePresentation.icon = AllIcons.Actions.More
    templatePresentation.description = "Mais ações desta aba"
    actions.forEach { if (it == null) addSeparator() else add(it) }
}
