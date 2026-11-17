package com.mio.irondome.interceptor

import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DataKey
import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.openapi.components.service
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.mio.irondome.base.IronDomeIcons
import com.mio.irondome.base.DefenseTabs
import com.mio.irondome.base.plural
import com.mio.irondome.base.rootPath
import com.mio.irondome.radar.DiscussionService
import com.mio.irondome.radar.ReviewComment
import com.mio.irondome.radar.ReviewCommentService
import java.awt.datatransfer.StringSelection
import java.nio.file.Path
import com.mio.ironhawk.IronHawkSystem

/**
 * Disparar com IA, o único disparo da Defesa: grava no `.md` a ordem de interceptação e pede à IA que a execute. Ela
 * corrige o código de cada alvo e marca `[x]` no `.md`, o que o Radar mostra como interceptado.
 *
 * Age nos alvos selecionados na aba Radar ([RADAR_SELECTION]); sem seleção, em todos os ainda não interceptados nem
 * cancelados. Sem a IA configurada, grava o `.md` e copia o pedido para o usuário colar na IA que preferir. Os alvos
 * de "discutir antes de alterar", com a IA pronta, abrem as perguntas no painel do Radar.
 */
class FireWithAiAction : DumbAwareAction() {

    override fun getActionUpdateThread() = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        if (!IronHawkSystem.DEFENSE.showIn(e)) return
        val project = e.project
        if (project == null) {
            e.presentation.isEnabledAndVisible = false
            return
        }
        val scope = scopeOf(e, project)
        val runner = AgentRunner.getInstance(project)
        val busy = runner.running != null
        val reviewing = runner.reviewingPullRequest()
        val count = scope.targets.size
        // Na barra do editor, só aparece com o que disparar; no Radar fica sempre à vista, desabilitado quando não há.
        e.presentation.isVisible = scope.alwaysVisible || count > 0 || busy
        e.presentation.isEnabled = count > 0 && !busy && reviewing == 0
        e.presentation.text = when {
            busy -> "IA Trabalhando…"
            scope.selected && count == 1 -> "Disparar ${scope.targets.single().id} com IA"
            scope.selected -> "Disparar $count Selecionados com IA"
            count > 0 -> "Disparar com IA (${plural(count, "alvo", "alvos")})"
            else -> TEXT
        }
        e.presentation.description = when {
            reviewing != 0 -> reviewingHint(reviewing)
            busy -> "A IA já está trabalhando"
            count == 0 -> "Nenhum alvo pendente para disparar"
            AgentSettings.ready -> "Grava o .md e pede à IA (${AgentSettings.provider.title}) que corrija ${what(scope)}"
            else -> "Sem IA configurada: grava o .md e copia o pedido para colar na sua IA"
        }
        e.presentation.putClientProperty(ActionUtil.SHOW_TEXT_IN_TOOLBAR, true)
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val scope = scopeOf(e, project)
        fireTargets(project, scope.targets, all = !scope.selected)
    }

    /**
     * O que um disparo agora levaria: [targets], e se são os selecionados no Radar ou todos os pendentes. Com
     * [alwaysVisible], na barra do Radar, o botão fica à vista mesmo sem alvo; no editor e no menu, só com alvo.
     */
    private class Scope(val targets: List<ReviewComment>, val selected: Boolean, val alwaysVisible: Boolean)

    private fun scopeOf(e: AnActionEvent, project: Project): Scope {
        val selection = e.getData(RADAR_SELECTION)
        val chosen = selection?.let(::fireable).orEmpty()
        if (chosen.isNotEmpty()) return Scope(chosen, selected = true, alwaysVisible = true)
        // No menu de um item do Radar sem alvo pendente, não vale cair em todos: o menu fala só do item.
        if (selection != null && e.isFromContextMenu) return Scope(emptyList(), selected = true, alwaysVisible = false)
        val all = fireable(project.service<ReviewCommentService>().activeComments())
        return Scope(all, selected = false, alwaysVisible = selection != null)
    }

    private fun what(scope: Scope): String = when {
        scope.selected && scope.targets.size == 1 -> "o alvo ${scope.targets.single().id}"
        scope.selected -> "os ${scope.targets.size} alvos selecionados"
        else -> "os alvos pendentes"
    }

    companion object {
        const val TEXT = "Disparar com IA"

        /**
         * Os alvos selecionados na aba Radar, com os de baixo de arquivos e revisões selecionados; vazio sem seleção.
         * Só a árvore do Radar fornece: fora dela, o disparo vale para todos os pendentes.
         */
        val RADAR_SELECTION: DataKey<List<ReviewComment>> = DataKey.create("IronDome.RadarSelection")

        /** Dica dos botões de IA desligados. */
        const val NOT_READY = "Configure e teste a IA na aba Arsenal para habilitar"

        /** Dica dos botões de disparar com um PR em revisão. */
        fun reviewingHint(number: Int) =
            "Revisando o PR #$number: a IA não altera o código. Saia da revisão na aba Patrulha para disparar"

        /** Dispara todos os alvos pendentes da revisão aberta, como o botão sem seleção. */
        fun fire(project: Project) {
            fireTargets(project, project.service<ReviewCommentService>().activeComments(), all = true)
        }

        /** Os alvos que podem ser disparados: da revisão aberta do Radar, ainda não interceptados nem cancelados. */
        fun fireable(comments: Collection<ReviewComment>): List<ReviewComment> =
            comments.filter { it.pullRequest == 0 && it.reviewId != 0 && !it.resolved && !it.removing }.distinct()

        /**
         * Dispara [comments]: grava o `.md` e pede à IA que execute esses alvos. Com [all], o disparo é o da revisão
         * inteira: grava os alvos de todos os arquivos, inclusive os cancelados, e a IA executa a ordem toda. Sem a
         * IA configurada, copia o pedido para colar no assistente que o usuário preferir.
         */
        fun fireTargets(project: Project, comments: Collection<ReviewComment>, all: Boolean = false) {
            val fireable = fireable(comments).sortedWith(ReviewComment.ORDER)
            if (fireable.isEmpty()) return
            val runner = AgentRunner.getInstance(project)
            // Com a IA pronta, "discutir antes de alterar" tem as perguntas no painel do Radar; sem ela, vale o pedido copiado.
            val (discuss, targets) = if (runner.available()) fireable.partition { it.discuss } else emptyList<ReviewComment>() to fireable
            if (targets.isEmpty()) return project.service<DiscussionService>().start(discuss)
            if (runner.refusesWrite()) return
            val service = project.service<ReviewCommentService>()
            val written = (if (all) service.exportAll().firstOrNull() else service.exportPaths(targets.map { it.filePath })) ?: return
            val relative = relativeTo(project.rootPath(), written)
            // A ordem inteira, sem alvos de discussão separados: a IA segue o arquivo todo.
            val whole = all && discuss.isEmpty()
            val ids = targets.joinToString(", ") { it.id }
            val what = if (targets.size == 1) "o alvo ${targets.single().id}" else "os alvos $ids"
            val prompt = if (whole) {
                "Execute a ordem de interceptação em $relative: siga as instruções do arquivo, corrija o código de cada alvo " +
                    "e marque [x] os que forem atendidos. Nos alvos marcados para discutir antes, só apresente a análise e a proposta, sem alterar."
            } else {
                "Execute somente $what da ordem de interceptação em $relative, seguindo as instruções do arquivo: corrija o " +
                    "código e marque [x] nos atendidos. Não mexa nos outros alvos. Nos alvos marcados para discutir antes, só apresente a " +
                    "análise e a proposta, sem alterar."
            }
            if (!runner.available()) {
                CopyPasteManager.getInstance().setContents(StringSelection(prompt))
                NotificationGroupManager.getInstance().getNotificationGroup("Iron Dome IA")
                    .createNotification(
                        "${if (targets.size == 1) "Alvo ${targets.single().id} disparado" else "${targets.size} alvos disparados"}: pedido copiado",
                        "Sem IA configurada. Cole o pedido na sua IA; os alvos estão em ${written.fileName}.",
                        NotificationType.INFORMATION,
                    )
                    .setIcon(IronDomeIcons.radar)
                    .addAction(NotificationAction.createSimple("Abrir ${written.fileName}") { runner.open(written) })
                    .notify(project)
                return
            }
            runner.run(
                AgentTask(
                    when {
                        whole -> "Disparar ${written.fileName}"
                        targets.size == 1 -> "Disparar ${targets.single().id}"
                        else -> "Disparar $ids"
                    },
                    prompt,
                    write = true,
                    origin = DefenseTabs.RADAR,
                ),
                "Abrir ${written.fileName}" to { _ -> runner.open(written) },
            ) { if (discuss.isNotEmpty()) project.service<DiscussionService>().start(discuss) }
        }

        private fun relativeTo(base: String?, file: Path): String =
            base?.let { runCatching { Path.of(it).relativize(file).toString().replace('\\', '/') }.getOrNull() } ?: file.toString()
    }
}
