package com.eventconductor.intellij

import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorPolicy
import com.intellij.openapi.fileEditor.FileEditorProvider
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.fileEditor.TextEditorWithPreview
import com.intellij.openapi.fileEditor.impl.text.TextEditorProvider
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile

/**
 * Opens `.ec` files in a split editor: the text (YAML/JSON) on one side and the interactive
 * EventConductor graph on the other. The toolbar's editor/split/preview toggles give the
 * "edit as text or as graph" experience for free, and both sides share the same document.
 */
class EcFileEditorProvider : FileEditorProvider, DumbAware {

    override fun accept(project: Project, file: VirtualFile): Boolean =
        file.extension?.equals("ec", ignoreCase = true) == true

    override fun createEditor(project: Project, file: VirtualFile): FileEditor {
        val textEditor = TextEditorProvider.getInstance().createEditor(project, file) as TextEditor
        val graphEditor = EcGraphFileEditor(project, file)
        return TextEditorWithPreview(
            textEditor,
            graphEditor,
            "EventConductor",
            TextEditorWithPreview.Layout.SHOW_EDITOR_AND_PREVIEW,
        )
    }

    override fun getEditorTypeId(): String = "eventconductor-ec-editor"

    override fun getPolicy(): FileEditorPolicy = FileEditorPolicy.HIDE_DEFAULT_EDITOR
}
