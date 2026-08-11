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
 * Opens `.ecform` files in a split editor: the text (YAML/JSON) on one side and the interactive
 * EventConductor form editor on the other. The toolbar's editor/split/preview toggles give the
 * "edit as text or visually" experience for free, and both sides share the same document. Mirrors
 * {@link EcFileEditorProvider}; the phase-1 text-side schema validation
 * ({@link FormJsonSchemaProviderFactory}) keeps working on the text half.
 */
class FormFileEditorProvider : FileEditorProvider, DumbAware {

    override fun accept(project: Project, file: VirtualFile): Boolean =
        file.extension?.equals("ecform", ignoreCase = true) == true

    override fun createEditor(project: Project, file: VirtualFile): FileEditor {
        val textEditor = TextEditorProvider.getInstance().createEditor(project, file) as TextEditor
        val formEditor = FormGraphFileEditor(project, file)
        return TextEditorWithPreview(
            textEditor,
            formEditor,
            "EventConductor Form",
            TextEditorWithPreview.Layout.SHOW_EDITOR_AND_PREVIEW,
        )
    }

    override fun getEditorTypeId(): String = "eventconductor-form-editor"

    override fun getPolicy(): FileEditorPolicy = FileEditorPolicy.HIDE_DEFAULT_EDITOR
}
