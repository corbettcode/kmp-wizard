package kmpwizard.generator

import kmpwizard.ProjectSpecification
import kmpwizard.TODO_LIBRARIES
import kmpwizard.TODO_SLICE
import kmpwizard.Target
import kmpwizard.generator.todosample.todoSample
import kmpwizard.generator.todosample.todoServer
import kmpwizard.slug
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.PersonIdent
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import kotlin.io.path.*

/*
 * Generated project layout follows
 * https://kotlinlang.org/docs/multiplatform/multiplatform-project-recommended-structure.html
 *
 *   core/            shared kernel for every slice: logging, errors (no UI)
 *   coreUI/          UI kernel for every slice: the Feature contract the shell renders (Compose)
 *   feature/<slice>/ one directory per vertical slice, package <pkg>.<slice>, split by where it runs:
 *     shared/        domain + data (KMP, every target)
 *     client/        presentation + client adapters (KMP + Compose)
 *     server/        Ktor routes + server adapters (JVM)
 *   app/sharedUI/    Compose Multiplatform shell that renders the slices (exports the iOS framework)
 *   app/androidApp/  app/desktopApp/  app/webApp/  app/iosApp/   entry points
 *   server/          Ktor bootstrap, depends on every :feature:<slice>:server
 */

/**
 * Name Swift code imports; matches the framework baseName, not the module name.
 */
const val IOS_FRAMEWORK = "sharedUI"

/** Identity on the optional initial commit; the server has no idea who the user is. */
private const val GIT_AUTHOR_NAME = "KMP Wizard"
private const val GIT_AUTHOR_EMAIL = "kmp-wizard@localhost"
/** Wrapper files copied verbatim from resources, keyed by their path in the generated project. */
private val WRAPPER_FILES = mapOf(
    "gradlew" to "gradlew",
    "gradlew.bat" to "gradlew.bat",
    "gradle/wrapper/gradle-wrapper.jar" to "gradle-wrapper.jar",
)
private val EXECUTABLES = setOf("gradlew")

class ProjectGenerator {
    fun generate(spec: ProjectSpecification): ByteArray {
        val c = ProjectContext(spec)
        val files = linkedMapOf<String, String>()
        rootFiles(c, files)
        coreModule(c, files)
        featureModules(c, files)
        if (c.hasUi) sharedUiModule(c, files)
        spec.slices.forEach { slice(c, it, files) }
        if (c.todo) todoSample(c, files)
        if (c.has(Target.ANDROID)) androidApp(c, files)
        if (c.has(Target.DESKTOP)) desktopApp(c, files)
        if (c.has(Target.WEB)) webApp(c, files)
        if (c.has(Target.IOS)) iosApp(c, files)
        if (c.has(Target.SERVER)) server(c, files)
        val all = files.mapValues { it.value.toByteArray() } + wrapperFiles()
        val entries = if (spec.gitInit) withGitRepo(all, spec.commitMessage.trim()) else all
        return zip(c.slug, entries)
    }

    private fun wrapperFiles(): Map<String, ByteArray> = WRAPPER_FILES.mapValues { (_, name) ->
        val res = "/kmpwizard/wrapper/$name"
        ProjectGenerator::class.java.getResourceAsStream(res)?.use { it.readBytes() }
            ?: error("Missing resource $res")
    }

    /**
     * Writes the files to a temp dir, runs `git init` (branch `main`) and, if [message] is not blank,
     * commits everything. Returns everything in the dir, `.git/` included, keyed by relative path.
     * Directories map to null: git needs its empty ones (e.g. `.git/objects/`) to recognise the repo.
     */
    @OptIn(ExperimentalPathApi::class)
    private fun withGitRepo(files: Map<String, ByteArray>, message: String): Map<String, ByteArray?> {
        val dir = Files.createTempDirectory("kmp-wizard-")
        try {
            files.forEach { (path, content) ->
                val file = dir.resolve(path).apply { parent.createDirectories() }
                file.writeBytes(content)
                // Git records the executable bit from the file system.
                if (path in EXECUTABLES) runCatching { file.setPosixFilePermissions(PosixFilePermissions.fromString("rwxr-xr-x")) }
            }
            Git.init().setDirectory(dir.toFile()).setInitialBranch("main").call().use { git ->
                if (message.isNotEmpty()) {
                    git.add().addFilepattern(".").call()
                    val who = PersonIdent(GIT_AUTHOR_NAME, GIT_AUTHOR_EMAIL)
                    git.commit().setMessage(message).setAuthor(who).setCommitter(who).setSign(false).call()
                }
            }
            return dir.walk(PathWalkOption.INCLUDE_DIRECTORIES).filter { it != dir }
                .map { it.relativeTo(dir).invariantSeparatorsPathString to it }.sortedBy { it.first }
                .associate { (rel, p) -> if (p.isDirectory()) "$rel/" to null else rel to p.readBytes() }
        } finally {
            dir.deleteRecursively()
        }
    }

