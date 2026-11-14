package kmpwizard.generator

import kmpwizard.TODO_SLICE
import kmpwizard.Target

// ---------- feature/<slice>/: each vertical slice is its own directory, split into modules by where code runs ------
//
//   feature/<slice>/shared   KMP, every target   domain/ (entities, repository interfaces, use cases),
//                                                data/ (repos, wire DTOs)
//   feature/<slice>/client   KMP + Compose       presentation/ (screens, ViewModels), <Slice>Feature.kt,
//                                                HTTP adapters (when a UI target is picked)
//   feature/<slice>/server   kotlin("jvm")       server/ (Ktor routes, server-only adapters) (when Server is picked)
//
// All three use the package <pkg>.<slice>. Separate modules keep server code out of the client
// apps and Compose out of the server (Kotlin doesn't allow two JVM targets in one module).
// app/sharedUI depends on every :feature:<slice>:client, server on every :feature:<slice>:server.
// The Feature interface the app shell renders lives in coreUI, shared by every slice's client module.

/** Package of slice [s]: `<pkg>.<slice>`, shared by its shared, client and server modules. */
fun slicePkg(
    c: ProjectContext
    , s: String
) = "${c.pkg}.$s"

/** Package of coreUI: UI kernel shared by the slices' client modules and the app shell, e.g. [Feature]. */
fun coreUiPkg(
    c: ProjectContext
) = "${c.pkg}.core.ui"

/** Directory of one slice module: `feature/<slice>/shared|client|server`. */
private fun sliceModuleDir(
    slice: String
    , module: String
) = "feature/$slice/$module"

/**
 * Source root, down to the slice's package, for one part of slice [slice]:
 * `commonMain` / `commonTest` (shared module), `clientMain` (client module), `serverMain` (server module).
 */
fun featureSrc(
    c: ProjectContext
    , slice: String
    , part: String
) = when (part) {
    "commonMain", "commonTest" -> "${sliceModuleDir(slice, "shared")}/src/$part"
    "clientMain" -> "${sliceModuleDir(slice, "client")}/src/commonMain"
    "serverMain" -> "${sliceModuleDir(slice, "server")}/src/main"
    else -> error("Unknown feature part: $part")
} + "/kotlin/${c.dir}/$slice"

/** Gradle project paths of every slice's modules, plus coreUI when there is a UI. */
fun featureModulePaths(
    c: ProjectContext
) = buildList {
    if (c.hasUi) add(":coreUI")
    c.allSlices.forEach { s ->
        add(":feature:$s:shared")
        if (c.hasUi) add(":feature:$s:client")
        if (c.has(Target.SERVER)) add(":feature:$s:server")
    }
}

/**
 * Koin modules the slices' client modules declare, as (slice, top-level val name). Only the Todo sample has one;
 * app/sharedUI's AppFeatures lists them for App to load.
 */
fun sliceKoinModules(
    c: ProjectContext
): List<Pair<String, String>> =
    if (c.lib("koin") && c.hasTodo && c.hasUi) listOf(TODO_SLICE to "${TODO_SLICE}Module")
    else emptyList()

/** Kotlin accessor of a slice module, e.g. `projects.feature.todo.client`. */
fun sliceProject(
    s: String
    , module: String
) = "projects.feature.$s.$module"

fun featureModules(
    c: ProjectContext
    , f: MutableMap<String, String>
) {
    if (c.hasUi) coreUiModule(c, f)
    c.allSlices.forEach { s ->
        sliceShared(c, s, f)
        if (c.hasUi) sliceClient(c, s, f)
        if (c.has(Target.SERVER)) sliceServer(c, s, f)
    }
}

/**
 * Every slice has modules named shared, client and server (and the root has :server). A group per slice
 * and a unique module/archive name keep their Maven coordinates, Kotlin modules and jars apart.
 */
private fun sliceIdentity(
    c: ProjectContext
    , s: String
    , module: String
) = ModuleIdentity(
    group = slicePkg(c, s)
    , name = "feature-$s-$module"
)

/** UI kernel for the slices' client modules and the app shell: the [Feature] contract. */
private fun coreUiModule(
    c: ProjectContext
    , f: MutableMap<String, String>
) {
    val deps = linkedMapOf(
        "commonMain" to listOf(
            "api(projects.core)"
            , "api(libs.compose.runtime)"
            , "api(libs.compose.ui)"
        )
    )
    f["coreUI/build.gradle.kts"] = kmpBuildScript(
        c
        , namespace = coreUiPkg(c)
        , hasCompose = true
        , hasJvm = c.has(Target.DESKTOP)
        , iosFramework = null
        , plugins = emptyList()
        , deps = deps
    )
    f["coreUI/src/commonMain/kotlin/${c.dir}/core/ui/Feature.kt"] = """
        package ${coreUiPkg(c)}

        import androidx.compose.runtime.Composable

        /** A vertical slice's entry point into the app shell. */
        interface Feature {
            val title: String
            @Composable fun Content()
        }
    """.trimIndent()
}

