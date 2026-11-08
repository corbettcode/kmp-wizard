package kmpwizard.generator

import kmpwizard.ProjectSpec
import kmpwizard.TODO_LIBRARIES
import kmpwizard.TODO_SLICE
import kmpwizard.Target
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
 *   core/            code shared by clients and server: each slice's domain + data (no UI)
 *   app/sharedUI/    Compose Multiplatform shell + each slice's presentation (exports the iOS framework)
 *   app/androidApp/  app/desktopApp/  app/webApp/  app/iosApp/   entry points
 *   server/          Ktor server, depends on :core only
 */

// Pinned versions of the GENERATED project. Bump these in one place.
// When bumping GRADLE, refresh the wrapper files in resources/kmpwizard/wrapper/ too
// (run `./gradlew wrapper --gradle-version <new>` in this repo, then copy gradlew, gradlew.bat, gradle-wrapper.jar).
private const val GRADLE = "9.9.0"
private const val KOTLIN = "2.4.21"
private const val AGP = "9.1.1"
private const val COMPOSE = "1.12.2"
private const val KTOR = "3.6.1"
private const val LOGBACK = "1.6.6"
private const val COROUTINES = "1.11.0"
private const val ACTIVITY = "1.13.0"
private const val COMPOSE_MATERIAL3 = "1.9.0"
private const val KOIN = "4.2.2"
// Compose 1.12 Android artifacts require compileSdk 37.
private const val COMPILE_SDK = 37
private const val TARGET_SDK = 36
private const val MIN_SDK = 24
private const val SERVER_PORT = 8081
private const val MONGODB = "5.14.0"
/** Name Swift code imports; matches the framework baseName, not the module name. */
private const val IOS_FRAMEWORK = "sharedUI"
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

/** [api] = true when the library's types appear in core's public API (e.g. use case return types). */
private class Art(val alias: String, val module: String, val sets: List<String>, val api: Boolean = false)
private class Plug(val alias: String, val id: String, val versionRef: String)

/**
 * Describes a library entry used to group related dependency artifacts, plugin definitions,
 * and version references under a shared library version.
 */
private class Library(
    val versionKey: String
    , val version: String
    , val arts: List<Art>
    , val plugins: List<Plug> = emptyList()
    /** Versions the plugins need besides [versionKey]. */
    , val extraVersions: Map<String, String> = emptyMap()
)

private val wiring = mapOf(
    "arrow" to Library(
        "arrow"
        , "2.3.0.1"
        , listOf(Art(
            "arrow-core"
            , "io.arrow-kt:arrow-core"
            , listOf(
                "commonMain"
            )
            , api = true
        ) )
    )

    , "datetime" to Library(
        "datetime"
        , "0.8.0"
        , listOf(Art(
            "datetime"
            , "org.jetbrains.kotlinx:kotlinx-datetime"
            , listOf(
                "commonMain"
            )
        ))
    )

    , "kermit" to Library(
        "kermit"
        , "2.2.0"
        , listOf(
            Art(
                "kermit"
                , "co.touchlab:kermit"
                , listOf("commonMain")
            )
        )
    )

    // Kotest 6: the `io.kotest` plugin plus KSP discover specs on non-JVM targets; JVM runs on JUnit Platform.
    // Koin: core gets koin-core (+ koin-android); the Compose artifacts go in app/sharedUI (see sharedUiModule).
    // Arts with no source sets are catalog-only.
    , "koin" to Library(
        "koin"
        , KOIN
        , listOf(
            Art("koin-android", "io.insert-koin:koin-android", listOf("androidMain"))
            , Art("koin-compose", "io.insert-koin:koin-compose", emptyList())
            , Art("koin-composeNavigation3", "io.insert-koin:koin-compose-navigation3", emptyList())
            , Art("koin-composeViewmodel", "io.insert-koin:koin-compose-viewmodel", emptyList())
            , Art("koin-core", "io.insert-koin:koin-core", listOf("commonMain"), api = true)
            , Art("koin-coreViewmodel", "io.insert-koin:koin-core-viewmodel", emptyList())
        )
    )

    , "kotest" to Library(
        "kotest"
        , "6.2.6"
        , listOf(
            Art(
                "kotest-assertions"
                , "io.kotest:kotest-assertions-core"
                , listOf(
                    "commonTest"
                )
            )
            , Art(
                "kotest-engine"
                , "io.kotest:kotest-framework-engine"
                , listOf(
                    "commonTest"
                )
            )
            , Art(
                "kotest-runner"
                , "io.kotest:kotest-runner-junit5"
                , listOf(
                    "jvmTest"
                )
            )
        )
        , plugins = listOf(Plug("kotest", "io.kotest", "kotest"), Plug("ksp", "com.google.devtools.ksp", "ksp"))
        , extraVersions = mapOf("ksp" to "2.3.13")
    )

    , "ktor" to Library(
        "ktor"
        , KTOR
        , listOf(
            Art(
                "ktor-client-core"
                , "io.ktor:ktor-client-core"
                , listOf(
                    "commonMain"
                )
            )
            , Art(
                "ktor-client-okhttp"
                , "io.ktor:ktor-client-okhttp"
                , listOf(
                    "androidMain"
                    , "jvmMain"
                )
            )
            , Art(
                "ktor-client-darwin"
                , "io.ktor:ktor-client-darwin"
                , listOf(
                    "iosMain"
                )
            )
            , Art(
                "ktor-client-js"
                , "io.ktor:ktor-client-js"
                , listOf(
                    "webMain"
                )
            )
        )
    )

    // Occurrent publishes JVM artifacts only.
    , "occurrent" to Library(
        "occurrent"
        , "0.20.5"
        , listOf(
            Art(
                "occurrent-eventstore-inmemory"
                , "org.occurrent:eventstore-inmemory"
                , listOf(
                    "androidMain"
                    , "jvmMain"
                )
            )
        )
    )

    , "serialization" to Library(
        "serialization"
        , "1.12.0"
        , listOf(
            Art(
                "serialization-json"
                , "org.jetbrains.kotlinx:kotlinx-serialization-json"
                , listOf(
                    "commonMain"
                )
            )
        )
        , plugins = listOf(
            Plug(
                "kotlinSerialization"
                , "org.jetbrains.kotlin.plugin.serialization"
                , "kotlin"
            )
        )
    )

    , "turbine" to Library(
        "turbine"
        , "1.2.1"
        , listOf(Art(
            "turbine"
            , "app.cash.turbine:turbine"
            , listOf(
                "commonTest")
        ))
    )
)

