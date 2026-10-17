package com.eventconductor.intellij

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.jetbrains.jsonSchema.extension.JsonSchemaFileProvider
import com.jetbrains.jsonSchema.extension.JsonSchemaProviderFactory
import com.jetbrains.jsonSchema.extension.SchemaType

/**
 * Associates the bundled events schema with `events.yaml` / `events.yml` — the event-trigger file that
 * sits next to the workflow definitions (a list of `{id?, destination, type, workflow, filter?,
 * businessKey?, variables?}`), validated by the engine and the Maven plugin against the very same schema.
 */
class EventsJsonSchemaProviderFactory : JsonSchemaProviderFactory {
    override fun getProviders(project: Project): List<JsonSchemaFileProvider> = listOf(EventsSchemaProvider())
}

class EventsSchemaProvider : JsonSchemaFileProvider {

    override fun isAvailable(file: VirtualFile): Boolean =
        file.name.equals("events.yaml", ignoreCase = true) || file.name.equals("events.yml", ignoreCase = true)

    override fun getName(): String = "EventConductor event triggers (events.yaml)"

    override fun getSchemaFile(): VirtualFile? =
        JsonSchemaProviderFactory.getResourceFile(EventsSchemaProvider::class.java, "/schema/events.schema.json")

    override fun getSchemaType(): SchemaType = SchemaType.embeddedSchema
}