/** Domain + data of slice [s], for clients and server alike. */
private fun sliceShared(
    c: ProjectContext
    , s: String
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
    add("commonMain", "api(projects.core)")
    add("commonTest", "implementation(kotlin(\"test\"))")
    add("commonTest", "implementation(libs.coroutines.test)")
    c.libraryDependencies()
        .forEach { (set, d) ->
            add(set, d)
        }
    // The Todo sample's wire DTOs are @Serializable and shared by the HTTP client and the server.
    if (s == TODO_SLICE) add("commonMain", "implementation(libs.ktor.serialization.kotlinx.json)")
    f["${sliceModuleDir(s, "shared")}/build.gradle.kts"] = kmpBuildScript(
        c
        , namespace = "${slicePkg(c, s)}.shared"
        , hasCompose = false
        , hasJvm = c.hasJvm
        , iosFramework = null
        , plugins = c.corePlugins
        , deps = deps
        , usesJUnit = c.lib("kotest")
        , identity = sliceIdentity(c, s, "shared")
    )
}

/** Compose screens, composition root and client-side adapters of slice [s]. */
private fun sliceClient(
    c: ProjectContext
    , s: String
    , f: MutableMap<String, String>
) {
    val isTodo = s == TODO_SLICE
    val deps = linkedMapOf(
        "commonMain" to buildList {
            add("api(${sliceProject(s, "shared")})")
            add("api(projects.coreUI)")
            add("implementation(libs.compose.foundation)")
            add("implementation(libs.compose.material3)")
            if (c.lib("koin")) {
                // ViewModels (androidx-lifecycle, via Koin): koinViewModel() in screens, viewModelOf() in Koin modules.
                add("implementation(libs.koin.composeViewmodel)")
                add("implementation(libs.koin.coreViewmodel)")
            }
            if (isTodo) {
                add("implementation(libs.ktor.client.core)")
                add("implementation(libs.ktor.client.content.negotiation)")
                add("implementation(libs.ktor.serialization.kotlinx.json)")
            }
        }
    )
    f["${sliceModuleDir(s, "client")}/build.gradle.kts"] = kmpBuildScript(
        c
        , namespace = "${slicePkg(c, s)}.client"
        , hasCompose = true
        , hasJvm = c.has(Target.DESKTOP)
        , iosFramework = null
        , plugins = if (isTodo) listOf("kotlinSerialization") else emptyList()
        , deps = deps
        , identity = sliceIdentity(c, s, "client")
    )
}

/** Ktor routes and server-only adapters of slice [s]. */
private fun sliceServer(
    c: ProjectContext
    , s: String
    , f: MutableMap<String, String>
) {
    val id = sliceIdentity(c, s, "server")
    f["${sliceModuleDir(s, "server")}/build.gradle.kts"] = """
        plugins {
            alias(libs.plugins.kotlinJvm)
            alias(libs.plugins.kotlinSerialization)
        }

        ${id.comment}
        group = "${id.group}"
        base { archivesName.set("${id.name}") }
        kotlin { compilerOptions { moduleName.set("${id.name}") } }

        dependencies {
            api(${sliceProject(s, "shared")})
            implementation(libs.ktor.server.core)
            implementation(libs.ktor.serialization.kotlinx.json)${if (s == TODO_SLICE) """
            implementation(libs.mongodb.driver.kotlin.coroutine)""" else ""}
        }
    """.trimIndent() + "\n"
}

