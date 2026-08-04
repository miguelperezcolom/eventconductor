import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.0.20"
    id("org.jetbrains.intellij.platform") version "2.1.0"
}

group = "com.eventconductor"
version = "0.1.2"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        // Community edition is enough; JCEF ships with the platform.
        create(IntelliJPlatformType.IntellijIdeaCommunity, "2024.2.4")
        // JSON language + JSON-schema support (for the .ec schema provider).
        bundledModule("com.intellij.modules.json")
        // YAML support so .ec (parsed as YAML) gets highlighting + schema validation.
        bundledPlugin("org.jetbrains.plugins.yaml")
    }
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "242"
            untilBuild = provider { null }
        }
    }
    // Pure-Kotlin plugin: skip the (Java NotNull) bytecode instrumentation step.
    instrumentCode = false

    // This plugin contributes no settings pages, so the task finds nothing to index and says so
    // ("No searchable options found") on every build. It also starts a headless IDE against the
    // same sandbox, which fails outright while a runIde instance is open — building the plugin
    // should not require closing the IDE you are testing it in.
    buildSearchableOptions = false

    // `./gradlew publishPlugin` — reads secrets from the environment (set by CI).
    publishing {
        token = providers.environmentVariable("PUBLISH_TOKEN")
    }
    // JetBrains Marketplace requires signed plugins for API uploads. Provide a certificate
    // chain + private key (see https://plugins.jetbrains.com/docs/intellij/plugin-signing.html).
    signing {
        certificateChain = providers.environmentVariable("CERTIFICATE_CHAIN")
        privateKey = providers.environmentVariable("PRIVATE_KEY")
        password = providers.environmentVariable("PRIVATE_KEY_PASSWORD")
    }
}

kotlin {
    // IntelliJ 2024.2 targets JDK 21.
    jvmToolchain(21)
}

// Copy the shared graph bundle and the JSON schema from the main repo into plugin resources,
// so the plugin ships the current component. Runs before resources are processed.
val syncBundle by tasks.registering(Copy::class) {
    val repo = rootProject.projectDir.resolve("../..")
    from(repo.resolve("modules/workflow-engine/src/main/resources/META-INF/resources/eventconductor/workflow-graph.js"))
    into(layout.projectDirectory.dir("src/main/resources/webview"))
}
val syncSchema by tasks.registering(Copy::class) {
    val repo = rootProject.projectDir.resolve("../..")
    from(repo.resolve("modules/workflow-engine/src/main/resources/workflow-definition-schema.json")) {
        rename { "ec.schema.json" }
    }
    into(layout.projectDirectory.dir("src/main/resources/schema"))
}

tasks.named("processResources") {
    dependsOn(syncBundle, syncSchema)
}
