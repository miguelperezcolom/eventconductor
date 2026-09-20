package com.eventconductor.intellij

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import io.mateu.workflow.codegen.ProjectGenerator
import io.mateu.workflow.codegen.ProjectSpec
import org.yaml.snakeyaml.Yaml

/**
 * Shared logic for the "task project" actions. It reuses `worker-codegen` — the single source of the
 * project templates and source generation (decision 16) — so the IDE writes exactly what the Maven
 * goal and the UI produce. Everything here is small on purpose: the actions parse the `.ectask`
 * under the cursor, ask for a couple of names, and let `ProjectGenerator` decide the files.
 */
internal object TaskProjectScaffolder {

    private val mapper = ObjectMapper()

    /** Parse a `.ectask` (YAML or its JSON subset) into the tree `ProjectGenerator` expects. */
    fun parseContract(text: String): JsonNode {
        val trimmed = text.trimStart()
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            return mapper.readTree(text)
        }
        val loaded = Yaml().load<Any>(text)
        return mapper.valueToTree(loaded)
    }

    fun group(contract: JsonNode): String = contract.get("group")?.asText() ?: "tasks"

    fun spec(contract: JsonNode, variant: ProjectSpec.Variant, artifactId: String, basePackage: String): ProjectSpec {
        val service = variant == ProjectSpec.Variant.TASK_SERVICE
        return ProjectSpec(
            variant,
            "com.example",
            artifactId,
            "0.1.0-SNAPSHOT",
            "1.0-SNAPSHOT",
            null, null, null,          // definitions / repository / ref — the contracts are local here
            group(contract),
            null,
            basePackage,
            listOf(contract),
            service,                   // wrapper only for a standalone service
            service,                   // .gitignore only for a standalone service
        )
    }

    /** The generated `<group>-tasks` / `<group>-service` artifact for a group. */
    fun artifactId(group: String, variant: ProjectSpec.Variant): String =
        group + if (variant == ProjectSpec.Variant.TASK_SERVICE) "-service" else "-tasks"

    /**
     * Write the generated project under [targetDir], plus the contract itself into
     * `src/main/resources/tasks/` so the project builds on its own (the goal reads it locally).
     */
    fun write(project: Project, targetDir: VirtualFile, generated: io.mateu.workflow.codegen.GeneratedProject,
              contractFileName: String, contractText: String) {
        WriteCommandAction.runWriteCommandAction(project) {
            generated.files().forEach { file ->
                writeFile(targetDir, file.path(), file.content())
            }
            writeFile(targetDir, "src/main/resources/tasks/$contractFileName", contractText)
        }
    }

    private fun writeFile(root: VirtualFile, relativePath: String, content: String) {
        val segments = relativePath.split('/')
        var dir = root
        for (i in 0 until segments.size - 1) {
            dir = dir.findChild(segments[i]) ?: dir.createChildDirectory(this, segments[i])
        }
        val name = segments.last()
        val file = dir.findChild(name) ?: dir.createChildData(this, name)
        VfsUtil.saveText(file, content)
    }

    /** Add a `<module>` entry to the reactor pom at the workspace root, if there is one. */
    fun addModuleToReactor(project: Project, moduleDirName: String) {
        val base = project.baseDir ?: return
        val pom = base.findChild("pom.xml") ?: return
        val text = VfsUtil.loadText(pom)
        if (!text.contains("<modules>") || text.contains("<module>$moduleDirName</module>")) {
            return
        }
        val updated = text.replaceFirst("</modules>", "    <module>$moduleDirName</module>\n  </modules>")
        WriteCommandAction.runWriteCommandAction(project) { VfsUtil.saveText(pom, updated) }
    }

    /** Insert a dependency on the group's task module into the nearest pom.xml above [from]. */
    fun addDependency(project: Project, from: VirtualFile, group: String) {
        var dir: VirtualFile? = if (from.isDirectory) from else from.parent
        var pom: VirtualFile? = null
        while (dir != null) {
            val candidate = dir.findChild("pom.xml")
            if (candidate != null) { pom = candidate; break }
            dir = dir.parent
        }
        if (pom == null) {
            Messages.showErrorDialog(project, "No pom.xml found above ${from.name}.", "Add Task Dependency")
            return
        }
        val artifact = artifactId(group, ProjectSpec.Variant.TASK_MODULE)
        val dependency = """
            |        <dependency>
            |            <groupId>com.example</groupId>
            |            <artifactId>$artifact</artifactId>
            |            <version>0.1.0-SNAPSHOT</version>
            |        </dependency>
        """.trimMargin()
        val text = VfsUtil.loadText(pom)
        if (text.contains("<artifactId>$artifact</artifactId>")) {
            return // already present
        }
        val updated = if (text.contains("</dependencies>")) {
            text.replaceFirst("</dependencies>", "$dependency\n    </dependencies>")
        } else {
            text.replaceFirst("</project>", "    <dependencies>\n$dependency\n    </dependencies>\n</project>")
        }
        val target = pom
        WriteCommandAction.runWriteCommandAction(project) { VfsUtil.saveText(target, updated) }
    }
}