    /**
     * [files] maps relative paths to contents; a null value is a directory (path ending in `/`).
     * Entries carry Unix modes so [EXECUTABLES] (e.g. `gradlew`) stay executable when unzipped.
     */
    private fun zip(root: String, files: Map<String, ByteArray?>): ByteArray {
        val bos = ByteArrayOutputStream()
        ZipArchiveOutputStream(bos).use { z ->
            files.forEach { (path, content) ->
                val entry = ZipArchiveEntry("$root/$path")
                entry.unixMode = when {
                    content == null -> "40755".toInt(8)
                    path in EXECUTABLES -> "100755".toInt(8)
                    else -> "100644".toInt(8)
                }
                z.putArchiveEntry(entry)
                content?.let(z::write)
                z.closeArchiveEntry()
            }
        }
        return bos.toByteArray()
    }

    // ---------- root ----------

    private fun rootFiles(c: ProjectContext, f: MutableMap<String, String>) {
        val mods = buildList {
            add(":core")
            addAll(featureModulePaths(c))
            if (c.hasUi) add(":app:sharedUI")
            if (c.has(Target.ANDROID)) add(":app:androidApp")
            if (c.has(Target.DESKTOP)) add(":app:desktopApp")
            if (c.has(Target.WEB)) add(":app:webApp")
            if (c.has(Target.SERVER)) add(":server")
        }
        f["settings.gradle.kts"] = buildString {
            appendLine("rootProject.name = \"${c.slug}\"")
            appendLine("enableFeaturePreview(\"TYPESAFE_PROJECT_ACCESSORS\")\n")
            appendLine("pluginManagement {")
            appendLine("    repositories {")
            appendLine("        google()")
            appendLine("        mavenCentral()")
            appendLine("        gradlePluginPortal()")
            appendLine("    }")
            appendLine("}\n")
            appendLine("dependencyResolutionManagement {")
            appendLine("    repositories {")
            appendLine("        google()")
            appendLine("        mavenCentral()")
            appendLine("    }")
            appendLine("}\n")
            mods.forEach { appendLine("include(\"$it\")") }
        }
        f["build.gradle.kts"] = buildString {
            appendLine("plugins {")
            appendLine("    alias(libs.plugins.kotlinMultiplatform) apply false")
            if (c.hasUi) {
                appendLine("    alias(libs.plugins.composeMultiplatform) apply false")
                appendLine("    alias(libs.plugins.composeCompiler) apply false")
            }
            if (c.has(Target.ANDROID)) {
                appendLine("    alias(libs.plugins.androidMultiplatformLibrary) apply false")
                appendLine("    alias(libs.plugins.androidApplication) apply false")
            }
            if (c.hasJvm) appendLine("    alias(libs.plugins.kotlinJvm) apply false")
            c.plugins.keys.forEach { appendLine("    alias(libs.plugins.$it) apply false") }
            appendLine("}")
        }
        f["gradle.properties"] = """
            org.gradle.jvmargs=-Xmx4g -Dfile.encoding=UTF-8
            org.gradle.caching=true
            org.gradle.configuration-cache=true
            kotlin.code.style=official
            kotlin.daemon.jvmargs=-Xmx4g
            android.useAndroidX=true
            android.nonTransitiveRClass=true
        """.trimIndent()
        f["gradle/wrapper/gradle-wrapper.properties"] = """
            distributionBase=GRADLE_USER_HOME
            distributionPath=wrapper/dists
            distributionUrl=https\://services.gradle.org/distributions/gradle-$GRADLE-bin.zip
            networkTimeout=10000
            retries=0
            retryBackOffMs=500
            validateDistributionUrl=true
            zipStoreBase=GRADLE_USER_HOME
            zipStorePath=wrapper/dists
        """.trimIndent() + "\n"
        // Same line-ending rules `gradle init` writes, so gradlew.bat keeps CRLF and the jar isn't mangled.
        f[".gitattributes"] = "gradlew text eol=lf\n*.bat text eol=crlf\n*.jar binary\n"
        dockerCompose(c)?.let { f["docker-compose.yml"] = it }
        f[".gitignore"] = "build/\n.gradle/\n.idea/\n.kotlin/\nlocal.properties\n*.iml\nxcuserdata/\n"
        f["gradle/libs.versions.toml"] = versionCatalog(c)
        f["README.md"] = readme(c)
    }

