package kmpwizard.generator

import kmpwizard.ProjectSpec
import kmpwizard.Target
import kmpwizard.slug
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

// Pinned versions of the GENERATED project. Bump these in one place.
private const val KOTLIN = "2.1.21"
private const val AGP = "8.7.3"
private const val KTOR = "3.1.3"
private const val LOGBACK = "1.5.18"
private const val SERVER_PORT = 8081
private const val IOS_FRAMEWORK = "ComposeApp"
private const val COMPOSE = "1.8.0"

/** [api] = true when the library's types appear in the shared module's public API (e.g. use case return types). */
private class Art(val alias: String, val module: String, val sets: List<String>, val api: Boolean = false)
private class Plug(val alias: String, val id: String, val versionRef: String)
private class Lib(val versionKey: String, val version: String, val arts: List<Art>, val plugin: Plug? = null)

private val wiring = mapOf(
    "kermit" to Lib("kermit", "2.0.4", listOf(Art("kermit", "co.touchlab:kermit", listOf("commonMain")))),
    "arrow" to Lib("arrow", "2.1.0", listOf(Art("arrow-core", "io.arrow-kt:arrow-core", listOf("commonMain"), api = true))),
    "kotest" to Lib(
        "kotest", "5.9.1",
        listOf(
            Art("kotest-assertions", "io.kotest:kotest-assertions-core", listOf("commonTest")),
            Art("kotest-engine", "io.kotest:kotest-framework-engine", listOf("commonTest")),
            Art("kotest-runner", "io.kotest:kotest-runner-junit5", listOf("jvmTest")),
        ),
        Plug("kotest", "io.kotest.multiplatform", "kotest"),
    ),
    "turbine" to Lib("turbine", "1.2.0", listOf(Art("turbine", "app.cash.turbine:turbine", listOf("commonTest")))),
    "ktor" to Lib(
        "ktor", KTOR,
        listOf(
            Art("ktor-client-core", "io.ktor:ktor-client-core", listOf("commonMain")),
            Art("ktor-client-okhttp", "io.ktor:ktor-client-okhttp", listOf("androidMain", "jvmMain")),
            Art("ktor-client-darwin", "io.ktor:ktor-client-darwin", listOf("iosMain")),
            Art("ktor-client-js", "io.ktor:ktor-client-js", listOf("wasmJsMain")),
        ),
    ),
    "serialization" to Lib(
        "serialization", "1.8.1",
        listOf(Art("serialization-json", "org.jetbrains.kotlinx:kotlinx-serialization-json", listOf("commonMain"))),
        Plug("kotlinSerialization", "org.jetbrains.kotlin.plugin.serialization", "kotlin"),
    ),
    "datetime" to Lib("datetime", "0.6.2", listOf(Art("datetime", "org.jetbrains.kotlinx:kotlinx-datetime", listOf("commonMain")))),
    // Verify against Maven Central; Occurrent publishes JVM artifacts only.
    "occurrent" to Lib(
        "occurrent", "0.19.2",
        listOf(Art("occurrent-eventstore-inmemory", "org.occurrent:eventstore-inmemory", listOf("androidMain", "jvmMain"))),
    ),
)

// Desktop and Server share the single `jvm()` target of the shared module.
private val jvmTargets = setOf(Target.DESKTOP, Target.SERVER)

// Targets with a Compose UI; these get the `ui` module.
private val uiTargets = setOf(Target.ANDROID, Target.IOS, Target.DESKTOP, Target.WEB)

/** Source sets that only exist when one of these targets is selected. */
private val setTargets = mapOf(
    "androidMain" to setOf(Target.ANDROID),
    "iosMain" to setOf(Target.IOS),
    "jvmMain" to jvmTargets,
    "jvmTest" to jvmTargets,
    "wasmJsMain" to setOf(Target.WEB),
)