/** Base for the two "create project" actions; each only differs by variant and labels. */
internal abstract class CreateTaskProjectAction(
    private val variant: ProjectSpec.Variant,
) : AnAction() {

    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        val file = event.getData(CommonDataKeys.VIRTUAL_FILE) ?: return
        if (file.extension?.equals("ectask", ignoreCase = true) != true) {
            Messages.showErrorDialog(project, "Run this on a .ectask task contract.", templatePresentation.text)
            return
        }
        val contractText = VfsUtil.loadText(file)
        val contract = TaskProjectScaffolder.parseContract(contractText)
        val group = TaskProjectScaffolder.group(contract)

        val defaultArtifact = TaskProjectScaffolder.artifactId(group, variant)
        val artifactId = Messages.showInputDialog(
            project, "Artifact id", templatePresentation.text, null, defaultArtifact, null
        )?.takeIf { it.isNotBlank() } ?: return
        val basePackage = Messages.showInputDialog(
            project, "Base package", templatePresentation.text, null, "com.example", null
        )?.takeIf { it.isNotBlank() } ?: return

        val spec = TaskProjectScaffolder.spec(contract, variant, artifactId, basePackage)
        val generated = ProjectGenerator().generate(spec)

        ApplicationManager.getApplication().invokeLater {
            WriteCommandAction.runWriteCommandAction(project) {
                val base = project.baseDir ?: file.parent
                val moduleDir = base.findChild(artifactId) ?: base.createChildDirectory(this, artifactId)
                TaskProjectScaffolder.write(project, moduleDir, generated, file.name, contractText)
                TaskProjectScaffolder.addModuleToReactor(project, artifactId)
            }
            Messages.showInfoMessage(project, "Created $artifactId.", templatePresentation.text)
        }
    }

    override fun update(event: AnActionEvent) {
        val file = event.getData(CommonDataKeys.VIRTUAL_FILE)
        event.presentation.isEnabledAndVisible = file?.extension?.equals("ectask", ignoreCase = true) == true
    }
}

/** Scaffold a task-module project (a library added to an existing Spring Boot service). */
class CreateTaskModuleAction : CreateTaskProjectAction(ProjectSpec.Variant.TASK_MODULE)

/** Scaffold a standalone task-service project (a runnable Kafka worker). */
class CreateTaskServiceAction : CreateTaskProjectAction(ProjectSpec.Variant.TASK_SERVICE)

/** Add a dependency on the current task's `<group>-tasks` module to the nearest pom. */
class AddTaskDependencyAction : AnAction() {

    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        val file = event.getData(CommonDataKeys.VIRTUAL_FILE) ?: return
        val group = if (file.extension?.equals("ectask", ignoreCase = true) == true) {
            TaskProjectScaffolder.group(TaskProjectScaffolder.parseContract(VfsUtil.loadText(file)))
        } else {
            Messages.showInputDialog(project, "Task group", templatePresentation.text, null, "", null)
                ?.takeIf { it.isNotBlank() } ?: return
        }
        TaskProjectScaffolder.addDependency(project, file, group)
    }

    override fun update(event: AnActionEvent) {
        val file = event.getData(CommonDataKeys.VIRTUAL_FILE)
        event.presentation.isEnabledAndVisible = file != null
    }
}
