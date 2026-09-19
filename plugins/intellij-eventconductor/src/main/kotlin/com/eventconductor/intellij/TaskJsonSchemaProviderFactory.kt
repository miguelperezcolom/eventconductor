package com.eventconductor.intellij

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.jetbrains.jsonSchema.extension.JsonSchemaFileProvider
import com.jetbrains.jsonSchema.extension.JsonSchemaProviderFactory
import com.jetbrains.jsonSchema.extension.SchemaType

/**
 * Associates the bundled task-contract JSON schema with every `.ectask` file (JSON or YAML).
 *
 * A `.ectask` gets highlighting, completion and validation as YAML against the very schema the
 * engine and the Maven plugin validate it with — the type vocabulary, the required fields, the
 * error-code shape — so a contract is authored with the same guardrails everywhere.
 */
class TaskJsonSchemaProviderFactory : JsonSchemaProviderFactory {
    override fun getProviders(project: Project): List<JsonSchemaFileProvider> = listOf(TaskSchemaProvider())
}

class TaskSchemaProvider : JsonSchemaFileProvider {

    override fun isAvailable(file: VirtualFile): Boolean =
        file.extension?.equals("ectask", ignoreCase = true) == true

    override fun getName(): String = "EventConductor task contract"

    override fun getSchemaFile(): VirtualFile? =
        JsonSchemaProviderFactory.getResourceFile(TaskSchemaProvider::class.java, "/schema/task.schema.json")

    override fun getSchemaType(): SchemaType = SchemaType.embeddedSchema
}