private class Ctx(val spec: ProjectSpec) {
    val pkg = spec.packageName
    val dir = pkg.replace('.', '/')
    /** Platform entry points live in `<pkg>.app.<platform>`. */
    fun appPkg(platform: String) = "$pkg.app.$platform"
    fun appDir(platform: String) = "$dir/app/$platform"
    val slug = spec.slug()
    fun has(t: Target) = t in spec.targets
    val hasJvm get() = spec.targets.any { it in jvmTargets }
    val hasUi get() = spec.targets.any { it in uiTargets }
    fun lib(id: String) = id in spec.libraries
    val activeLibs = spec.libraries.sorted().mapNotNull { wiring[it] }
    fun setActive(set: String) = setTargets[set]?.let { ts -> ts.any(::has) } ?: true
    /** Gradle plugins applied somewhere in the project, keyed by catalog alias. */
    val plugins: Map<String, Plug> = buildMap {
        if (spec.targets.contains(Target.SERVER))
            put("kotlinSerialization", Plug("kotlinSerialization", "org.jetbrains.kotlin.plugin.serialization", "kotlin"))
        activeLibs.mapNotNull { it.plugin }.forEach { put(it.alias, it) }
    }
    /** Plugins from selected libraries, applied to the shared module. */
    val sharedPlugins = activeLibs.mapNotNull { it.plugin?.alias }.distinct()
}

class ProjectGenerator {
    fun generate(spec: ProjectSpec): ByteArray {
        val c = Ctx(spec)
        val files = linkedMapOf<String, String>()
        rootFiles(c, files)
        sharedModule(c, files)
        if (c.hasUi) uiModule(c, files)
        spec.slices.forEach { slice(c, it, files) }
        if (c.has(Target.ANDROID)) androidApp(c, files)
        if (c.has(Target.DESKTOP)) desktopApp(c, files)
        if (c.has(Target.WEB)) webApp(c, files)
        if (c.has(Target.IOS)) iosApp(c, files)
        if (c.has(Target.SERVER)) server(c, files)
        return zip(c.slug, files)
    }

    private fun zip(root: String, files: Map<String, String>): ByteArray {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { z ->
            files.forEach { (path, content) ->
                z.putNextEntry(ZipEntry("$root/$path"))
                z.write(content.toByteArray())
                z.closeEntry()
            }
        }
        return bos.toByteArray()
    }

    // ---------- root ----------

    private fun rootFiles(c: Ctx, f: MutableMap<String, String>) {
        val mods = buildList {
            add("shared")
            if (c.hasUi) add("ui")
            if (c.has(Target.ANDROID)) add("androidApp")
            if (c.has(Target.DESKTOP)) add("desktopApp")
            if (c.has(Target.WEB)) add("webApp")
            if (c.has(Target.SERVER)) add("server")
        }
        f["settings.gradle.kts"] = buildString {
            appendLine("rootProject.name = \"${c.slug}\"")
            appendLine("enableFeaturePreview(\"TYPESAFE_PROJECT_ACCESSORS\")")
            appendLine("pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }")
            appendLine("dependencyResolutionManagement { repositories { google(); mavenCentral() } }")
            mods.forEach { appendLine("include(\":$it\")") }
        }
        f["build.gradle.kts"] = buildString {
            appendLine("plugins {")
            appendLine("    alias(libs.plugins.kotlinMultiplatform) apply false")
            if (c.hasUi) {
                appendLine("    alias(libs.plugins.composeMultiplatform) apply false")
                appendLine("    alias(libs.plugins.composeCompiler) apply false")
            }
            if (c.has(Target.ANDROID)) {
                appendLine("    alias(libs.plugins.androidLibrary) apply false")
                appendLine("    alias(libs.plugins.androidApplication) apply false")
                appendLine("    alias(libs.plugins.kotlinAndroid) apply false")
            }
            if (c.hasJvm) appendLine("    alias(libs.plugins.kotlinJvm) apply false")
            c.plugins.keys.forEach { appendLine("    alias(libs.plugins.$it) apply false") }
            appendLine("}")
        }
        f["gradle.properties"] = """
            org.gradle.jvmargs=-Xmx4g -Dfile.encoding=UTF-8
            org.gradle.caching=true
            kotlin.code.style=official
            android.useAndroidX=true
            android.nonTransitiveRClass=true
        """.trimIndent()
        f["gradle/wrapper/gradle-wrapper.properties"] = """
            distributionUrl=https\://services.gradle.org/distributions/gradle-8.12-bin.zip
        """.trimIndent()
        f[".gitignore"] = "build/\n.gradle/\n.idea/\nlocal.properties\n*.iml\nxcuserdata/\n"
        f["gradle/libs.versions.toml"] = versionCatalog(c)
        f["README.md"] = readme(c)
    }