    /** Local services the project needs (MongoDB for the Todo sample, PostgreSQL for Flyway); null if none. */
    private fun dockerCompose(c: ProjectContext): String? {
        val services = buildList {
            if (c.todo) add("Todo sample" to ("""
                mongo:
                  # The 8.0 long-term-support line. Newer 8.x images (8.3) refuse to start on
                  # Linux kernels 6.19-7.0.13 (MongoDB SERVER-121912 / SERVER-136848).
                  image: mongo:8.0
                  ports:
                    - "27017:27017"
                  volumes:
                    - mongo-data:/data/db
            """.trimIndent() to "mongo-data:"))
            if (c.lib(FlywayGenerator.ID)) add("Flyway" to FlywayGenerator.dockerService(c))
        }
        if (services.isEmpty()) return null
        return buildString {
            appendLine("# Local services for ${services.joinToString(" and ") { it.first }}: docker compose up -d")
            appendLine("services:")
            services.forEach { (_, svc) -> appendLine(svc.first.prependIndent("  ")) }
            appendLine("volumes:")
            services.forEach { (_, svc) -> appendLine("  ${svc.second}") }
        }
    }

    /** Each section's entries are sorted by key. */
    private fun versionCatalog(c: ProjectContext) = buildString {
        val v = sortedMapOf("kotlin" to KOTLIN, "coroutines" to COROUTINES)
        if (c.hasUi) {
            v["compose"] = COMPOSE
            v["compose-material3"] = COMPOSE_MATERIAL3
        }
        if (c.has(Target.ANDROID)) {
            v["agp"] = AGP
            v["activity"] = ACTIVITY
        }
        c.activeLibs.forEach { v[it.versionKey] = it.version; v.putAll(it.extraVersions) }
        if (c.has(Target.SERVER)) v["ktor"] = KTOR
        if (c.has(Target.SERVER) || desktopLogback(c)) v["logback"] = LOGBACK
        if (c.todo) v["mongodb"] = MONGODB
        if (c.lib(FlywayGenerator.ID)) FlywayGenerator.versions(v)

        // alias -> (module, version ref)
        val libs = sortedMapOf<String, Pair<String, String>>()
        fun lib(alias: String, module: String, ref: String) { libs[alias] = module to ref }
        lib("coroutines-core", "org.jetbrains.kotlinx:kotlinx-coroutines-core", "coroutines")
        lib("coroutines-test", "org.jetbrains.kotlinx:kotlinx-coroutines-test", "coroutines")
        if (c.hasUi) {
            lib("compose-runtime", "org.jetbrains.compose.runtime:runtime", "compose")
            lib("compose-ui", "org.jetbrains.compose.ui:ui", "compose")
            lib("compose-foundation", "org.jetbrains.compose.foundation:foundation", "compose")
            lib("compose-material3", "org.jetbrains.compose.material3:material3", "compose-material3")
        }
        if (c.has(Target.DESKTOP)) lib("coroutines-swing", "org.jetbrains.kotlinx:kotlinx-coroutines-swing", "coroutines")
        if (c.has(Target.ANDROID)) lib("androidx-activity-compose", "androidx.activity:activity-compose", "activity")
        c.activeLibs.forEach { l -> l.arts.forEach { lib(it.alias, it.module, l.versionKey) } }
        if (c.has(Target.SERVER)) {
            lib("ktor-server-core", "io.ktor:ktor-server-core", "ktor")
            lib("ktor-server-netty", "io.ktor:ktor-server-netty", "ktor")
            lib("ktor-server-content-negotiation", "io.ktor:ktor-server-content-negotiation", "ktor")
            lib("ktor-serialization-kotlinx-json", "io.ktor:ktor-serialization-kotlinx-json", "ktor")
            lib("ktor-server-test-host", "io.ktor:ktor-server-test-host", "ktor")
        }
        if (c.has(Target.SERVER) || desktopLogback(c)) lib("logback", "ch.qos.logback:logback-classic", "logback")
        if (c.todo) {
            lib("ktor-client-content-negotiation", "io.ktor:ktor-client-content-negotiation", "ktor")
            lib("ktor-server-cors", "io.ktor:ktor-server-cors", "ktor")
            lib("mongodb-driver-kotlin-coroutine", "org.mongodb:mongodb-driver-kotlin-coroutine", "mongodb")
        }
        if (c.lib(FlywayGenerator.ID)) FlywayGenerator.libraries(::lib)

        // alias -> (plugin id, version ref)
        val plugins = sortedMapOf<String, Pair<String, String>>()
        fun plugin(alias: String, id: String, ref: String) { plugins[alias] = id to ref }
        plugin("kotlinMultiplatform", "org.jetbrains.kotlin.multiplatform", "kotlin")
        if (c.hasJvm) plugin("kotlinJvm", "org.jetbrains.kotlin.jvm", "kotlin")
        if (c.hasUi) {
            plugin("composeMultiplatform", "org.jetbrains.compose", "compose")
            plugin("composeCompiler", "org.jetbrains.kotlin.plugin.compose", "kotlin")
        }
        if (c.has(Target.ANDROID)) {
            plugin("androidApplication", "com.android.application", "agp")
            plugin("androidMultiplatformLibrary", "com.android.kotlin.multiplatform.library", "agp")
        }
        c.plugins.values.forEach { plugin(it.alias, it.id, it.versionRef) }

        appendLine("[versions]")
        v.forEach { (k, x) -> appendLine("$k = \"$x\"") }
        appendLine("\n[libraries]")
        libs.forEach { (alias, m) -> appendLine("$alias = { module = \"${m.first}\", version.ref = \"${m.second}\" }") }
        appendLine("\n[plugins]")
        plugins.forEach { (alias, p) -> appendLine("$alias = { id = \"${p.first}\", version.ref = \"${p.second}\" }") }
    }

