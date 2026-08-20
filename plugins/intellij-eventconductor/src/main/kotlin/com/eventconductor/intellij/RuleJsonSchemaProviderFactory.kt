package com.eventconductor.intellij

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.jetbrains.jsonSchema.extension.JsonSchemaFileProvider
import com.jetbrains.jsonSchema.extension.JsonSchemaProviderFactory
import com.jetbrains.jsonSchema.extension.SchemaType

/**
 * Associates the bundled rule JSON schema with every `.ecrule` file (JSON or YAML).
 *
 * There is no visual rule editor, and this does not pretend otherwise: what a `.ecrule` gets is
 * highlighting, completion and validation as YAML against the schema the engine validates it with.
 * That is the whole of what the extension needs to be worth having — before this, `.ecrule` was
 * declared in the engine's shared list and in the Maven plugin's copy of it, and read by nothing.
 */
class RuleJsonSchemaProviderFactory : JsonSchemaProviderFactory {
    override fun getProviders(project: Project): List<JsonSchemaFileProvider> = listOf(RuleSchemaProvider())
}

class RuleSchemaProvider : JsonSchemaFileProvider {

    override fun isAvailable(file: VirtualFile): Boolean =
        file.extension?.equals("ecrule", ignoreCase = true) == true

    override fun getName(): String = "EventConductor rule"

    override fun getSchemaFile(): VirtualFile? =
        JsonSchemaProviderFactory.getResourceFile(RuleSchemaProvider::class.java, "/schema/rule.schema.json")

    override fun getSchemaType(): SchemaType = SchemaType.embeddedSchema
}