    private fun versionCatalog(c: Ctx) = buildString {
        val v = linkedMapOf(
            "kotlin" to KOTLIN, "agp" to AGP, "compose" to COMPOSE,
            "coroutines" to "1.10.2", "activity" to "1.10.1",
        )
        c.activeLibs.forEach { v[it.versionKey] = it.version }
        if (c.has(Target.SERVER)) {
            v["ktor"] = KTOR
            v["logback"] = LOGBACK
        }
        appendLine("[versions]")
        v.forEach { (k, x) -> appendLine("$k = \"$x\"") }
        appendLine("\n[libraries]")
        appendLine("coroutines-core = { module = \"org.jetbrains.kotlinx:kotlinx-coroutines-core\", version.ref = \"coroutines\" }")
        appendLine("coroutines-test = { module = \"org.jetbrains.kotlinx:kotlinx-coroutines-test\", version.ref = \"coroutines\" }")
        appendLine("coroutines-swing = { module = \"org.jetbrains.kotlinx:kotlinx-coroutines-swing\", version.ref = \"coroutines\" }")
        appendLine("androidx-activity-compose = { module = \"androidx.activity:activity-compose\", version.ref = \"activity\" }")
        c.activeLibs.forEach { l ->
            l.arts.forEach { appendLine("${it.alias} = { module = \"${it.module}\", version.ref = \"${l.versionKey}\" }") }
        }
        if (c.has(Target.SERVER)) {
            appendLine("ktor-server-core = { module = \"io.ktor:ktor-server-core\", version.ref = \"ktor\" }")
            appendLine("ktor-server-netty = { module = \"io.ktor:ktor-server-netty\", version.ref = \"ktor\" }")
            appendLine("ktor-server-content-negotiation = { module = \"io.ktor:ktor-server-content-negotiation\", version.ref = \"ktor\" }")
            appendLine("ktor-serialization-kotlinx-json = { module = \"io.ktor:ktor-serialization-kotlinx-json\", version.ref = \"ktor\" }")
            appendLine("ktor-server-test-host = { module = \"io.ktor:ktor-server-test-host\", version.ref = \"ktor\" }")
            appendLine("logback = { module = \"ch.qos.logback:logback-classic\", version.ref = \"logback\" }")
        }
        appendLine("\n[plugins]")
        appendLine("kotlinMultiplatform = { id = \"org.jetbrains.kotlin.multiplatform\", version.ref = \"kotlin\" }")
        appendLine("kotlinAndroid = { id = \"org.jetbrains.kotlin.android\", version.ref = \"kotlin\" }")
        appendLine("kotlinJvm = { id = \"org.jetbrains.kotlin.jvm\", version.ref = \"kotlin\" }")
        appendLine("composeMultiplatform = { id = \"org.jetbrains.compose\", version.ref = \"compose\" }")
        appendLine("composeCompiler = { id = \"org.jetbrains.kotlin.plugin.compose\", version.ref = \"kotlin\" }")
        appendLine("androidApplication = { id = \"com.android.application\", version.ref = \"agp\" }")
        appendLine("androidLibrary = { id = \"com.android.library\", version.ref = \"agp\" }")
        c.plugins.values.forEach { appendLine("${it.alias} = { id = \"${it.id}\", version.ref = \"${it.versionRef}\" }") }
    }

    // ---------- KMP library module build script (shared and ui) ----------