// Desktop and Server share core's single `jvm()` target.
private val jvmTargets = setOf(Target.DESKTOP, Target.SERVER)

// Targets with a Compose UI; these get app/sharedUI.
private val uiTargets = setOf(Target.ANDROID, Target.IOS, Target.DESKTOP, Target.WEB)

/** Source sets that only exist when one of these targets is selected. */
private val setTargets = mapOf(
    "androidMain" to setOf(Target.ANDROID),
    "iosMain" to setOf(Target.IOS),
    "jvmMain" to jvmTargets,
    "jvmTest" to jvmTargets,
    "webMain" to setOf(Target.WEB),
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
    val todo get() = spec.sampleTodo
    /** The Todo sample's HTTP client needs Ktor and kotlinx.serialization in core. */
    val libIds: Set<String> = spec.libraries + if (spec.sampleTodo) TODO_LIBRARIES else emptySet()
    fun lib(id: String) = id in libIds
    val activeLibs = libIds.sorted().mapNotNull { wiring[it] }
    /** User slices plus the Todo sample's slice, in tab order. */
    val allSlices get() = spec.slices + if (spec.sampleTodo) listOf(TODO_SLICE) else emptyList()
    fun setActive(set: String) = setTargets[set]?.let { ts -> ts.any(::has) } ?: true
    /** Gradle plugins applied somewhere in the project, keyed by catalog alias. */
    val plugins: Map<String, Plug> = buildMap {
        if (has(Target.SERVER))
            put("kotlinSerialization", Plug("kotlinSerialization", "org.jetbrains.kotlin.plugin.serialization", "kotlin"))
        activeLibs.flatMap { it.plugins }.forEach { put(it.alias, it) }
    }
    /** Plugins from selected libraries, applied to core. */
    val corePlugins = activeLibs.flatMap { it.plugins }.map { it.alias }.distinct()
}