/** Sources of one user-defined vertical slice: every layer in `<pkg>.<slice>`, under `feature/<slice>/`. */
fun slice(
    c: ProjectContext
    , s: String
    , f: MutableMap<String, String>
) {
    val x = s.replaceFirstChar(Char::uppercase)
    val p = slicePkg(c, s)
    val b = featureSrc(c, s, "commonMain")
    val cb = featureSrc(c, s, "clientMain")
    val hasArrow = c.lib("arrow")

    // ----- domain -----
    f["$b/domain/${x}ItemId.kt"] = uuidIdType("$p.domain", "${x}ItemId", "${x}Item")
    f["$b/domain/${x}Item.kt"] = """
        package $p.domain

        data class ${x}Item(
            val id: ${x}ItemId
            , val title: String
        )
    """.trimIndent() + "\n"
    f["$b/domain/${x}Repository.kt"] =
        "package $p.domain\n\ninterface ${x}Repository {\n    suspend fun all(): List<${x}Item>\n}\n"
    f["$b/domain/Get${x}ItemsUseCase.kt"] = if (hasArrow) """
        package $p.domain

        import arrow.core.Either
        import ${c.pkg}.core.DomainError

        class Get${x}ItemsUseCase(
            private val repository: ${x}Repository
        ) {
            suspend operator fun invoke(): Either<DomainError, List<${x}Item>> = Either
                .catch {
                    repository.all()
                }
                .mapLeft {
                    DomainError.Unexpected(it.message ?: "unknown")
                }
        }
    """.trimIndent() else """
        package $p.domain

        class Get${x}ItemsUseCase(
            private val repository: ${x}Repository
        ) {
            suspend operator fun invoke(): Result<List<${x}Item>> = runCatching { repository.all() }
        }
    """.trimIndent()

    // ----- data -----
    f["$b/data/InMemory${x}Repository.kt"] = """
        package $p.data

        import $p.domain.${x}Item
        import $p.domain.${x}ItemId
        import $p.domain.${x}Repository

        class InMemory${x}Repository : ${x}Repository {
            private val items = listOf(
                ${x}Item(${x}ItemId.generate(), "First $s item")
                , ${x}Item(${x}ItemId.generate(), "Second $s item")
            )

            override suspend fun all() = items
        }
    """.trimIndent()

    // ----- presentation -----
    if (c.hasUi) {
        val fold = foldCall(
            hasArrow
            , onFailure = "${x}UiState.Error(${if (hasArrow) "it.toString()" else "it.message ?: \"error\""})"
            , onSuccess = "${x}UiState.Loaded(it)"
        )
        f["$cb/presentation/${x}Screen.kt"] = """
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
                data class Loaded(
                    val items: List<${x}Item>
                ) : ${x}UiState

                data class Error(
                    val message: String
                ) : ${x}UiState
            }

            @Composable
            fun ${x}Screen(
                getItems: Get${x}ItemsUseCase
            ) {
                var state by remember { mutableStateOf<${x}UiState>(${x}UiState.Loading) }
                LaunchedEffect(Unit) {
                    state = getItems().${fold.indentContinuation(20)}
                    AppLogger.d { "$s slice loaded" }
                }
                when (val s = state) {
                    ${x}UiState.Loading -> Text("Loading…")
                    is ${x}UiState.Loaded -> Column { s.items.forEach { Text(it.title) } }
                    is ${x}UiState.Error -> Text(s.message)
                }
            }
        """.trimIndent()
        // Slice composition root on the client: the only place that knows the concrete data layer.
        f["$cb/${x}Feature.kt"] = """
            package $p

            import androidx.compose.runtime.Composable
            import ${coreUiPkg(c)}.Feature
            import $p.data.InMemory${x}Repository
            import $p.domain.Get${x}ItemsUseCase
            import $p.presentation.${x}Screen

            object ${x}Feature : Feature {
                override val title = "$x"
                private val getItems = Get${x}ItemsUseCase(InMemory${x}Repository())
                @Composable override fun Content() = ${x}Screen(getItems)
            }
        """.trimIndent()
    }

    // ----- server -----
    if (c.has(Target.SERVER)) {
        val sb = "${featureSrc(c, s, "serverMain")}/server"
        f["$sb/${x}ItemDto.kt"] = """
            package $p.server

            import kotlinx.serialization.Serializable
            import $p.domain.${x}Item

            /** Wire format of /api/$s. Keeps serialization out of the domain layer. [id] is a UUID v7 string. */
            @Serializable
            data class ${x}ItemDto(
                val id: String
                , val title: String
            )

            fun ${x}Item.toDto() = ${x}ItemDto(id.toString(), title)
        """.trimIndent()
        val fold = foldCall(
            hasArrow
            , onFailure = "call.respond(HttpStatusCode.InternalServerError, " +
                "${if (hasArrow) "it.toString()" else "it.message ?: \"error\""})"
            , onSuccess = "items -> call.respond(items.map { it.toDto() })"
        )
        f["$sb/${x}Routes.kt"] = buildString {
            appendLine("package $p.server\n")
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
            appendLine("        getItems().${fold.indentContinuation(8)}")
            appendLine("    }")
            appendLine("}")
        }
    }

    // ----- tests -----
    f["${featureSrc(c, s, "commonTest")}/Get${x}ItemsUseCaseTest.kt"] = if (c.lib("kotest")) """
        package $p

        import $p.data.InMemory${x}Repository
        import $p.domain.Get${x}ItemsUseCase
        import io.kotest.core.spec.style.StringSpec
        import io.kotest.matchers.collections.shouldNotBeEmpty

        class Get${x}ItemsUseCaseTest : StringSpec({
            "returns items" {
                Get${x}ItemsUseCase(InMemory${x}Repository())()
                    .getOrNull()!!
                    .shouldNotBeEmpty()
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
                val items = Get${x}ItemsUseCase(InMemory${x}Repository())().getOrNull()!!
                assertTrue(items.isNotEmpty())
            }
        }
    """.trimIndent()
}
