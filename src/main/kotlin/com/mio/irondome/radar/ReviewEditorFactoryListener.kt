package com.mio.irondome.radar

import com.intellij.openapi.components.service
import com.mio.irondome.base.IdeSupport
import com.intellij.openapi.editor.event.EditorFactoryEvent
import com.intellij.openapi.editor.event.EditorFactoryListener
import com.mio.ironhawk.IronHawkSystem

class ReviewEditorFactoryListener : EditorFactoryListener {

    override fun editorCreated(event: EditorFactoryEvent) {
        val project = event.editor.project ?: return
        if (project.isDisposed || IdeSupport.isLimited(RADAR) || !IronHawkSystem.DEFENSE.enabled) return
        project.service<ReviewCommentService>().render(event.editor)
    }
}