    // ---------- app/sharedUI: Compose shell that renders each slice's Feature ----------

    private fun sharedUiModule(c: ProjectContext, f: MutableMap<String, String>) {
        val deps = linkedMapOf(
            "commonMain" to listOf("api(projects.coreUI)") +
                c.allSlices.map { "implementation(${sliceProject(it, "client")})" } + listOf(
                "implementation(libs.compose.foundation)",
                "implementation(libs.compose.material3)",
            ) + if (c.lib("koin")) listOf(
                "implementation(libs.koin.compose)",
                "implementation(libs.koin.composeViewmodel)",
            ) else emptyList(),
        )
        f["app/sharedUI/build.gradle.kts"] = kmpBuildScript(
            c, namespace = "${c.pkg}.ui", compose = true, jvm = c.has(Target.DESKTOP), iosFramework = IOS_FRAMEWORK,
            plugins = emptyList(), deps = deps,
        )

        val base = "app/sharedUI/src/commonMain/kotlin/${c.dir}/ui"
        val names = c.allSlices.map { it.replaceFirstChar(Char::uppercase) }
        val koin = c.lib("koin")
        f["$base/AppFeatures.kt"] = buildString {
            appendLine("package ${c.pkg}.ui\n")
            appendLine("import ${coreUiPkg(c)}.Feature")
            c.allSlices.zip(names).forEach { (s, x) -> appendLine("import ${slicePkg(c, s)}.${x}Feature") }
            if (koin) {
                sliceKoinModules(c).forEach { (s, m) -> appendLine("import ${slicePkg(c, s)}.$m") }
                appendLine("import org.koin.core.module.Module")
            }
            appendLine("\nval features: List<Feature> = listOf(${names.joinToString { it + "Feature" }})")
            if (koin) {
                appendLine("\n/** Koin modules of the slices, loaded by [App]. */")
                appendLine("val koinModules: List<Module> = listOf(${sliceKoinModules(c).joinToString { it.second }})")
            }
        }
        f["$base/App.kt"] = """
            package ${c.pkg}.ui

            import androidx.compose.foundation.layout.Column
            import androidx.compose.material3.MaterialTheme
            import androidx.compose.material3.Surface
            import androidx.compose.material3.Tab
            import androidx.compose.material3.PrimaryTabRow
            import androidx.compose.material3.Text
            import androidx.compose.runtime.Composable
            import androidx.compose.runtime.getValue
            import androidx.compose.runtime.mutableStateOf
            import androidx.compose.runtime.remember
            import androidx.compose.runtime.setValue
            __KOIN_IMPORTS__
            __KOIN_APP__
            @Composable
            __SHELL__ = MaterialTheme {
                Surface {
                    var selected by remember { mutableStateOf(0) }
                    Column {
                        PrimaryTabRow(selectedTabIndex = selected) {
                            features.forEachIndexed { i, f ->
                                Tab(selected = selected == i, onClick = { selected = i }, text = { Text(f.title) })
                            }
                        }
                        features[selected].Content()
                    }
                }
            }
        """.trimIndent()
            .replace("__KOIN_IMPORTS__\n", if (koin) "import org.koin.compose.KoinApplication\nimport org.koin.dsl.koinConfiguration\n" else "")
            .replace("__KOIN_APP__\n", if (koin) "\n" + """
                |/** One configuration for the app's lifetime; Koin is started once, not on every recomposition. */
                |private val koinConfiguration = koinConfiguration { modules(koinModules) }
                |
                |/** Starts Koin (for koinViewModel() and koinInject() in the slices) around the app shell. */
                |@Composable
                |fun App() = KoinApplication(configuration = koinConfiguration) { AppShell() }
                |
            """.trimMargin() + "\n" else "\n")
            .replace("__SHELL__", if (koin) "private fun AppShell()" else "fun App()")
        // iOS has no Gradle entry-point module; its view controller lives with the UI that the framework exports.
        if (c.has(Target.IOS)) f["app/sharedUI/src/iosMain/kotlin/${c.appDir("ios")}/MainViewController.kt"] = """
            package ${c.appPkg("ios")}

            import androidx.compose.ui.window.ComposeUIViewController
            import ${c.pkg}.ui.App

            fun MainViewController() = ComposeUIViewController { App() }
        """.trimIndent()
    }

