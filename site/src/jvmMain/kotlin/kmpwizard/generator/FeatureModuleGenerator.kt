package kmpwizard.generator

import kmpwizard.Target

// ---------- feature/: one package per vertical slice, <pkg>.features.<slice>, across three modules ----------
//
//   feature/shared   KMP, every target    domain/ (entities, repository interfaces, use cases), data/ (repos, wire DTOs)
//   feature/client   KMP + Compose        presentation/, <Slice>Feature.kt, HTTP adapters   (when a UI target is picked)
//   feature/server   kotlin("jvm")        server/ (Ktor routes, server-only adapters)        (when Server is picked)
//
// Separate modules keep server code out of the client apps and Compose out of the server
// (Kotlin doesn't allow two JVM targets in one module). app/sharedUI depends on :feature:client,
// server on :feature:server; both only wire slices together.

/** Package of the feature modules' shared types, e.g. the [Feature] interface the app shell renders. */
fun featuresPkg(c: ProjectContext) = "${c.pkg}.features"

/**
 * Source root, down to the `features` package, for one part of a slice:
 * `commonMain` / `commonTest` (feature/shared), `clientMain` (feature/client), `serverMain` (feature/server).
 */
fun featureSrc(c: ProjectContext, part: String) = when (part) {
    "commonMain", "commonTest" -> "feature/shared/src/$part"
    "clientMain" -> "feature/client/src/commonMain"
    "serverMain" -> "feature/server/src/main"
    else -> error("Unknown feature part: $part")
} + "/kotlin/${c.dir}/features"

/** Gradle project paths of the feature modules in this project. */
fun featureModulePaths(c: ProjectContext) = buildList {
    add(":feature:shared")
    if (c.hasUi) add(":feature:client")
    if (c.has(Target.SERVER)) add(":feature:server")
}

fun featureModules(c: ProjectContext, f: MutableMap<String, String>) {
    featureShared(c, f)
    if (c.hasUi) featureClient(c, f)
    if (c.has(Target.SERVER)) featureServer(c, f)
}

/** Domain + data of every slice, for clients and server alike. */
private fun featureShared(c: ProjectContext, f: MutableMap<String, String>) {
    val deps = linkedMapOf<String, MutableList<String>>()
    fun add(set: String, d: String) = deps.getOrPut(set) { mutableListOf() }.add(d)
    add("commonMain", "api(projects.core)")
    add("commonTest", "implementation(kotlin(\"test\"))")
    add("commonTest", "implementation(libs.coroutines.test)")
    c.libraryDependencies().forEach { (set, d) -> add(set, d) }
    // Wire DTOs are @Serializable and shared by the HTTP client and the server.
    if (c.todo) add("commonMain", "implementation(libs.ktor.serialization.kotlinx.json)")
    f["feature/shared/build.gradle.kts"] = kmpBuildScript(
        c, namespace = "${featuresPkg(c)}.shared", compose = false, jvm = c.hasJvm, iosFramework = null,
        plugins = c.corePlugins, deps = deps, junit = c.lib("kotest"),
    )
}

/** Compose screens, slice composition roots and client-side adapters. */
private fun featureClient(c: ProjectContext, f: MutableMap<String, String>) {
    val deps = linkedMapOf(
        "commonMain" to buildList {
            add("api(projects.feature.shared)")
            add("api(libs.compose.runtime)")
            add("api(libs.compose.ui)")
            add("implementation(libs.compose.foundation)")
            add("implementation(libs.compose.material3)")
            if (c.todo) {
                add("implementation(libs.ktor.client.core)")
                add("implementation(libs.ktor.client.content.negotiation)")
                add("implementation(libs.ktor.serialization.kotlinx.json)")
            }
        },
    )
    f["feature/client/build.gradle.kts"] = kmpBuildScript(
        c, namespace = "${featuresPkg(c)}.client", compose = true, jvm = c.has(Target.DESKTOP), iosFramework = null,
        plugins = if (c.todo) listOf("kotlinSerialization") else emptyList(), deps = deps,
    )
    f["${featureSrc(c, "clientMain")}/Feature.kt"] = """
        package ${featuresPkg(c)}

        import androidx.compose.runtime.Composable

        /** A vertical slice's entry point into the app shell. */
        interface Feature {
            val title: String
            @Composable fun Content()
        }
    """.trimIndent()
}

/** Ktor routes and server-only adapters of every slice. */
private fun featureServer(c: ProjectContext, f: MutableMap<String, String>) {
    f["feature/server/build.gradle.kts"] = """
        plugins {
            alias(libs.plugins.kotlinJvm)
            alias(libs.plugins.kotlinSerialization)
        }

        // The root :server module is also named "server"; distinct names keep their jars and Kotlin modules apart.
        base { archivesName.set("feature-server") }
        kotlin { compilerOptions { moduleName.set("feature-server") } }

        dependencies {
            api(projects.feature.shared)
            implementation(libs.ktor.server.core)
            implementation(libs.ktor.serialization.kotlinx.json)${if (c.todo) """
            implementation(libs.mongodb.driver.kotlin.coroutine)""" else ""}
        }
    """.trimIndent() + "\n"
}

/** One user-defined vertical slice: every layer in `<pkg>.features.<slice>`. */
fun slice(c: ProjectContext, s: String, f: MutableMap<String, String>) {
    val x = s.replaceFirstChar(Char::uppercase)
    val p = "${featuresPkg(c)}.$s"
    val b = "${featureSrc(c, "commonMain")}/$s"
    val cb = "${featureSrc(c, "clientMain")}/$s"
    val arrow = c.lib("arrow")

    // ----- domain -----
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

    // ----- data -----
    f["$b/data/InMemory${x}Repository.kt"] = """
        package $p.data

        import $p.domain.${x}Item
        import $p.domain.${x}Repository

        class InMemory${x}Repository : ${x}Repository {
            override suspend fun all() = listOf(${x}Item("1", "First $s item"), ${x}Item("2", "Second $s item"))
        }
    """.trimIndent()

    // ----- presentation -----
    if (c.hasUi) {
        val fold = if (arrow) "getItems().fold({ ${x}UiState.Error(it.toString()) }, { ${x}UiState.Loaded(it) })"
        else "getItems().fold(onSuccess = { ${x}UiState.Loaded(it) }, onFailure = { ${x}UiState.Error(it.message ?: \"error\") })"
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
        // Slice composition root on the client: the only place that knows the concrete data layer.
        f["$cb/${x}Feature.kt"] = """
            package $p

            import androidx.compose.runtime.Composable
            import ${featuresPkg(c)}.Feature
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
        val sb = "${featureSrc(c, "serverMain")}/$s/server"
        f["$sb/${x}ItemDto.kt"] = """
            package $p.server

            import kotlinx.serialization.Serializable
            import $p.domain.${x}Item

            /** Wire format of /api/$s. Keeps serialization out of the domain layer. */
            @Serializable
            data class ${x}ItemDto(val id: String, val title: String)

            fun ${x}Item.toDto() = ${x}ItemDto(id, title)
        """.trimIndent()
        val ok = "call.respond(items.map { it.toDto() })"
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

    // ----- tests -----
    f["${featureSrc(c, "commonTest")}/$s/Get${x}ItemsUseCaseTest.kt"] = if (c.lib("kotest")) """
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