class ProjectGenerator {
    fun generate(spec: ProjectSpec): ByteArray {
        val c = Ctx(spec)
        val files = linkedMapOf<String, String>()
        rootFiles(c, files)
        coreModule(c, files)
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

    private fun rootFiles(c: Ctx, f: MutableMap<String, String>) {
        val mods = buildList {
            add(":core")
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
        if (c.todo) f["docker-compose.yml"] = """
            # Local MongoDB for the Todo sample: docker compose up -d
            services:
              mongo:
                image: mongo:8
                ports:
                  - "27017:27017"
                volumes:
                  - mongo-data:/data/db
            volumes:
              mongo-data:
        """.trimIndent() + "\n"
        f[".gitignore"] = "build/\n.gradle/\n.idea/\n.kotlin/\nlocal.properties\n*.iml\nxcuserdata/\n"
        f["gradle/libs.versions.toml"] = versionCatalog(c)
        f["README.md"] = readme(c)
    }

    /** Each section's entries are sorted by key. */
    private fun versionCatalog(c: Ctx) = buildString {
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
        if (c.has(Target.SERVER)) {
            v["ktor"] = KTOR
            v["logback"] = LOGBACK
        }
        if (c.todo) v["mongodb"] = MONGODB

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
            lib("logback", "ch.qos.logback:logback-classic", "logback")
        }
        if (c.todo) {
            lib("ktor-client-content-negotiation", "io.ktor:ktor-client-content-negotiation", "ktor")
            lib("ktor-server-cors", "io.ktor:ktor-server-cors", "ktor")
            lib("mongodb-driver-kotlin-coroutine", "org.mongodb:mongodb-driver-kotlin-coroutine", "mongodb")
        }

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

    // ---------- KMP library build script (core and app/sharedUI) ----------

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
        if (c.has(Target.ANDROID)) appendLine("    alias(libs.plugins.androidMultiplatformLibrary)")
        if (compose) {
            appendLine("    alias(libs.plugins.composeMultiplatform)")
            appendLine("    alias(libs.plugins.composeCompiler)")
        }
        plugins.forEach { appendLine("    alias(libs.plugins.$it)") }
        appendLine("}\n\nkotlin {")
        if (c.has(Target.ANDROID)) {
            appendLine("    android {")
            appendLine("        namespace = \"$namespace\"")
            appendLine("        compileSdk = $COMPILE_SDK")
            appendLine("        minSdk = $MIN_SDK")
            appendLine("        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }")
            appendLine("    }")
        }
        if (c.has(Target.IOS)) {
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

    // ---------- core: shared by clients and server. Each slice's domain + data, no UI ----------

    private fun coreModule(c: Ctx, f: MutableMap<String, String>) {
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

    // ---------- app/sharedUI: Compose shell + each slice's presentation ----------

    private fun sharedUiModule(c: Ctx, f: MutableMap<String, String>) {
        val deps = linkedMapOf(
            "commonMain" to listOf(
                "api(projects.core)",
                "api(libs.compose.runtime)",
                "api(libs.compose.ui)",
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
        f["$base/Feature.kt"] = """
            package ${c.pkg}.ui

            import androidx.compose.runtime.Composable

            /** A vertical slice's entry point into the app shell. */
            interface Feature {
                val title: String
                @Composable fun Content()
            }
        """.trimIndent()
        val names = c.allSlices.map { it.replaceFirstChar(Char::uppercase) }
        f["$base/AppFeatures.kt"] = buildString {
            appendLine("package ${c.pkg}.ui\n")
            c.allSlices.zip(names).forEach { (s, x) -> appendLine("import ${c.pkg}.features.$s.${x}Feature") }
            appendLine("\nval features: List<Feature> = listOf(${names.joinToString { it + "Feature" }})")
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

            @Composable
            fun App() = MaterialTheme {
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
        // iOS has no Gradle entry-point module; its view controller lives with the UI that the framework exports.
        if (c.has(Target.IOS)) f["app/sharedUI/src/iosMain/kotlin/${c.appDir("ios")}/MainViewController.kt"] = """
            package ${c.appPkg("ios")}

            import androidx.compose.ui.window.ComposeUIViewController
            import ${c.pkg}.ui.App

            fun MainViewController() = ComposeUIViewController { App() }
        """.trimIndent()
    }

    // ---------- one vertical slice: domain + data in core, presentation in app/sharedUI ----------

    private fun slice(c: Ctx, s: String, f: MutableMap<String, String>) {
        val x = s.replaceFirstChar(Char::uppercase)
        val p = "${c.pkg}.features.$s"
        val b = "core/src/commonMain/kotlin/${c.dir}/features/$s"
        val ub = "app/sharedUI/src/commonMain/kotlin/${c.dir}/features/$s"
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
        f["core/src/commonTest/kotlin/${c.dir}/features/$s/Get${x}ItemsUseCaseTest.kt"] = if (c.lib("kotest")) """
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

    // ---------- app/: platform entry points ----------

    private fun androidApp(c: Ctx, f: MutableMap<String, String>) {
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

    private fun desktopApp(c: Ctx, f: MutableMap<String, String>) {
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
                implementation(libs.coroutines.swing)
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

    private fun webApp(c: Ctx, f: MutableMap<String, String>) {
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

    private fun iosApp(c: Ctx, f: MutableMap<String, String>) {
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

    // ---------- server: Ktor app reusing each slice's domain + data from core ----------

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
                implementation(projects.core)
                implementation(libs.ktor.server.core)
                implementation(libs.ktor.server.netty)
                implementation(libs.ktor.server.content.negotiation)
                implementation(libs.ktor.serialization.kotlinx.json)
                implementation(libs.logback)${if (c.todo) """
                implementation(libs.ktor.server.cors)
                implementation(libs.mongodb.driver.kotlin.coroutine)""" else ""}
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
            if (c.todo) {
                appendLine("import io.ktor.http.HttpHeaders")
                appendLine("import io.ktor.http.HttpMethod")
                appendLine("import io.ktor.server.application.ApplicationStopped")
                appendLine("import io.ktor.server.plugins.cors.routing.CORS")
                appendLine("import com.mongodb.kotlin.client.coroutine.MongoClient")
                appendLine("import $sp.todo.MongoTodoRepository")
                appendLine("import $sp.todo.todoRoutes")
            }
            appendLine("\n/** Port, host and modules come from src/main/resources/application.conf. */")
            appendLine("fun main(args: Array<String>) = EngineMain.main(args)\n")
            appendLine("fun Application.module() {")
            appendLine("    install(ContentNegotiation) { json() }")
            if (c.todo) {
                appendLine("    // The web app is served from another port, so browsers need CORS to call this API.")
                appendLine("    install(CORS) {")
                appendLine("        anyHost()")
                appendLine("        allowHeader(HttpHeaders.ContentType)")
                appendLine("        allowMethod(HttpMethod.Patch)")
                appendLine("        allowMethod(HttpMethod.Delete)")
                appendLine("    }")
                appendLine("    val mongoConfig = environment.config.config(\"mongo\")")
                appendLine("    // The driver connects lazily, so the server starts even when MongoDB is down.")
                appendLine("    val mongo = MongoClient.create(mongoConfig.property(\"uri\").getString())")
                appendLine("    monitor.subscribe(ApplicationStopped) { mongo.close() }")
                appendLine("    val todos = MongoTodoRepository(mongo.getDatabase(mongoConfig.property(\"database\").getString()))")
            }
            appendLine("    routing {")
            appendLine("        get(\"/\") { call.respondText(\"${c.slug} server is running\") }")
            slices.forEach { (s, _) -> appendLine("        ${s}Routes()") }
            if (c.todo) appendLine("        todoRoutes(todos)")
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
        if (c.todo) todoServer(c, f)
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
        """.trimIndent() + if (c.todo) "\n" + """
            mongo {
                uri = "mongodb://localhost:27017"
                uri = ${'$'}{?MONGODB_URI}
                database = "${c.slug.replace('-', '_')}"
                database = ${'$'}{?MONGODB_DATABASE}
            }
        """.trimIndent() else ""
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
            appendLine("    @Test fun root() = testApplication {")
            appendLine("        environment { config = ApplicationConfig(\"application.conf\") }")
            appendLine("        assertEquals(HttpStatusCode.OK, client.get(\"/\").status)")
            appendLine("    }")
            slices.forEach { (s, _) ->
                appendLine("    @Test fun ${s}Endpoint() = testApplication {")
                appendLine("        environment { config = ApplicationConfig(\"application.conf\") }")
                appendLine("        assertEquals(HttpStatusCode.OK, client.get(\"/api/$s\").status)")
                appendLine("    }")
            }
            appendLine("}")
        }
    }

    // ---------- Todo sample: clients -> HTTP -> server -> MongoDB, sharing domain + DTOs via core ----------

    /** Result type the Todo use cases return, depending on whether Arrow is selected. */
    private fun todoResult(c: Ctx, t: String) = if (c.lib("arrow")) "Either<DomainError, $t>" else "Result<$t>"

    private fun todoResultImports(c: Ctx) =
        if (c.lib("arrow")) "import arrow.core.Either\nimport ${c.pkg}.core.DomainError\n" else ""

    private fun todoSample(c: Ctx, f: MutableMap<String, String>) {
        val s = TODO_SLICE
        val p = "${c.pkg}.features.$s"
        val b = "core/src/commonMain/kotlin/${c.dir}/features/$s"
        val ub = "app/sharedUI/src/commonMain/kotlin/${c.dir}/features/$s"
        val arrow = c.lib("arrow")
        val imports = todoResultImports(c)
        fun r(t: String) = todoResult(c, t)

        // Where clients find the server. The Android emulator reaches the host machine at 10.0.2.2.
        val core = "core/src/%s/kotlin/${c.dir}/core"
        f["${core.format("commonMain")}/ServerUrl.kt"] = """
            package ${c.pkg}.core

            /** Base URL of the Ktor server, per platform. */
            expect val defaultServerUrl: String
        """.trimIndent()
        listOf("androidMain" to "android", "iosMain" to "ios", "jvmMain" to "jvm", "webMain" to "web")
            .filter { c.setActive(it.first) }
            .forEach { (set, name) ->
                val host = if (set == "androidMain") "10.0.2.2" else "localhost"
                f["${core.format(set)}/ServerUrl.$name.kt"] =
                    "package ${c.pkg}.core\n\nactual val defaultServerUrl: String = \"http://$host:$SERVER_PORT\"\n"
            }

        // ----- domain -----
        f["$b/domain/Todo.kt"] = "package $p.domain\n\ndata class Todo(val id: String, val title: String, val done: Boolean)\n"
        f["$b/domain/TodoRepository.kt"] = """
            package $p.domain

            interface TodoRepository {
                suspend fun all(): List<Todo>
                suspend fun add(title: String): Todo
                /** Returns null when no todo has this id. */
                suspend fun setDone(id: String, done: Boolean): Todo?
                /** Returns false when no todo has this id. */
                suspend fun delete(id: String): Boolean
            }
        """.trimIndent()
        f["$b/domain/Attempt.kt"] = if (arrow) """
            package $p.domain

            import arrow.core.Either
            import ${c.pkg}.core.DomainError

            /** Runs [block], turning failures into [DomainError]s. */
            internal inline fun <T> attempt(block: () -> T): Either<DomainError, T> =
                Either.catch(block).mapLeft {
                    when (it) {
                        is IllegalArgumentException -> DomainError.Invalid(it.message ?: "invalid")
                        is NoSuchElementException -> DomainError.NotFound(it.message ?: "not found")
                        else -> DomainError.Unexpected(it.message ?: "unknown")
                    }
                }
        """.trimIndent() else """
            package $p.domain

            /**
             * Runs [block], capturing failures.
             * IllegalArgumentException = invalid input, NoSuchElementException = unknown id.
             */
            internal inline fun <T> attempt(block: () -> T): Result<T> = runCatching(block)
        """.trimIndent()
        f["$b/domain/TodoUseCases.kt"] = """
            package $p.domain

            __IMPORTS__

            class GetTodosUseCase(private val repository: TodoRepository) {
                suspend operator fun invoke(): ${r("List<Todo>")} = attempt { repository.all() }
            }

            class AddTodoUseCase(private val repository: TodoRepository) {
                suspend operator fun invoke(title: String): ${r("Todo")} = attempt {
                    require(title.isNotBlank()) { "Title must not be blank" }
                    repository.add(title.trim())
                }
            }

            class SetTodoDoneUseCase(private val repository: TodoRepository) {
                suspend operator fun invoke(id: String, done: Boolean): ${r("Todo")} = attempt {
                    repository.setDone(id, done) ?: throw NoSuchElementException("No todo with id ${'$'}id")
                }
            }

            class DeleteTodoUseCase(private val repository: TodoRepository) {
                suspend operator fun invoke(id: String): ${r("Unit")} = attempt {
                    if (!repository.delete(id)) throw NoSuchElementException("No todo with id ${'$'}id")
                }
            }
        """.trimIndent().replace("__IMPORTS__\n", imports).replace("\n\n\n", "\n\n")

        // ----- data: wire format shared with the server, plus the HTTP client adapter -----
        f["$b/data/TodoDtos.kt"] = """
            package $p.data

            import kotlinx.serialization.Serializable
            import $p.domain.Todo

            /** Wire format of /api/$s, used by both the HTTP client and the server. */
            @Serializable
            data class TodoDto(val id: String, val title: String, val done: Boolean)

            @Serializable
            data class NewTodoDto(val title: String)

            @Serializable
            data class TodoPatchDto(val done: Boolean)

            fun Todo.toDto() = TodoDto(id, title, done)
            fun TodoDto.toDomain() = Todo(id, title, done)
        """.trimIndent()
        f["$b/data/HttpTodoRepository.kt"] = """
            package $p.data

            import io.ktor.client.HttpClient
            import io.ktor.client.call.body
            import io.ktor.client.plugins.ClientRequestException
            import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
            import io.ktor.client.request.delete
            import io.ktor.client.request.get
            import io.ktor.client.request.patch
            import io.ktor.client.request.post
            import io.ktor.client.request.setBody
            import io.ktor.http.ContentType
            import io.ktor.http.HttpStatusCode
            import io.ktor.http.contentType
            import io.ktor.serialization.kotlinx.json.json
            import $p.domain.Todo
            import $p.domain.TodoRepository

            /** The engine is picked per platform from the Ktor engine on the classpath. */
            internal fun todoHttpClient() = HttpClient {
                expectSuccess = true
                install(ContentNegotiation) { json() }
            }

            /** Talks to the server's /api/$s endpoints. Ktor stays an implementation detail of core. */
            class HttpTodoRepository internal constructor(private val client: HttpClient, baseUrl: String) : TodoRepository {
                constructor(baseUrl: String) : this(todoHttpClient(), baseUrl)

                private val url = "${'$'}baseUrl/api/$s"

                override suspend fun all(): List<Todo> =
                    client.get(url).body<List<TodoDto>>().map { it.toDomain() }

                override suspend fun add(title: String): Todo =
                    client.post(url) {
                        contentType(ContentType.Application.Json)
                        setBody(NewTodoDto(title))
                    }.body<TodoDto>().toDomain()

                override suspend fun setDone(id: String, done: Boolean): Todo? = nullIfNotFound {
                    client.patch("${'$'}url/${'$'}id") {
                        contentType(ContentType.Application.Json)
                        setBody(TodoPatchDto(done))
                    }.body<TodoDto>().toDomain()
                }

                override suspend fun delete(id: String): Boolean =
                    nullIfNotFound { client.delete("${'$'}url/${'$'}id") } != null

                private inline fun <T> nullIfNotFound(block: () -> T): T? = try {
                    block()
                } catch (e: ClientRequestException) {
                    if (e.response.status == HttpStatusCode.NotFound) null else throw e
                }
            }
        """.trimIndent()

        // ----- tests: use cases against an in-memory fake -----
        val tb = "core/src/commonTest/kotlin/${c.dir}/features/$s"
        f["$tb/FakeTodoRepository.kt"] = """
            package $p

            import $p.domain.Todo
            import $p.domain.TodoRepository

            class FakeTodoRepository : TodoRepository {
                private val todos = linkedMapOf<String, Todo>()
                private var nextId = 1

                override suspend fun all() = todos.values.toList()
                override suspend fun add(title: String) = Todo("${'$'}{nextId++}", title, false).also { todos[it.id] = it }
                override suspend fun setDone(id: String, done: Boolean) = todos[id]?.copy(done = done)?.also { todos[id] = it }
                override suspend fun delete(id: String) = todos.remove(id) != null
            }
        """.trimIndent()
        f["$tb/TodoUseCasesTest.kt"] = if (c.lib("kotest")) """
            package $p

            import $p.domain.AddTodoUseCase
            import $p.domain.GetTodosUseCase
            import $p.domain.SetTodoDoneUseCase
            import io.kotest.core.spec.style.StringSpec
            import io.kotest.matchers.nulls.shouldBeNull
            import io.kotest.matchers.shouldBe

            class TodoUseCasesTest : StringSpec({
                "added todos are listed" {
                    val repo = FakeTodoRepository()
                    AddTodoUseCase(repo)("  Buy milk ")
                    GetTodosUseCase(repo)().getOrNull()!!.map { it.title } shouldBe listOf("Buy milk")
                }
                "blank titles are rejected" {
                    AddTodoUseCase(FakeTodoRepository())(" ").getOrNull().shouldBeNull()
                }
                "marking an unknown todo fails" {
                    SetTodoDoneUseCase(FakeTodoRepository())("missing", true).getOrNull().shouldBeNull()
                }
            })
        """.trimIndent() else """
            package $p

            import $p.domain.AddTodoUseCase
            import $p.domain.GetTodosUseCase
            import $p.domain.SetTodoDoneUseCase
            import kotlinx.coroutines.test.runTest
            import kotlin.test.Test
            import kotlin.test.assertEquals
            import kotlin.test.assertNull

            class TodoUseCasesTest {
                @Test fun addedTodosAreListed() = runTest {
                    val repo = FakeTodoRepository()
                    AddTodoUseCase(repo)("  Buy milk ")
                    assertEquals(listOf("Buy milk"), GetTodosUseCase(repo)().getOrNull()!!.map { it.title })
                }

                @Test fun blankTitlesAreRejected() = runTest {
                    assertNull(AddTodoUseCase(FakeTodoRepository())(" ").getOrNull())
                }

                @Test fun markingAnUnknownTodoFails() = runTest {
                    assertNull(SetTodoDoneUseCase(FakeTodoRepository())("missing", true).getOrNull())
                }
            }
        """.trimIndent()

        if (!c.hasUi) return

        // ----- presentation -----
        val report = if (arrow) "fold({ error = it.message; null }, { error = null; it })"
        else "fold(onSuccess = { error = null; it }, onFailure = { error = it.message ?: \"error\"; null })"
        f["$ub/presentation/TodoScreen.kt"] = """
            package $p.presentation

            import androidx.compose.foundation.layout.Arrangement
            import androidx.compose.foundation.layout.Column
            import androidx.compose.foundation.layout.Row
            import androidx.compose.foundation.layout.padding
            import androidx.compose.material3.Button
            import androidx.compose.material3.Checkbox
            import androidx.compose.material3.MaterialTheme
            import androidx.compose.material3.OutlinedTextField
            import androidx.compose.material3.Text
            import androidx.compose.material3.TextButton
            import androidx.compose.runtime.Composable
            import androidx.compose.runtime.LaunchedEffect
            import androidx.compose.runtime.getValue
            import androidx.compose.runtime.mutableStateOf
            import androidx.compose.runtime.remember
            import androidx.compose.runtime.rememberCoroutineScope
            import androidx.compose.runtime.setValue
            import androidx.compose.ui.Alignment
            import androidx.compose.ui.Modifier
            import androidx.compose.ui.text.style.TextDecoration
            import androidx.compose.ui.unit.dp
            import kotlinx.coroutines.launch
            __IMPORTS__
            import $p.domain.AddTodoUseCase
            import $p.domain.DeleteTodoUseCase
            import $p.domain.GetTodosUseCase
            import $p.domain.SetTodoDoneUseCase
            import $p.domain.Todo

            @Composable
            fun TodoScreen(
                getTodos: GetTodosUseCase,
                addTodo: AddTodoUseCase,
                setDone: SetTodoDoneUseCase,
                deleteTodo: DeleteTodoUseCase,
            ) {
                var todos by remember { mutableStateOf<List<Todo>>(emptyList()) }
                var loading by remember { mutableStateOf(true) }
                var error by remember { mutableStateOf<String?>(null) }
                var newTitle by remember { mutableStateOf("") }
                val scope = rememberCoroutineScope()

                /** Shows a failure and returns null, or returns the value. */
                fun <T> ${r("T")}.orReport(): T? = $report

                LaunchedEffect(Unit) {
                    getTodos().orReport()?.let { todos = it }
                    loading = false
                }

                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = newTitle,
                            onValueChange = { newTitle = it },
                            label = { Text("New todo") },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                        Button(enabled = newTitle.isNotBlank(), onClick = {
                            val title = newTitle
                            scope.launch { addTodo(title).orReport()?.let { todos = todos + it; newTitle = "" } }
                        }) { Text("Add") }
                    }
                    error?.let { Text("Server error: ${'$'}it", color = MaterialTheme.colorScheme.error) }
                    when {
                        loading -> Text("Loading…")
                        todos.isEmpty() -> Text("Nothing to do.")
                    }
                    todos.forEach { todo ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = todo.done, onCheckedChange = { done ->
                                scope.launch {
                                    setDone(todo.id, done).orReport()?.let { updated ->
                                        todos = todos.map { if (it.id == updated.id) updated else it }
                                    }
                                }
                            })
                            Text(
                                todo.title,
                                modifier = Modifier.weight(1f),
                                textDecoration = if (todo.done) TextDecoration.LineThrough else null,
                            )
                            TextButton(onClick = {
                                scope.launch { deleteTodo(todo.id).orReport()?.let { todos = todos.filterNot { it.id == todo.id } } }
                            }) { Text("Delete") }
                        }
                    }
                }
            }
        """.trimIndent().replace("__IMPORTS__\n", imports)
        f["$ub/TodoFeature.kt"] = """
            package $p

            import androidx.compose.runtime.Composable
            import ${c.pkg}.core.defaultServerUrl
            import ${c.pkg}.ui.Feature
            import $p.data.HttpTodoRepository
            import $p.domain.AddTodoUseCase
            import $p.domain.DeleteTodoUseCase
            import $p.domain.GetTodosUseCase
            import $p.domain.SetTodoDoneUseCase
            import $p.presentation.TodoScreen

            /** Composition root: clients store todos on the server over HTTP. */
            object TodoFeature : Feature {
                override val title = "Todo"
                private val repository = HttpTodoRepository(defaultServerUrl)
                private val getTodos = GetTodosUseCase(repository)
                private val addTodo = AddTodoUseCase(repository)
                private val setDone = SetTodoDoneUseCase(repository)
                private val deleteTodo = DeleteTodoUseCase(repository)
                @Composable override fun Content() = TodoScreen(getTodos, addTodo, setDone, deleteTodo)
            }
        """.trimIndent()
    }

    /** Server side of the Todo sample: MongoDB adapter + HTTP routes. */
    private fun todoServer(c: Ctx, f: MutableMap<String, String>) {
        val s = TODO_SLICE
        val p = "${c.pkg}.features.$s"
        val tp = "${c.appPkg("server")}.$s"
        val td = "server/src/main/kotlin/${c.appDir("server")}/$s"
        val arrow = c.lib("arrow")
        f["$td/MongoTodoRepository.kt"] = """
            package $tp

            import com.mongodb.client.model.Filters.eq
            import com.mongodb.client.model.FindOneAndUpdateOptions
            import com.mongodb.client.model.ReturnDocument
            import com.mongodb.client.model.Updates
            import com.mongodb.kotlin.client.coroutine.MongoDatabase
            import kotlinx.coroutines.flow.map
            import kotlinx.coroutines.flow.toList
            import org.bson.Document
            import org.bson.types.ObjectId
            import $p.domain.Todo
            import $p.domain.TodoRepository

            /** Stores todos in the `todos` collection. Server-only data adapter for the `$s` slice. */
            class MongoTodoRepository(db: MongoDatabase) : TodoRepository {
                private val todos = db.getCollection<Document>("todos")

                override suspend fun all(): List<Todo> = todos.find().map { it.toTodo() }.toList()

                override suspend fun add(title: String): Todo {
                    val doc = Document("_id", ObjectId()).append("title", title).append("done", false)
                    todos.insertOne(doc)
                    return doc.toTodo()
                }

                override suspend fun setDone(id: String, done: Boolean): Todo? {
                    val oid = id.toObjectIdOrNull() ?: return null
                    return todos.findOneAndUpdate(
                        eq("_id", oid),
                        Updates.set("done", done),
                        FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER),
                    )?.toTodo()
                }

                override suspend fun delete(id: String): Boolean {
                    val oid = id.toObjectIdOrNull() ?: return false
                    return todos.deleteOne(eq("_id", oid)).deletedCount > 0
                }
            }

            private fun String.toObjectIdOrNull() = if (ObjectId.isValid(this)) ObjectId(this) else null

            private fun Document.toTodo() = Todo(getObjectId("_id").toHexString(), getString("title"), getBoolean("done", false))
        """.trimIndent()

        fun fold(ok: String) = if (arrow) "fold({ call.respondError(it) }, { $ok })"
        else "fold(onSuccess = { $ok }, onFailure = { call.respondError(it) })"
        val respondError = if (arrow) """
            private suspend fun ApplicationCall.respondError(error: DomainError) = when (error) {
                is DomainError.Invalid -> respond(HttpStatusCode.BadRequest, error.message)
                is DomainError.NotFound -> respond(HttpStatusCode.NotFound, error.message)
                is DomainError.Unexpected -> respond(HttpStatusCode.InternalServerError, error.message)
            }
        """.trimIndent() else """
            private suspend fun ApplicationCall.respondError(error: Throwable) = when (error) {
                is IllegalArgumentException -> respond(HttpStatusCode.BadRequest, error.message ?: "Bad request")
                is NoSuchElementException -> respond(HttpStatusCode.NotFound, error.message ?: "Not found")
                else -> respond(HttpStatusCode.InternalServerError, error.message ?: "error")
            }
        """.trimIndent()
        f["$td/TodoRoutes.kt"] = """
            package $tp

            import io.ktor.http.HttpStatusCode
            import io.ktor.server.application.ApplicationCall
            import io.ktor.server.request.receive
            import io.ktor.server.response.respond
            import io.ktor.server.routing.Route
            import io.ktor.server.routing.delete
            import io.ktor.server.routing.get
            import io.ktor.server.routing.patch
            import io.ktor.server.routing.post
            import io.ktor.server.routing.route
            ${if (arrow) "import ${c.pkg}.core.DomainError" else ""}
            import $p.data.NewTodoDto
            import $p.data.TodoPatchDto
            import $p.data.toDto
            import $p.domain.AddTodoUseCase
            import $p.domain.DeleteTodoUseCase
            import $p.domain.GetTodosUseCase
            import $p.domain.SetTodoDoneUseCase
            import $p.domain.TodoRepository

            /**
             * HTTP adapter for the `$s` slice.
             *   GET    /api/$s           list
             *   POST   /api/$s           create  {"title": "..."}
             *   PATCH  /api/$s/{id}      update  {"done": true}
             *   DELETE /api/$s/{id}      delete
             */
            fun Route.todoRoutes(repository: TodoRepository) {
                val getTodos = GetTodosUseCase(repository)
                val addTodo = AddTodoUseCase(repository)
                val setDone = SetTodoDoneUseCase(repository)
                val deleteTodo = DeleteTodoUseCase(repository)
                route("/api/$s") {
                    get { getTodos().${fold("todos -> call.respond(todos.map { it.toDto() })")} }
                    post {
                        val body = call.receive<NewTodoDto>()
                        addTodo(body.title).${fold("call.respond(HttpStatusCode.Created, it.toDto())")}
                    }
                    patch("/{id}") {
                        val body = call.receive<TodoPatchDto>()
                        setDone(call.parameters["id"]!!, body.done).${fold("call.respond(it.toDto())")}
                    }
                    delete("/{id}") { deleteTodo(call.parameters["id"]!!).${fold("call.respond(HttpStatusCode.NoContent)")} }
                }
            }

            __RESPOND_ERROR__
        """.trimIndent().replace("\n\n\n", "\n\n").replace("route\n\n", "route\n")
            .replace("__RESPOND_ERROR__", respondError) + "\n"
    }

    private fun readme(c: Ctx) = buildString {
        appendLine("# ${c.spec.name}")
        appendLine("Generated Kotlin Multiplatform project${if (c.hasUi) " (Compose Multiplatform UI)" else ""}.\n")
        appendLine("Targets: ${c.spec.targets.joinToString { it.label }}")
        appendLine("Libraries: ${c.libIds.sorted().joinToString().ifEmpty { "none" }}\n")
        appendLine("## Setup")
        appendLine("Includes the Gradle $GRADLE wrapper: run `./gradlew` (or `gradlew.bat` on Windows); no Gradle install needed.\n")
        appendLine("## Structure")
        appendLine("Follows the [recommended KMP project structure](https://kotlinlang.org/docs/multiplatform/multiplatform-project-recommended-structure.html):")
        appendLine("entry points are separate modules, shared code is split into logic and UI.\n")
        appendLine("```")
        appendLine("core/                       shared by clients and server, no UI")
        appendLine("  src/commonMain/kotlin/${c.dir}/")
        appendLine("    core/                   shared kernel (logging, errors)")
        appendLine("    features/<slice>/")
        appendLine("      domain/               entities, repository interfaces, use cases (no framework imports)")
        appendLine("      data/                 repository implementations")
        if (c.hasUi) {
            appendLine("app/")
            appendLine("  sharedUI/                 Compose Multiplatform, depends on core")
            appendLine("    src/commonMain/kotlin/${c.dir}/")
            appendLine("      ui/                   app shell: App, Feature, AppFeatures (registry of slices)")
            appendLine("      features/<slice>/")
            appendLine("        presentation/       UI state + Compose screens")
            appendLine("        <Slice>Feature.kt   slice composition root")
            if (c.has(Target.ANDROID)) appendLine("  androidApp/               Android entry point")
            if (c.has(Target.DESKTOP)) appendLine("  desktopApp/               desktop (JVM) entry point")
            if (c.has(Target.WEB)) appendLine("  webApp/                   web entry point (JS + Wasm, shared webMain)")
            if (c.has(Target.IOS)) appendLine("  iosApp/                   Xcode host app, imports the `$IOS_FRAMEWORK` framework")
        }
        if (c.has(Target.SERVER)) appendLine("server/                     Ktor server, depends on core only")
        appendLine("```")
        appendLine("Dependency rule: presentation -> domain <- data. Slices must not import each other; share through `core`.\n")
        appendLine("Platform entry points use the package `${c.pkg}.app.<platform>`.")
        if (c.has(Target.SERVER)) {
            appendLine("The server exposes each slice over HTTP (`GET /api/<slice>`).")
            appendLine("Its port is set in `server/src/main/resources/application.conf` (default $SERVER_PORT, or the `PORT` env var).")
        }
        if (c.todo) {
            appendLine("\n## Todo sample")
            appendLine("The `$TODO_SLICE` slice is a full round trip: Compose screen -> use cases -> `HttpTodoRepository` (Ktor client, in core)")
            appendLine("-> `/api/$TODO_SLICE` on the server -> use cases -> `MongoTodoRepository` (server only) -> MongoDB.")
            appendLine("Clients and server share the domain, use cases and wire DTOs from `core`.\n")
            appendLine("1. Start MongoDB: `docker compose up -d` (or set `MONGODB_URI` to your own instance).")
            appendLine("2. Start the server: `./gradlew :server:run`.")
            appendLine("3. Run any client and open the Todo tab. Clients use `http://localhost:$SERVER_PORT`; the Android emulator uses `http://10.0.2.2:$SERVER_PORT`.")
            appendLine("   Change it in `core/src/*Main/kotlin/${c.dir}/core/ServerUrl.*.kt`.")
        }
        appendLine("\n## Run")
        if (c.has(Target.DESKTOP)) appendLine("- Desktop: `./gradlew :app:desktopApp:run`")
        if (c.has(Target.ANDROID)) appendLine("- Android: `./gradlew :app:androidApp:installDebug`")
        if (c.has(Target.WEB)) {
            appendLine("- Web (Wasm): `./gradlew :app:webApp:wasmJsBrowserDevelopmentRun`")
            appendLine("- Web (JS): `./gradlew :app:webApp:jsBrowserDevelopmentRun`")
        }
        if (c.has(Target.IOS)) appendLine("- iOS: see `app/iosApp/README.md`")
        if (c.has(Target.SERVER)) appendLine("- Server: `./gradlew :server:run`, then open http://localhost:$SERVER_PORT/api/${c.allSlices.first()}")
        appendLine("- Tests: `./gradlew allTests`" + if (c.has(Target.SERVER)) " and `./gradlew :server:test`" else "")
    }
}