    // ---------- app/: platform entry points ----------

    private fun androidApp(c: ProjectContext, f: MutableMap<String, String>) {
        f["app/androidApp/build.gradle.kts"] = """
            plugins {
                alias(libs.plugins.androidApplication)
                alias(libs.plugins.composeMultiplatform)
                alias(libs.plugins.composeCompiler)
            }

            android {
                namespace = "${c.appPkg("android")}"
                compileSdk = $COMPILE_SDK
                defaultConfig {
                    applicationId = "${c.pkg}"
                    minSdk = $MIN_SDK
                    targetSdk = $TARGET_SDK
                    versionCode = 1
                    versionName = "1.0"
                }
                compileOptions {
                    sourceCompatibility = JavaVersion.VERSION_17
                    targetCompatibility = JavaVersion.VERSION_17
                }
            }

            dependencies {
                implementation(projects.app.sharedUI)
                implementation(libs.androidx.activity.compose)
            }
        """.trimIndent()
        f["app/androidApp/src/main/AndroidManifest.xml"] = """
            <?xml version="1.0" encoding="utf-8"?>
            <manifest xmlns:android="http://schemas.android.com/apk/res/android">${if (c.todo) "\n                <uses-permission android:name=\"android.permission.INTERNET\" />" else ""}
                <application android:label="${c.spec.name.replace("\"", "")}"${if (c.todo) " android:usesCleartextTraffic=\"true\"" else ""} android:theme="@android:style/Theme.Material.Light.NoActionBar">
                    <activity android:name=".MainActivity" android:exported="true">
                        <intent-filter>
                            <action android:name="android.intent.action.MAIN" />
                            <category android:name="android.intent.category.LAUNCHER" />
                        </intent-filter>
                    </activity>
                </application>
            </manifest>
        """.trimIndent()
        f["app/androidApp/src/main/kotlin/${c.appDir("android")}/MainActivity.kt"] = """
            package ${c.appPkg("android")}

            import android.os.Bundle
            import androidx.activity.ComponentActivity
            import androidx.activity.compose.setContent
            import ${c.pkg}.ui.App

            class MainActivity : ComponentActivity() {
                override fun onCreate(savedInstanceState: Bundle?) {
                    super.onCreate(savedInstanceState)
                    setContent { App() }
                }
            }
        """.trimIndent()
    }

    /** The desktop app gets the Ktor client's slf4j-api on its classpath, so it needs a logging backend. */
    private fun desktopLogback(c: ProjectContext) = c.has(Target.DESKTOP) && c.lib("ktor")

