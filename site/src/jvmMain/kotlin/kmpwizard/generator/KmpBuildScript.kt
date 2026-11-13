package kmpwizard.generator

import kmpwizard.Target

// ---------- KMP library build script (core, coreUI, slice modules and app/sharedUI) ----------

/**
 * Gives a module a unique Maven group and Kotlin module / archive [name], for modules whose project
 * name repeats in the build (every slice has `shared`, `client` and `server`).
 */
class ModuleIdentity(
    val group: String
    , val name: String
) {
    val comment = "// Every slice has shared/client/server modules: " +
        "a unique group and name keep their jars and Kotlin modules apart."
}

fun kmpBuildScript(
    c: ProjectContext
    , namespace: String
    , hasCompose: Boolean
    , hasJvm: Boolean
    , iosFramework: String?
    , plugins: List<String>
    , deps: Map<String, List<String>>
    , usesJUnit: Boolean = false
    , identity: ModuleIdentity? = null
) = buildString {
    if (c.has(kmpwizard.Target.ANDROID)) appendLine("import org.jetbrains.kotlin.gradle.dsl.JvmTarget")
    if (c.has(kmpwizard.Target.WEB)) appendLine("import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl")
    appendLine("\nplugins {")
    appendLine("    alias(libs.plugins.kotlinMultiplatform)")
    if (c.has(kmpwizard.Target.ANDROID)) appendLine("    alias(libs.plugins.androidMultiplatformLibrary)")
    if (hasCompose) {
        appendLine("    alias(libs.plugins.composeMultiplatform)")
        appendLine("    alias(libs.plugins.composeCompiler)")
    }
    plugins.forEach { appendLine("    alias(libs.plugins.$it)") }
    appendLine("}\n")
    if (identity != null) {
        appendLine(identity.comment)
        appendLine("group = \"${identity.group}\"")
        appendLine("base { archivesName.set(\"${identity.name}\") }\n")
    }
    appendLine("kotlin {")
    if (c.has(kmpwizard.Target.ANDROID)) {
        appendLine("    android {")
        appendLine("        namespace = \"$namespace\"")
        appendLine("        compileSdk = $COMPILE_SDK")
        appendLine("        minSdk = $MIN_SDK")
        appendLine("        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }")
        appendLine("    }")
    }
    if (c.has(kmpwizard.Target.IOS)) {
        if (iosFramework != null) {
            appendLine("    listOf(iosArm64(), iosSimulatorArm64()).forEach { iosTarget ->")
            appendLine("        iosTarget.binaries.framework {")
            appendLine("            // The name Swift code imports.")
            appendLine("            baseName = \"$iosFramework\"")
            appendLine("            isStatic = true")
            appendLine("        }")
            appendLine("    }")
        } else {
            appendLine("    iosArm64()")
            appendLine("    iosSimulatorArm64()")
        }
    }
    if (hasJvm) appendLine("    jvm()")
    if (c.has(Target.WEB)) {
        appendLine("    js { browser() }")
        appendLine("    @OptIn(ExperimentalWasmDsl::class)")
        appendLine("    wasmJs { browser() }")
    }
    appendLine("\n    sourceSets {")
    deps.forEach { (set, list) ->
        appendLine("        $set.dependencies {")
        list.forEach { appendLine("            $it") }
        appendLine("        }")
    }
    appendLine("    }\n}")
    if (usesJUnit) appendLine("\ntasks.withType<Test>().configureEach { useJUnitPlatform() }")
    // Compose hooks a check onto every web browser test task that fails unless the target is an executable
    // (CMP-4906), even when the module has no tests, which breaks `./gradlew allTests`.
    if (hasCompose && c.has(Target.WEB) && deps.keys.none { it.endsWith("Test") }) {
        appendLine(
            "\n// No tests here yet. Compose's web UI-test check (CMP-4906) needs an executable target and would fail"
        )
        appendLine("// `allTests`; remove this when adding Compose UI tests, and bundle them as that check describes.")
        appendLine(
            "tasks.matching { it.name.startsWith(\"checkComposeUiTestConfigurationFor\") }" +
                ".configureEach { enabled = false }"
        )
    }
}
