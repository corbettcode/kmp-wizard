package kmpwizard.generator

// ---------- core: shared by clients and server. Each slice's domain + data, no UI ----------

fun coreModule(
    c: ProjectContext
    , f: MutableMap<String, String>
) {
    val deps = linkedMapOf<String, MutableList<String>>()

    fun add(
        set: String
        , d: String
    ) = deps.getOrPut(set) {
        mutableListOf()
    }
        .add(d)

    add("commonMain", "api(libs.coroutines.core)")
    add("commonTest", "implementation(kotlin(\"test\"))")
    add("commonTest", "implementation(libs.coroutines.test)")

    c.activeLibs.forEach { l ->
        l.arts.forEach { a ->
            a.sets.filter(c::setActive).forEach { add(it, "${if (a.api) "api" else "implementation"}(libs.${a.alias.replace('-', '.')})") }
        }
    }
    if (c.todo) {
        add("commonMain", "implementation(libs.ktor.client.content.negotiation)")
        add("commonMain", "implementation(libs.ktor.serialization.kotlinx.json)")
    }
    f["core/build.gradle.kts"] = kmpBuildScript(
        c, namespace = "${c.pkg}.core", compose = false, jvm = c.hasJvm, iosFramework = null,
        plugins = c.corePlugins, deps = deps, junit = c.lib("kotest"),
    )

    val base = "core/src/commonMain/kotlin/${c.dir}"
    f["$base/core/logging/AppLogger.kt"] = if (c.lib("kermit")) """
            package ${c.pkg}.core.logging

            import co.touchlab.kermit.Logger

            object AppLogger {
                fun d(message: () -> String) = Logger.withTag("App").d { message() }
            }
        """.trimIndent() else """
            package ${c.pkg}.core.logging

            object AppLogger {
                fun d(message: () -> String) = println(message())
            }
        """.trimIndent()
    if (c.lib("arrow")) f["$base/core/DomainError.kt"] = """
            package ${c.pkg}.core

            sealed interface DomainError {
                val message: String
                data class Unexpected(override val message: String) : DomainError
            }
        """.trimIndent().let { if (c.todo) it.replace("    data class Unexpected", "    data class Invalid(override val message: String) : DomainError\n    data class NotFound(override val message: String) : DomainError\n    data class Unexpected") else it }
}