    private fun desktopApp(c: ProjectContext, f: MutableMap<String, String>) {
        f["app/desktopApp/build.gradle.kts"] = """
            import org.jetbrains.compose.desktop.application.dsl.TargetFormat

            plugins {
                alias(libs.plugins.kotlinJvm)
                alias(libs.plugins.composeMultiplatform)
                alias(libs.plugins.composeCompiler)
            }

            dependencies {
                implementation(projects.app.sharedUI)
                implementation(compose.desktop.currentOs)
                implementation(libs.coroutines.swing)__LOGBACK__
            }

            compose.desktop {
                application {
                    mainClass = "${c.appPkg("desktop")}.MainKt"
                    nativeDistributions {
                        targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
                        packageName = "${c.slug}"
                        packageVersion = "1.0.0"
                    }
                }
            }
        """.trimIndent().replace(
            "__LOGBACK__",
            if (desktopLogback(c)) "\n    // The Ktor client logs through SLF4J; without a backend it prints \"No SLF4J providers were found\".\n    runtimeOnly(libs.logback)" else "",
        )
        if (desktopLogback(c)) f["app/desktopApp/src/main/resources/logback.xml"] = """
            <configuration>
                <appender name="STDOUT" class="ch.qos.logback.core.ConsoleAppender">
                    <encoder><pattern>%d{HH:mm:ss.SSS} %-5level %logger{36} - %msg%n</pattern></encoder>
                </appender>
                <root level="INFO"><appender-ref ref="STDOUT" /></root>
            </configuration>
        """.trimIndent()
        f["app/desktopApp/src/main/kotlin/${c.appDir("desktop")}/Main.kt"] = """
            package ${c.appPkg("desktop")}

            import androidx.compose.ui.window.Window
            import androidx.compose.ui.window.application
            import ${c.pkg}.ui.App

            fun main() = application {
                Window(onCloseRequest = ::exitApplication, title = "${c.slug}") { App() }
            }
        """.trimIndent()
    }

    private fun webApp(c: ProjectContext, f: MutableMap<String, String>) {
        f["app/webApp/build.gradle.kts"] = """
            import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

            plugins {
                alias(libs.plugins.kotlinMultiplatform)
                alias(libs.plugins.composeMultiplatform)
                alias(libs.plugins.composeCompiler)
            }

            kotlin {
                js {
                    outputModuleName = "webApp"
                    browser()
                    binaries.executable()
                }
                @OptIn(ExperimentalWasmDsl::class)
                wasmJs {
                    outputModuleName = "webApp"
                    browser()
                    binaries.executable()
                }

                sourceSets {
                    webMain.dependencies {
                        implementation(projects.app.sharedUI)
                    }
                }
            }
        """.trimIndent()
        f["app/webApp/src/webMain/kotlin/${c.appDir("web")}/Main.kt"] = """
            package ${c.appPkg("web")}

            import androidx.compose.ui.ExperimentalComposeUiApi
            import androidx.compose.ui.window.ComposeViewport
            import ${c.pkg}.ui.App

            @OptIn(ExperimentalComposeUiApi::class)
            fun main() {
                ComposeViewport { App() }
            }
        """.trimIndent()
        f["app/webApp/src/webMain/resources/index.html"] = """
            <!DOCTYPE html>
            <html><head><meta charset="UTF-8"><meta name="viewport" content="width=device-width, initial-scale=1.0">
            <title>${c.slug}</title><script src="webApp.js"></script></head><body></body></html>
        """.trimIndent()
    }

    private fun iosApp(c: ProjectContext, f: MutableMap<String, String>) {
        f["app/iosApp/iosApp/iOSApp.swift"] = """
            import SwiftUI

            @main
            struct iOSApp: App {
                var body: some Scene { WindowGroup { ContentView() } }
            }
        """.trimIndent()
        f["app/iosApp/iosApp/ContentView.swift"] = """
            import SwiftUI
            import $IOS_FRAMEWORK

            struct ComposeView: UIViewControllerRepresentable {
                func makeUIViewController(context: Context) -> UIViewController { MainViewControllerKt.MainViewController() }
                func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
            }

            struct ContentView: View {
                var body: some View { ComposeView().ignoresSafeArea() }
            }
        """.trimIndent()
        f["app/iosApp/README.md"] = """
            # iOS host app
            Xcode projects can't be generated reliably from text, so:
            1. In Xcode create an iOS App named `iosApp` in this folder, then replace its Swift files with the ones here.
            2. Add a Run Script build phase *before* "Compile Sources":
               `cd "${'$'}SRCROOT/../.." && ./gradlew :app:sharedUI:embedAndSignAppleFrameworkForXcode`
            3. Set "User Script Sandboxing" to No and add `${'$'}(SRCROOT)/../sharedUI/build/xcode-frameworks/${'$'}(CONFIGURATION)/${'$'}(SDK_NAME)` to Framework Search Paths.

            Swift imports the framework as `$IOS_FRAMEWORK` (its `baseName` in `app/sharedUI/build.gradle.kts`).
        """.trimIndent() + if (c.todo) "\n\n" + """
            The Todo tab calls the server over plain HTTP. In `Info.plist` add
            `NSAppTransportSecurity` > `NSAllowsLocalNetworking` = YES so the simulator can reach `localhost`.
        """.trimIndent() else ""
    }

