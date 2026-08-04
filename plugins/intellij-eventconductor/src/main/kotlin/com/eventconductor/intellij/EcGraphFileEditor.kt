package com.eventconductor.intellij

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorLocation
import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.ui.jcef.JBCefBrowserBase
import com.intellij.ui.jcef.JBCefJSQuery
import com.intellij.util.ui.UIUtil
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.handler.CefLoadHandlerAdapter
import java.beans.PropertyChangeListener
import java.nio.charset.StandardCharsets
import java.util.Base64
import javax.swing.JComponent

/**
 * The graph half of the split editor: a JCEF browser hosting the shared workflow-graph web
 * component, kept in sync with the file's document. The web page owns the JSON<->YAML conversion
 * (it has js-yaml), so this class only shuttles the raw file text in and out.
 */
class EcGraphFileEditor(
    private val project: Project,
    private val file: VirtualFile,
) : UserDataHolderBase(), FileEditor {

    private val browser = JBCefBrowser()
    private val document: Document? = FileDocumentManager.getInstance().getDocument(file)
    private val onEdit = JBCefJSQuery.create(browser as JBCefBrowserBase)

    @Volatile private var applyingFromGraph = false
    @Volatile private var pageReady = false

    private val documentListener = object : DocumentListener {
        override fun documentChanged(event: DocumentEvent) {
            if (!applyingFromGraph) pushToBrowser()
        }
    }

    init {
        Disposer.register(this, browser)

        // Graph edit -> document. The payload is the full file text already in the file's format.
        onEdit.addHandler { text ->
            writeToDocument(text)
            null
        }

        browser.jbCefClient.addLoadHandler(object : CefLoadHandlerAdapter() {
            override fun onLoadEnd(b: CefBrowser?, frame: CefFrame?, httpStatusCode: Int) {
                exec("window.__ecOnEdit = function(t){ ${onEdit.inject("t")} };")
                exec("window.__ecSetTheme(${isDarkTheme()});")
                pageReady = true
                pushToBrowser()
            }
        }, browser.cefBrowser)

        document?.addDocumentListener(documentListener, this)
        browser.loadHTML(buildHtml())
    }

    private fun exec(js: String) = browser.cefBrowser.executeJavaScript(js, browser.cefBrowser.url, 0)

    /** Dark vs light UI from the panel background luminance — avoids the removed isUnderDarcula(). */
    private fun isDarkTheme(): Boolean {
        val c = UIUtil.getPanelBackground()
        return (c.red * 0.299 + c.green * 0.587 + c.blue * 0.114) < 128
    }

    private fun pushToBrowser() {
        if (!pageReady) return
        val text = document?.text ?: ""
        val b64 = Base64.getEncoder().encodeToString(text.toByteArray(StandardCharsets.UTF_8))
        exec("window.__ecSetFileB64(\"$b64\");")
    }

    private fun writeToDocument(newText: String) {
        val doc = document ?: return
        ApplicationManager.getApplication().invokeLater {
            if (doc.text == newText) return@invokeLater
            applyingFromGraph = true
            try {
                WriteCommandAction.runWriteCommandAction(project, "Edit EventConductor Graph", null, Runnable {
                    doc.setText(newText)
                })
            } finally {
                applyingFromGraph = false
            }
        }
    }

    private fun buildHtml(): String {
        val jsYaml = readResource("/webview/js-yaml.min.js")
        val bundle = readResource("/webview/workflow-graph.js")
        val main = readResource("/webview/ec-main.js")
        return """
            <!DOCTYPE html><html><head><meta charset="UTF-8"/>
            <style>html,body{height:100%;margin:0}body{display:flex}
            eventconductor-workflow-graph{flex:1 1 auto;min-height:0}</style></head>
            <body>
            <!-- no-expand: this editor pane is already the whole surface the graph can have -->
            <eventconductor-workflow-graph no-expand></eventconductor-workflow-graph>
            <script>$jsYaml</script>
            <script type="module">$bundle</script>
            <script>$main</script>
            </body></html>
        """.trimIndent()
    }

    private fun readResource(path: String): String =
        javaClass.getResourceAsStream(path)?.use { it.readBytes().toString(StandardCharsets.UTF_8) }
            ?: error("Missing plugin resource: $path — run the syncBundle Gradle task")

    override fun getComponent(): JComponent = browser.component
    override fun getPreferredFocusedComponent(): JComponent = browser.component
    override fun getName(): String = "Graph"
    override fun setState(state: FileEditorState) {}
    override fun isModified(): Boolean = false
    override fun isValid(): Boolean = true
    override fun addPropertyChangeListener(listener: PropertyChangeListener) {}
    override fun removePropertyChangeListener(listener: PropertyChangeListener) {}
    override fun getCurrentLocation(): FileEditorLocation? = null
    override fun getFile(): VirtualFile = file

    override fun dispose() {
        Disposer.dispose(onEdit)
    }
}
