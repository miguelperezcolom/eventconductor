package com.eventconductor.intellij

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.jetbrains.jsonSchema.extension.JsonSchemaFileProvider
import com.jetbrains.jsonSchema.extension.JsonSchemaProviderFactory
import com.jetbrains.jsonSchema.extension.SchemaType

/** Associates the bundled form JSON schema with every `.ecform` file (JSON or YAML). */
class FormJsonSchemaProviderFactory : JsonSchemaProviderFactory {
    override fun getProviders(project: Project): List<JsonSchemaFileProvider> = listOf(FormSchemaProvider())
}

class FormSchemaProvider : JsonSchemaFileProvider {

    override fun isAvailable(file: VirtualFile): Boolean =
        file.extension?.equals("ecform", ignoreCase = true) == true

    override fun getName(): String = "EventConductor Form"

    override fun getSchemaFile(): VirtualFile? =
        JsonSchemaProviderFactory.getResourceFile(FormSchemaProvider::class.java, "/schema/form.schema.json")

    override fun getSchemaType(): SchemaType = SchemaType.embeddedSchema
}