    // ---------- server: Ktor bootstrap; each slice's routes come from its :feature:<slice>:server module ----------

    private fun server(c: ProjectContext, f: MutableMap<String, String>) {
        val sp = c.appPkg("server")
        val sd = c.appDir("server")
        val slices = c.spec.slices.map { it to it.replaceFirstChar(Char::uppercase) }
        val flyway = c.lib(FlywayGenerator.ID)
        f["server/build.gradle.kts"] = """
            plugins {
                alias(libs.plugins.kotlinJvm)
                alias(libs.plugins.kotlinSerialization)
                application
            }

            application {
                mainClass.set("$sp.ApplicationKt")
                // Netty loads native transports; without this, JDK 24+ prints "restricted method" warnings.
                applicationDefaultJvmArgs = listOf("--enable-native-access=ALL-UNNAMED")
            }

            dependencies {
                __SLICE_SERVERS__
                implementation(libs.ktor.server.core)
                implementation(libs.ktor.server.netty)
                implementation(libs.ktor.server.content.negotiation)
                implementation(libs.ktor.serialization.kotlinx.json)
                implementation(libs.logback)${if (c.todo) """
                implementation(libs.ktor.server.cors)
                implementation(libs.mongodb.driver.kotlin.coroutine)""" else ""}${
                    if (flyway) FlywayGenerator.serverDependencies.joinToString("") { "\n                $it" } else ""}
                testImplementation(kotlin("test"))
                testImplementation(libs.ktor.server.test.host)
            }

            tasks.test { useJUnitPlatform() }
        """.trimIndent().replace(
            "__SLICE_SERVERS__", c.allSlices.joinToString("\n    ") { "implementation(${sliceProject(it, "server")})" },
        )
        f["server/src/main/kotlin/$sd/Application.kt"] = buildString {
            appendLine("package $sp\n")
            appendLine("import io.ktor.serialization.kotlinx.json.json")
            appendLine("import io.ktor.server.application.Application")
            appendLine("import io.ktor.server.application.install")
            appendLine("import io.ktor.server.netty.EngineMain")
            appendLine("import io.ktor.server.plugins.contentnegotiation.ContentNegotiation")
            appendLine("import io.ktor.server.response.respondText")
            appendLine("import io.ktor.server.routing.get")
            appendLine("import io.ktor.server.routing.routing")
            if (c.todo) {
                appendLine("import io.ktor.http.HttpHeaders")
                appendLine("import io.ktor.http.HttpMethod")
                appendLine("import io.ktor.server.application.ApplicationStopped")
                appendLine("import io.ktor.server.application.log")
                appendLine("import io.ktor.server.plugins.cors.routing.CORS")
                appendLine("import com.mongodb.ConnectionString")
                appendLine("import com.mongodb.MongoClientSettings")
                appendLine("import com.mongodb.kotlin.client.coroutine.MongoClient")
                appendLine("import java.util.concurrent.TimeUnit")
                appendLine("import kotlinx.coroutines.launch")
                appendLine("import org.bson.Document")
                appendLine("import ${slicePkg(c, TODO_SLICE)}.server.MongoTodoRepository")
                appendLine("import ${slicePkg(c, TODO_SLICE)}.server.todoRoutes")
            }
            slices.forEach { (s, _) -> appendLine("import ${slicePkg(c, s)}.server.${s}Routes") }
            if (flyway) FlywayGenerator.applicationImports(c).forEach(::appendLine)
            appendLine("\n/** Port, host and modules come from src/main/resources/application.conf. */")
            appendLine("fun main(args: Array<String>) = EngineMain.main(args)\n")
            appendLine("fun Application.module() {")
            appendLine("    install(ContentNegotiation) { json() }")
            if (flyway) FlywayGenerator.applicationStartup.forEach { appendLine("    $it") }
            if (c.todo) {
                appendLine("    // The web app is served from another port, so browsers need CORS to call this API.")
                appendLine("    install(CORS) {")
                appendLine("        anyHost()")
                appendLine("        allowHeader(HttpHeaders.ContentType)")
                appendLine("        allowMethod(HttpMethod.Patch)")
                appendLine("        allowMethod(HttpMethod.Delete)")
                appendLine("    }")
                append("""
                    |    val mongoConfig = environment.config.config("mongo")
                    |    val mongoUrl = ConnectionString(mongoConfig.property("uri").getString())
                    |    // The driver connects in the background, so the server starts even when MongoDB is down.
                    |    // Requests that need it then fail after 5 s instead of the driver's default 30 s.
                    |    val mongo = MongoClient.create(
                    |        MongoClientSettings.builder()
                    |            .applyConnectionString(mongoUrl)
                    |            .applyToClusterSettings { it.serverSelectionTimeout(5, TimeUnit.SECONDS) }
                    |            .build()
                    |    )
                    |    monitor.subscribe(ApplicationStopped) { mongo.close() }
                    |    val mongoDb = mongo.getDatabase(mongoConfig.property("database").getString())
                    |    // One clear line at startup instead of the driver's connection stack traces (quieted in logback.xml).
                    |    // Logs the hosts only: the URI may carry credentials.
                    |    launch {
                    |        runCatching { mongoDb.runCommand(Document("ping", 1)) }
                    |            .onSuccess { log.info("Connected to MongoDB at ${'$'}{mongoUrl.hosts}") }
                    |            .onFailure {
                    |                log.warn(
                    |                    "MongoDB is not reachable at ${'$'}{mongoUrl.hosts}; /api/$TODO_SLICE fails until it is. " +
                    |                        "Start it with `docker compose up -d`, or point MONGODB_URI at your own instance."
                    |                )
                    |            }
                    |    }
                    |    val todos = MongoTodoRepository(mongoDb)
                    |""".trimMargin() + "\n")
            }
            appendLine("    routing {")
            appendLine("        get(\"/\") { call.respondText(\"${c.slug} server is running\") }")
            slices.forEach { (s, _) -> appendLine("        ${s}Routes()") }
            if (c.todo) appendLine("        todoRoutes(todos)")
            appendLine("    }")
            appendLine("}")
        }
        if (c.todo) todoServer(c, f)
        if (flyway) FlywayGenerator.files(c, f)
        f["server/src/main/resources/application.conf"] = ("""
            ktor {
                deployment {
                    port = $SERVER_PORT
                    # Override at runtime with the PORT environment variable.
                    port = ${'$'}{?PORT}
                    host = "0.0.0.0"
                }
                application {
                    modules = [ $sp.ApplicationKt.module ]
                }
            }
        """.trimIndent() + if (c.todo) "\n" + """
            mongo {
                uri = "mongodb://localhost:27017"
                uri = ${'$'}{?MONGODB_URI}
                database = "${c.slug.replace('-', '_')}"
                database = ${'$'}{?MONGODB_DATABASE}
            }
        """.trimIndent() else "") + if (flyway) "\n" + FlywayGenerator.config(c) else ""
        f["server/src/main/resources/logback.xml"] = """
            <configuration>
                <appender name="STDOUT" class="ch.qos.logback.core.ConsoleAppender">
                    <encoder><pattern>%d{HH:mm:ss.SSS} %-5level %logger{36} - %msg%n</pattern></encoder>
                </appender>
                <root level="INFO"><appender-ref ref="STDOUT" /></root>
                <logger name="io.netty" level="WARN" />
                __MONGO_LOGGER__
            </configuration>
        """.trimIndent().replace(
            "    __MONGO_LOGGER__\n",
            // The driver logs every failed background connection attempt with a stack trace; Application logs one line.
            if (c.todo) "    <logger name=\"org.mongodb.driver\" level=\"WARN\" />\n" else "",
        )
        f["server/src/test/kotlin/$sd/ApplicationTest.kt"] = buildString {
            appendLine("package $sp\n")
            appendLine("import io.ktor.client.request.get")
            appendLine("import io.ktor.http.HttpStatusCode")
            appendLine("import io.ktor.server.config.ApplicationConfig")
            appendLine("import io.ktor.server.testing.testApplication")
            appendLine("import kotlin.test.Test")
            appendLine("import kotlin.test.assertEquals")
            if (flyway) FlywayGenerator.testImports(c).forEach(::appendLine)
            val config = if (flyway) FlywayGenerator.TEST_CONFIG else "ApplicationConfig(\"application.conf\")"
            appendLine("\nclass ApplicationTest {")
            appendLine("    @Test fun root() = testApplication {")
            appendLine("        environment { config = $config }")
            appendLine("        assertEquals(HttpStatusCode.OK, client.get(\"/\").status)")
            appendLine("    }")
            slices.forEach { (s, _) ->
                appendLine("    @Test fun ${s}Endpoint() = testApplication {")
                appendLine("        environment { config = $config }")
                appendLine("        assertEquals(HttpStatusCode.OK, client.get(\"/api/$s\").status)")
                appendLine("    }")
            }
            appendLine("}")
        }
    }

}