    private fun kmpBuildScript(
        c: Ctx,
        namespace: String,
        compose: Boolean,
        jvm: Boolean,
        iosFramework: String?,
        plugins: List<String>,
        deps: Map<String, List<String>>,
        junit: Boolean = false,
    ) = buildString {
        if (c.has(Target.ANDROID)) appendLine("import org.jetbrains.kotlin.gradle.dsl.JvmTarget")
        if (c.has(Target.WEB)) appendLine("import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl")
        appendLine("\nplugins {")
        appendLine("    alias(libs.plugins.kotlinMultiplatform)")
        if (compose) {
            appendLine("    alias(libs.plugins.composeMultiplatform)")
            appendLine("    alias(libs.plugins.composeCompiler)")
        }
        if (c.has(Target.ANDROID)) appendLine("    alias(libs.plugins.androidLibrary)")
        plugins.forEach { appendLine("    alias(libs.plugins.$it)") }
        appendLine("}\n\nkotlin {")
        if (c.has(Target.ANDROID)) appendLine("    androidTarget { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }")
        if (c.has(Target.IOS)) {
            if (iosFramework != null) {
                appendLine("    listOf(iosX64(), iosArm64(), iosSimulatorArm64()).forEach {")
                appendLine("        it.binaries.framework { baseName = \"$iosFramework\"; isStatic = true }")
                appendLine("    }")
            } else {
                appendLine("    iosX64(); iosArm64(); iosSimulatorArm64()")
            }
        }
        if (jvm) appendLine("    jvm()")
        if (c.has(Target.WEB)) {
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
        if (c.has(Target.ANDROID)) {
            appendLine("\nandroid {")
            appendLine("    namespace = \"$namespace\"")
            appendLine("    compileSdk = 35")
            appendLine("    defaultConfig { minSdk = 24 }")
            appendLine("    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }")
            appendLine("}")
        }
        if (junit) appendLine("\ntasks.withType<Test>().configureEach { useJUnitPlatform() }")
    }

    // ---------- shared module: core + each slice's domain/data. No UI, so the server can use it ----------

    private fun sharedModule(c: Ctx, f: MutableMap<String, String>) {
        val deps = linkedMapOf<String, MutableList<String>>()
        fun add(set: String, d: String) = deps.getOrPut(set) { mutableListOf() }.add(d)
        add("commonMain", "api(libs.coroutines.core)")
        add("commonTest", "implementation(kotlin(\"test\"))")
        add("commonTest", "implementation(libs.coroutines.test)")
        c.activeLibs.forEach { l ->
            l.arts.forEach { a ->
                a.sets.filter(c::setActive).forEach { add(it, "${if (a.api) "api" else "implementation"}(libs.${a.alias.replace('-', '.')})") }
            }
        }
        f["shared/build.gradle.kts"] = kmpBuildScript(
            c, namespace = "${c.pkg}.shared", compose = false, jvm = c.hasJvm, iosFramework = null,
            plugins = c.sharedPlugins, deps = deps, junit = c.lib("kotest"),
        )

        val base = "shared/src/commonMain/kotlin/${c.dir}"
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
                data class Unexpected(val message: String) : DomainError
            }
        """.trimIndent()
    }

    // ---------- ui module: Compose app shell + each slice's presentation ----------

    private fun uiModule(c: Ctx, f: MutableMap<String, String>) {
        val deps = linkedMapOf(
            "commonMain" to listOf(
                "api(project(\":shared\"))",
                "api(compose.runtime)",
                "api(compose.ui)",
                "implementation(compose.foundation)",
                "implementation(compose.material3)",
            ),
        )
        f["ui/build.gradle.kts"] = kmpBuildScript(
            c, namespace = "${c.pkg}.ui", compose = true, jvm = c.has(Target.DESKTOP), iosFramework = IOS_FRAMEWORK,
            plugins = emptyList(), deps = deps,
        )

        val base = "ui/src/commonMain/kotlin/${c.dir}/ui"
        f["$base/Feature.kt"] = """
            package ${c.pkg}.ui

            import androidx.compose.runtime.Composable

            /** A vertical slice's entry point into the app shell. */
            interface Feature {
                val title: String
                @Composable fun Content()
            }
        """.trimIndent()
        val names = c.spec.slices.map { it.replaceFirstChar(Char::uppercase) }
        f["$base/AppFeatures.kt"] = buildString {
            appendLine("package ${c.pkg}.ui\n")
            c.spec.slices.zip(names).forEach { (s, x) -> appendLine("import ${c.pkg}.features.$s.${x}Feature") }
            appendLine("\nval features: List<Feature> = listOf(${names.joinToString { it + "Feature" }})")
        }
        f["$base/App.kt"] = """
            package ${c.pkg}.ui

            import androidx.compose.foundation.layout.Column
            import androidx.compose.material3.MaterialTheme
            import androidx.compose.material3.Surface
            import androidx.compose.material3.Tab
            import androidx.compose.material3.TabRow
            import androidx.compose.material3.Text
            import androidx.compose.runtime.Composable
            import androidx.compose.runtime.getValue
            import androidx.compose.runtime.mutableStateOf
            import androidx.compose.runtime.remember
            import androidx.compose.runtime.setValue

            @Composable
            fun App() = MaterialTheme {
                Surface {
                    var selected by remember { mutableStateOf(0) }
                    Column {
                        TabRow(selectedTabIndex = selected) {
                            features.forEachIndexed { i, f ->
                                Tab(selected = selected == i, onClick = { selected = i }, text = { Text(f.title) })
                            }
                        }
                        features[selected].Content()
                    }
                }
            }
        """.trimIndent()
        if (c.has(Target.IOS)) f["ui/src/iosMain/kotlin/${c.appDir("ios")}/MainViewController.kt"] = """
            package ${c.appPkg("ios")}

            import androidx.compose.ui.window.ComposeUIViewController
            import ${c.pkg}.ui.App

            fun MainViewController() = ComposeUIViewController { App() }
        """.trimIndent()
    }

    // ---------- one vertical slice: domain / data / presentation ----------

    private fun slice(c: Ctx, s: String, f: MutableMap<String, String>) {
        val x = s.replaceFirstChar(Char::uppercase)
        val p = "${c.pkg}.features.$s"
        val b = "shared/src/commonMain/kotlin/${c.dir}/features/$s"
        val ub = "ui/src/commonMain/kotlin/${c.dir}/features/$s"
        val arrow = c.lib("arrow")

        f["$b/domain/${x}Item.kt"] = "package $p.domain\n\ndata class ${x}Item(val id: String, val title: String)\n"
        f["$b/domain/${x}Repository.kt"] =
            "package $p.domain\n\ninterface ${x}Repository {\n    suspend fun all(): List<${x}Item>\n}\n"
        f["$b/domain/Get${x}ItemsUseCase.kt"] = if (arrow) """
            package $p.domain

            import arrow.core.Either
            import ${c.pkg}.core.DomainError

            class Get${x}ItemsUseCase(private val repository: ${x}Repository) {
                suspend operator fun invoke(): Either<DomainError, List<${x}Item>> =
                    Either.catch { repository.all() }.mapLeft { DomainError.Unexpected(it.message ?: "unknown") }
            }
        """.trimIndent() else """
            package $p.domain

            class Get${x}ItemsUseCase(private val repository: ${x}Repository) {
                suspend operator fun invoke(): Result<List<${x}Item>> = runCatching { repository.all() }
            }
        """.trimIndent()
        f["$b/data/InMemory${x}Repository.kt"] = """
            package $p.data

            import $p.domain.${x}Item
            import $p.domain.${x}Repository

            class InMemory${x}Repository : ${x}Repository {
                override suspend fun all() = listOf(${x}Item("1", "First $s item"), ${x}Item("2", "Second $s item"))
            }
        """.trimIndent()
        val fold = if (arrow) "getItems().fold({ ${x}UiState.Error(it.toString()) }, { ${x}UiState.Loaded(it) })"
        else "getItems().fold(onSuccess = { ${x}UiState.Loaded(it) }, onFailure = { ${x}UiState.Error(it.message ?: \"error\") })"
        if (c.hasUi) f["$ub/presentation/${x}Screen.kt"] = """
            package $p.presentation

            import androidx.compose.foundation.layout.Column
            import androidx.compose.material3.Text
            import androidx.compose.runtime.Composable
            import androidx.compose.runtime.LaunchedEffect
            import androidx.compose.runtime.getValue
            import androidx.compose.runtime.mutableStateOf
            import androidx.compose.runtime.remember
            import androidx.compose.runtime.setValue
            import ${c.pkg}.core.logging.AppLogger
            import $p.domain.Get${x}ItemsUseCase
            import $p.domain.${x}Item

            sealed interface ${x}UiState {
                data object Loading : ${x}UiState
                data class Loaded(val items: List<${x}Item>) : ${x}UiState
                data class Error(val message: String) : ${x}UiState
            }

            @Composable
            fun ${x}Screen(getItems: Get${x}ItemsUseCase) {
                var state by remember { mutableStateOf<${x}UiState>(${x}UiState.Loading) }
                LaunchedEffect(Unit) {
                    state = $fold
                    AppLogger.d { "$s slice loaded" }
                }
                when (val s = state) {
                    ${x}UiState.Loading -> Text("Loading…")
                    is ${x}UiState.Loaded -> Column { s.items.forEach { Text(it.title) } }
                    is ${x}UiState.Error -> Text(s.message)
                }
            }
        """.trimIndent()
        // Slice composition root: the only place that knows the concrete data layer.
        if (c.hasUi) f["$ub/${x}Feature.kt"] = """
            package $p

            import androidx.compose.runtime.Composable
            import ${c.pkg}.ui.Feature
            import $p.data.InMemory${x}Repository
            import $p.domain.Get${x}ItemsUseCase
            import $p.presentation.${x}Screen

            object ${x}Feature : Feature {
                override val title = "$x"
                private val getItems = Get${x}ItemsUseCase(InMemory${x}Repository())
                @Composable override fun Content() = ${x}Screen(getItems)
            }
        """.trimIndent()
        f["shared/src/commonTest/kotlin/${c.dir}/features/$s/Get${x}ItemsUseCaseTest.kt"] = if (c.lib("kotest")) """
            package $p

            import $p.data.InMemory${x}Repository
            import $p.domain.Get${x}ItemsUseCase
            import io.kotest.core.spec.style.StringSpec
            import io.kotest.matchers.collections.shouldNotBeEmpty

            class Get${x}ItemsUseCaseTest : StringSpec({
                "returns items" {
                    Get${x}ItemsUseCase(InMemory${x}Repository())().getOrNull()!!.shouldNotBeEmpty()
                }
            })
        """.trimIndent() else """
            package $p

            import $p.data.InMemory${x}Repository
            import $p.domain.Get${x}ItemsUseCase
            import kotlinx.coroutines.test.runTest
            import kotlin.test.Test
            import kotlin.test.assertTrue

            class Get${x}ItemsUseCaseTest {
                @Test fun returnsItems() = runTest {
                    assertTrue(Get${x}ItemsUseCase(InMemory${x}Repository())().getOrNull()!!.isNotEmpty())
                }
            }
        """.trimIndent()
    }

    // ---------- platform entry points ----------

    private fun androidApp(c: Ctx, f: MutableMap<String, String>) {
        f["androidApp/build.gradle.kts"] = """
            plugins {
                alias(libs.plugins.androidApplication)
                alias(libs.plugins.kotlinAndroid)
                alias(libs.plugins.composeMultiplatform)
                alias(libs.plugins.composeCompiler)
            }
            android {
                namespace = "${c.appPkg("android")}"
                compileSdk = 35
                defaultConfig { applicationId = "${c.pkg}"; minSdk = 24; targetSdk = 35; versionCode = 1; versionName = "1.0" }
                compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
                kotlinOptions { jvmTarget = "17" }
            }
            dependencies {
                implementation(project(":ui"))
                implementation(libs.androidx.activity.compose)
            }
        """.trimIndent()
        f["androidApp/src/main/AndroidManifest.xml"] = """
            <?xml version="1.0" encoding="utf-8"?>
            <manifest xmlns:android="http://schemas.android.com/apk/res/android">
                <application android:label="${c.spec.name.replace("\"", "")}" android:theme="@android:style/Theme.Material.Light.NoActionBar">
                    <activity android:name=".MainActivity" android:exported="true">
                        <intent-filter>
                            <action android:name="android.intent.action.MAIN" />
                            <category android:name="android.intent.category.LAUNCHER" />
                        </intent-filter>
                    </activity>
                </application>
            </manifest>
        """.trimIndent()
        f["androidApp/src/main/kotlin/${c.appDir("android")}/MainActivity.kt"] = """
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

    private fun desktopApp(c: Ctx, f: MutableMap<String, String>) {
        f["desktopApp/build.gradle.kts"] = """
            plugins {
                alias(libs.plugins.kotlinJvm)
                alias(libs.plugins.composeMultiplatform)
                alias(libs.plugins.composeCompiler)
            }
            dependencies {
                implementation(project(":ui"))
                implementation(compose.desktop.currentOs)
                implementation(libs.coroutines.swing)
            }
            compose.desktop { application { mainClass = "${c.appPkg("desktop")}.MainKt" } }
        """.trimIndent()
        f["desktopApp/src/main/kotlin/${c.appDir("desktop")}/Main.kt"] = """
            package ${c.appPkg("desktop")}

            import androidx.compose.ui.window.Window
            import androidx.compose.ui.window.application
            import ${c.pkg}.ui.App

            fun main() = application {
                Window(onCloseRequest = ::exitApplication, title = "${c.slug}") { App() }
            }
        """.trimIndent()
    }

    private fun webApp(c: Ctx, f: MutableMap<String, String>) {
        f["webApp/build.gradle.kts"] = """
            import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

            plugins {
                alias(libs.plugins.kotlinMultiplatform)
                alias(libs.plugins.composeMultiplatform)
                alias(libs.plugins.composeCompiler)
            }
            kotlin {
                @OptIn(ExperimentalWasmDsl::class)
                wasmJs {
                    outputModuleName = "webApp"
                    browser()
                    binaries.executable()
                }
                sourceSets { wasmJsMain.dependencies { implementation(project(":ui")) } }
            }
        """.trimIndent()
        f["webApp/src/wasmJsMain/kotlin/${c.appDir("web")}/Main.kt"] = """
            package ${c.appPkg("web")}

            import androidx.compose.ui.ExperimentalComposeUiApi
            import androidx.compose.ui.window.ComposeViewport
            import ${c.pkg}.ui.App
            import kotlinx.browser.document

            @OptIn(ExperimentalComposeUiApi::class)
            fun main() {
                ComposeViewport(document.body!!) { App() }
            }
        """.trimIndent()
        f["webApp/src/wasmJsMain/resources/index.html"] = """
            <!DOCTYPE html>
            <html><head><meta charset="UTF-8"><meta name="viewport" content="width=device-width, initial-scale=1.0">
            <title>${c.slug}</title><script src="webApp.js"></script></head><body></body></html>
        """.trimIndent()
    }

    private fun iosApp(c: Ctx, f: MutableMap<String, String>) {
        f["iosApp/iosApp/iOSApp.swift"] = """
            import SwiftUI

            @main
            struct iOSApp: App {
                var body: some Scene { WindowGroup { ContentView() } }
            }
        """.trimIndent()
        f["iosApp/iosApp/ContentView.swift"] = """
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
        f["iosApp/README.md"] = """
            # iOS host app
            Xcode projects can't be generated reliably from text, so:
            1. In Xcode create an iOS App named `iosApp` in this folder, then replace its Swift files with the ones here.
            2. Add a Run Script build phase *before* "Compile Sources":
               `cd "${'$'}SRCROOT/.." && ./gradlew :ui:embedAndSignAppleFrameworkForXcode`
            3. Set "User Script Sandboxing" to No and add `${'$'}(SRCROOT)/../ui/build/xcode-frameworks/${'$'}(CONFIGURATION)/${'$'}(SDK_NAME)` to Framework Search Paths.
        """.trimIndent()
    }

    // ---------- server: Ktor app reusing each slice's domain layer ----------

    private fun server(c: Ctx, f: MutableMap<String, String>) {
        val sp = c.appPkg("server")
        val sd = c.appDir("server")
        val slices = c.spec.slices.map { it to it.replaceFirstChar(Char::uppercase) }
        f["server/build.gradle.kts"] = """
            plugins {
                alias(libs.plugins.kotlinJvm)
                alias(libs.plugins.kotlinSerialization)
                application
            }
            application { mainClass.set("$sp.ApplicationKt") }
            dependencies {
                implementation(project(":shared"))
                implementation(libs.ktor.server.core)
                implementation(libs.ktor.server.netty)
                implementation(libs.ktor.server.content.negotiation)
                implementation(libs.ktor.serialization.kotlinx.json)
                implementation(libs.logback)
                testImplementation(kotlin("test"))
                testImplementation(libs.ktor.server.test.host)
            }
            tasks.test { useJUnitPlatform() }
        """.trimIndent()
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
            appendLine("\n/** Port, host and modules come from src/main/resources/application.conf. */")
            appendLine("fun main(args: Array<String>) = EngineMain.main(args)\n")
            appendLine("fun Application.module() {")
            appendLine("    install(ContentNegotiation) { json() }")
            appendLine("    routing {")
            appendLine("        get(\"/\") { call.respondText(\"${c.slug} server is running\") }")
            slices.forEach { (s, _) -> appendLine("        ${s}Routes()") }
            appendLine("    }")
            appendLine("}")
        }
        f["server/src/main/kotlin/$sd/ItemDto.kt"] = """
            package $sp

            import kotlinx.serialization.Serializable

            /** Wire format for slice items. Keeps serialization out of the domain layer. */
            @Serializable
            data class ItemDto(val id: String, val title: String)
        """.trimIndent()
        val arrow = c.lib("arrow")
        slices.forEach { (s, x) ->
            val p = "${c.pkg}.features.$s"
            val ok = "call.respond(items.map { ItemDto(it.id, it.title) })"
            f["server/src/main/kotlin/$sd/${x}Routes.kt"] = buildString {
                appendLine("package $sp\n")
                appendLine("import io.ktor.http.HttpStatusCode")
                appendLine("import io.ktor.server.response.respond")
                appendLine("import io.ktor.server.routing.Route")
                appendLine("import io.ktor.server.routing.get")
                appendLine("import $p.data.InMemory${x}Repository")
                appendLine("import $p.domain.Get${x}ItemsUseCase")
                appendLine("\n/** HTTP adapter for the `$s` slice: GET /api/$s */")
                appendLine("fun Route.${s}Routes() {")
                appendLine("    val getItems = Get${x}ItemsUseCase(InMemory${x}Repository())")
                appendLine("    get(\"/api/$s\") {")
                appendLine("        getItems().fold(")
                if (arrow) {
                    appendLine("            { call.respond(HttpStatusCode.InternalServerError, it.toString()) },")
                    appendLine("            { items -> $ok },")
                } else {
                    appendLine("            onSuccess = { items -> $ok },")
                    appendLine("            onFailure = { call.respond(HttpStatusCode.InternalServerError, it.message ?: \"error\") },")
                }
                appendLine("        )")
                appendLine("    }")
                appendLine("}")
            }
        }
        f["server/src/main/resources/application.conf"] = """
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
        """.trimIndent()
        f["server/src/main/resources/logback.xml"] = """
            <configuration>
                <appender name="STDOUT" class="ch.qos.logback.core.ConsoleAppender">
                    <encoder><pattern>%d{HH:mm:ss.SSS} %-5level %logger{36} - %msg%n</pattern></encoder>
                </appender>
                <root level="INFO"><appender-ref ref="STDOUT" /></root>
                <logger name="io.netty" level="WARN" />
            </configuration>
        """.trimIndent()
        f["server/src/test/kotlin/$sd/ApplicationTest.kt"] = buildString {
            appendLine("package $sp\n")
            appendLine("import io.ktor.client.request.get")
            appendLine("import io.ktor.http.HttpStatusCode")
            appendLine("import io.ktor.server.config.ApplicationConfig")
            appendLine("import io.ktor.server.testing.testApplication")
            appendLine("import kotlin.test.Test")
            appendLine("import kotlin.test.assertEquals")
            appendLine("\nclass ApplicationTest {")
            slices.forEach { (s, x) ->
                appendLine("    @Test fun ${s}Endpoint() = testApplication {")
                appendLine("        environment { config = ApplicationConfig(\"application.conf\") }")
                appendLine("        assertEquals(HttpStatusCode.OK, client.get(\"/api/$s\").status)")
                appendLine("    }")
            }
            appendLine("}")
        }
    }

    private fun readme(c: Ctx) = """
        # ${c.spec.name}
        Generated Kotlin Multiplatform project (Compose Multiplatform UI).

        Targets: ${c.spec.targets.joinToString { it.label }}
        Libraries: ${c.spec.libraries.sorted().joinToString().ifEmpty { "none" }}

        ## Setup
        This ZIP has no Gradle wrapper jar. With Gradle installed, run `gradle wrapper --gradle-version 8.12` once.

        ## Architecture
        Clean architecture inside vertical slices. Each slice owns all its layers, split across two modules:

        ```
        shared/   Kotlin only, no UI. Used by the apps and the server.
          src/commonMain/kotlin/${c.dir}/
            core/                 shared kernel (logging, errors)
            features/<slice>/
              domain/             entities, repository interfaces, use cases (no framework imports)
              data/               repository implementations
        ui/       Compose Multiplatform. Depends on shared.
          src/commonMain/kotlin/${c.dir}/
            ui/                   app shell: App, Feature, AppFeatures (registry of slices)
            features/<slice>/
              presentation/       UI state + Compose screens
              <Slice>Feature.kt   slice composition root
        ```
        Dependency rule: presentation -> domain <- data. Slices must not import each other; share through `core`.
        The `ui` module only exists when a UI platform is selected.

        Platform entry points live in `${c.pkg}.app.<platform>` (android, ios, desktop, web, server).
        The server depends on `shared` only and exposes each slice over HTTP (`GET /api/<slice>`).
        Its port is set in `server/src/main/resources/application.conf` (default $SERVER_PORT, or the `PORT` env var).

        ## Run
    """.trimIndent() + "\n" + buildList {
        if (c.has(Target.DESKTOP)) add("- Desktop: `./gradlew :desktopApp:run`")
        if (c.has(Target.ANDROID)) add("- Android: `./gradlew :androidApp:installDebug`")
        if (c.has(Target.WEB)) add("- Web: `./gradlew :webApp:wasmJsBrowserDevelopmentRun`")
        if (c.has(Target.IOS)) add("- iOS: see `iosApp/README.md`")
        if (c.has(Target.SERVER)) add("- Server: `./gradlew :server:run`, then open http://localhost:$SERVER_PORT/api/${c.spec.slices.first()}")
        add("- Tests: `./gradlew allTests`" + if (c.has(Target.SERVER)) " and `./gradlew :server:test`" else "")
    }.joinToString("\n", postfix = "\n")
}
