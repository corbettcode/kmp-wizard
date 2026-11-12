package kmpwizard.generator

import kmpwizard.Target

// ---------- KMP library build script (core and app/sharedUI) ----------

fun kmpBuildScript(
    c: ProjectContext,
    namespace: String,
    compose: Boolean,
    jvm: Boolean,
    iosFramework: String?,
    plugins: List<String>,
    deps: Map<String, List<String>>,
    junit: Boolean = false,
) = buildString {
    if (c.has(kmpwizard.Target.ANDROID)) appendLine("import org.jetbrains.kotlin.gradle.dsl.JvmTarget")
    if (c.has(kmpwizard.Target.WEB)) appendLine("import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl")
    appendLine("\nplugins {")
    appendLine("    alias(libs.plugins.kotlinMultiplatform)")
    if (c.has(kmpwizard.Target.ANDROID)) appendLine("    alias(libs.plugins.androidMultiplatformLibrary)")
    if (compose) {
        appendLine("    alias(libs.plugins.composeMultiplatform)")
        appendLine("    alias(libs.plugins.composeCompiler)")
    }
    plugins.forEach { appendLine("    alias(libs.plugins.$it)") }
    appendLine("}\n\nkotlin {")
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
    if (jvm) appendLine("    jvm()")
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
    if (junit) appendLine("\ntasks.withType<Test>().configureEach { useJUnitPlatform() }")
}
