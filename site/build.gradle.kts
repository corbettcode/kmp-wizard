import com.varabyte.kobweb.gradle.application.util.configAsKobwebApplication

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kobweb.application)
}

group = "kmpwizard"
version = "1.0.2"

// Generates kmpwizard.APP_VERSION from `version` above, so the UI always shows the build's version.
val generateBuildInfo = tasks.register("generateBuildInfo") {
    val outDir = layout.buildDirectory.dir("generated/buildInfo/kotlin")
    val appVersion = project.version.toString()
    inputs.property("version", appVersion)
    outputs.dir(outDir)
    doLast {
        val file = outDir.get().file("kmpwizard/BuildInfo.kt").asFile
        file.parentFile.mkdirs()
        file.writeText("package kmpwizard\n\nconst val APP_VERSION = \"$appVersion\"\n")
    }
}

kotlin {
    configAsKobwebApplication(includeServer = true)

    sourceSets {
        commonMain {
            kotlin.srcDir(generateBuildInfo)
        }
        commonMain.dependencies {
            implementation(libs.kotlinx.serialization.json)
        }

        jsMain.dependencies {
            implementation(libs.compose.runtime)
            implementation(libs.compose.html.core)
            implementation(libs.kobweb.core)
            implementation(libs.kobweb.silk)
        }

        jvmMain.dependencies {
            compileOnly(libs.kobweb.api) // Provided by Kobweb backend at runtime
            implementation(libs.jgit) // Builds the optional git repo inside generated projects
            implementation(libs.commons.compress) // ZIP entries with Unix modes, so gradlew stays executable
        }
    }
}
