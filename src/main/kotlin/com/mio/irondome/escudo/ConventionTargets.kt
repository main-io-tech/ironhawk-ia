package com.mio.irondome.escudo

import com.intellij.codeInsight.AnnotationUtil
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiNameIdentifierOwner
import com.mio.irondome.base.IronDomeIcons
import com.mio.irondome.base.DefenseTabs
import com.mio.irondome.base.plural
import com.mio.irondome.interceptor.AgentSettings
import com.mio.irondome.interceptor.FireWithAiAction
import com.mio.irondome.radar.CommentOrigin
import com.mio.irondome.radar.ReviewCommentService

/**
 * Padronizar Testes, sem catálogo de critérios: cada classe de teste sem `@DisplayName` vira um alvo no Radar,
 * marcado como do Escudo, na linha da declaração da classe. A IA corrige as anotações quando a ordem de
 * interceptação é disparada.
 */
object ConventionTargets {

    private const val DISPLAY_NAME = "org.junit.jupiter.api.DisplayName"

    /** O pedido para a IA: o padrão do Escudo, sem chave entre colchetes. */
    fun text(className: String, label: String): String = buildString {
        append("A classe de teste `$className` está sem @DisplayName. ")
        append("Coloque na classe @DisplayName(\"Descrição\"), com a descrição dizendo em português o que ela testa ")
        append("(hoje: \"$label\"). Não use chave entre colchetes como `[Frete]`.")
        append("\n\nNos testes da classe, deixe cada @DisplayName em Dado/Quando/Então, com `->` ou ENTÃO para o resultado esperado. ")
        append("Se o projeto tem catálogo em ${CriteriaActions.FOLDER}/, acrescente @Tag(\"criterio:ID\") e, quando evidente, @Tag(\"nivel:...\"). ")
        append("Cenários de Cucumber ficam nos arquivos .feature, com `@criterio:ID` acima do Cenário.")
        append(" Altere só anotações e @DisplayName, sem mexer na lógica dos testes, e adicione os imports de org.junit.jupiter.api se faltarem.")
    }

    /** Só pela EDT. */
    fun mark(project: Project, classes: List<TestNode>) {
        if (DumbService.isDumb(project)) {
            notify(project, "Aguarde a indexação do projeto terminar para deixar os testes no padrão.", NotificationType.WARNING)
            return
        }
        val service = project.service<ReviewCommentService>()
        var created = 0
        var existing = 0
        val missing = mutableListOf<String>()
        for (testClass in classes) {
            if (testClass.convention == null || testClass.convention == Convention.OK) continue
            val className = testClass.codeReference?.substringAfterLast('.')?.substringAfterLast('$') ?: testClass.label
            val place = testClass.locationUrl?.let { url -> ReadAction.compute<Place?, RuntimeException> { placeOf(project, url) } }
            if (place == null) {
                missing += className
                continue
            }
            val path = service.relativePath(place.file)
            if (path == null) {
                missing += className
                continue
            }
            if (service.hasOpenComment(path, CommentOrigin.ESCUDO, "`$className`")) {
                existing++
                continue
            }
            val comment = service.addComment(
                place.file,
                place.document,
                place.line..place.line,
                text(className, testClass.label),
                discuss = false,
                origin = CommentOrigin.ESCUDO,
            )
            if (comment != null) created++ else missing += className
        }
        report(project, created, existing, missing)
    }

    private class Place(val file: VirtualFile, val document: Document, val line: Int)

    /** A linha do `@DisplayName` da classe, se houver; senão, a do nome da classe. */
    private fun placeOf(project: Project, url: String): Place? {
        val owner = JUnitDisplayNames.resolve(project, url) ?: return null
        val annotation = AnnotationUtil.findAnnotation(owner, DISPLAY_NAME)?.navigationElement
        val declaration = owner.navigationElement
        val anchor: PsiElement = annotation ?: (declaration as? PsiNameIdentifierOwner)?.nameIdentifier ?: declaration
        val file = anchor.containingFile?.virtualFile ?: return null
        val document = FileDocumentManager.getInstance().getDocument(file) ?: return null
        val offset = anchor.textRange?.startOffset ?: return null
        if (offset > document.textLength) return null
        return Place(file, document, document.getLineNumber(offset))
    }

    private fun report(project: Project, created: Int, existing: Int, missing: List<String>) {
        val parts = listOfNotNull(
            plural(created, "alvo marcado", "alvos marcados").takeIf { created > 0 }?.let { "$it no Radar, como do Escudo" },
            plural(existing, "classe já tinha alvo aberto", "classes já tinham alvo aberto").takeIf { existing > 0 },
            missing.takeIf { it.isNotEmpty() }?.let { "não achei no código: ${it.joinToString(", ")}" },
        )
        val type = if (created > 0) NotificationType.INFORMATION else NotificationType.WARNING
        val content = parts.joinToString("; ").replaceFirstChar { it.uppercase() } + "." +
            if (created > 0) " Dispare a ordem de interceptação para a IA deixar o @DisplayName no padrão." else ""
        val actions = buildList {
            add(NotificationAction.createSimpleExpiring("Abrir Radar") { DefenseTabs.showTab(project, DefenseTabs.RADAR) })
            if (created > 0 && AgentSettings.ready) add(NotificationAction.createSimpleExpiring("Disparar com IA") { FireWithAiAction.fire(project) })
        }
        notify(project, content, type, actions)
    }

    private fun notify(project: Project, content: String, type: NotificationType, actions: List<NotificationAction> = emptyList()) {
        NotificationGroupManager.getInstance().getNotificationGroup("Iron Dome IA")
            .createNotification("Escudo: padronizar testes", content, type)
            .setIcon(IronDomeIcons.escudo)
            .apply { actions.forEach(::addAction) }
            .notify(project)
    }
}
