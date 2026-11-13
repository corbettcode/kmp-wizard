package kmpwizard.generator

// ---------- core: shared kernel for every slice (logging, errors, server URL). Slices live in feature/<slice>/ -------

fun coreModule(
    c: ProjectContext
    , f: MutableMap<String, String>
) {
    val deps = linkedMapOf<String, MutableList<String>>()

    fun add(
        set: String
        , d: String
    ) = deps
        .getOrPut(set) {
            mutableListOf()
        }
        .add(d)

    add("commonMain", "api(libs.coroutines.core)")
    add("commonTest", "implementation(kotlin(\"test\"))")
    add("commonTest", "implementation(libs.coroutines.test)")

    c.libraryDependencies()
        .forEach { (set, d) ->
            add(set, d)
        }
    f["core/build.gradle.kts"] = kmpBuildScript(
        c
        , namespace = "${c.pkg}.core"
        , hasCompose = false
        , hasJvm = c.hasJvm
        , iosFramework = null
        , plugins = c.corePlugins
        , deps = deps
        , usesJUnit = c.lib("kotest")
    )

    val base = "core/src/commonMain/kotlin/${c.dir}"
    f["$base/core/logging/AppLogger.kt"] = if (c.lib("kermit")) """
            package ${c.pkg}.core.logging

            import co.touchlab.kermit.Logger

            object AppLogger {
                fun d(
                    message: () -> String
                ) = Logger
                    .withTag("App")
                    .d {
                        message()
                    }
            }
        """.trimIndent() else """
            package ${c.pkg}.core.logging

            object AppLogger {
                fun d(
                    message: () -> String
                ) = println(message())
            }
        """.trimIndent()
    // The Todo sample's use cases also report invalid input and unknown ids.
    val errorCases = if (c.hasTodo) listOf("Invalid", "NotFound", "Unexpected") else listOf("Unexpected")
    if (c.lib("arrow")) f["$base/core/DomainError.kt"] = buildString {
        appendLine("package ${c.pkg}.core\n")
        appendLine("sealed interface DomainError {")
        appendLine("    val message: String")
        errorCases.forEach {
            appendLine()
            appendLine("    data class $it(")
            appendLine("        override val message: String")
            appendLine("    ) : DomainError")
        }
        append("}")
    }
}
