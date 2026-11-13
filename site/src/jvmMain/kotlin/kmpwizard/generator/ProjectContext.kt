package kmpwizard.generator

import kmpwizard.ProjectSpecification
import kmpwizard.TODO_LIBRARIES
import kmpwizard.TODO_SLICE
import kmpwizard.Target
import kmpwizard.slug


/** [api] = true when the library's types appear in core's public API (e.g. use case return types). */
class Art(val alias: String, val module: String, val sets: List<String>, val api: Boolean = false)

class Plug(val alias: String, val id: String, val versionRef: String)

// Desktop and Server share core's single `jvm()` target.
val jvmTargets = setOf(Target.DESKTOP, Target.SERVER)

// Targets with a Compose UI; these get app/sharedUI.
val uiTargets = setOf(Target.ANDROID, Target.IOS, Target.DESKTOP, Target.WEB)

/** Source sets that only exist when one of these targets is selected. */
val setTargets = mapOf(
    "androidMain" to setOf(Target.ANDROID),
    "iosMain" to setOf(Target.IOS),
    "jvmMain" to jvmTargets,
    "jvmTest" to jvmTargets,
    "webMain" to setOf(Target.WEB),
)


/**
 * Describes a library entry used to group related dependency artifacts, plugin definitions,
 * and version references under a shared library version.
 */
class Library(
    val versionKey: String
    , val version: String
    , val arts: List<Art>
    , val plugins: List<Plug> = emptyList()
    /** Versions the plugins need besides [versionKey]. */
    , val extraVersions: Map<String, String> = emptyMap()
)

val wiring = mapOf(
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
    // Koin: core gets koin-core (+ koin-android); the Compose/ViewModel artifacts go in app/sharedUI and the slices'
    // client modules (see sharedUiModule, sliceClient). koin-*-viewmodel bring androidx-lifecycle's ViewModel along.
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


class ProjectContext(val spec: ProjectSpecification) {
    val pkg = spec.packageName
    val dir = pkg.replace('.', '/')
    /** Platform entry points live in `<pkg>.app.<platform>`. */
    fun appPkg(platform: String) = "$pkg.app.$platform"
    fun appDir(platform: String) = "$dir/app/$platform"
    val slug = spec.slug()
    fun has(t: kmpwizard.Target) = t in spec.targets
    val hasJvm get() = spec.targets.any { it in jvmTargets }
    val hasUi get() = spec.targets.any { it in uiTargets }
    val todo get() = spec.sampleTodo
    /** The Todo sample needs Ktor and kotlinx.serialization (HTTP client) and Koin (wiring, ViewModel). */
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
    /** Selected libraries as (source set, dependency declaration) for core and every slice's shared module. */
    fun libraryDependencies(): List<Pair<String, String>> = activeLibs.flatMap { l ->
        l.arts.flatMap { a ->
            a.sets.filter(::setActive).map { it to "${if (a.api) "api" else "implementation"}(libs.${a.alias.replace('-', '.')})" }
        }
    }
}
