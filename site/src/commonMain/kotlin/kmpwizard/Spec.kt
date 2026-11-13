package kmpwizard

import kotlinx.serialization.Serializable

@Serializable
enum class Target(val label: String) {
    ANDROID("Android"), IOS("iOS"), DESKTOP("Desktop (JVM)"), WEB("Web (JS + Wasm)"), SERVER("Server (Ktor)")
}

@Serializable
data class ProjectSpecification(
    val name: String
    , val packageName: String
    , val targets: Set<Target>
    , val slices: List<String>
    , val libraries: Set<String>
    /** Adds a sample `todo` slice: REST API on the server backed by MongoDB, clients call it over HTTP. */
    , val sampleTodo: Boolean = false
    /** Ship the project as a local git repository (`.git` included in the ZIP, branch `main`). */
    , val gitInit: Boolean = false
    /** When [gitInit] is on: commit all files with this message. Blank = init only, nothing committed. */
    , val commitMessage: String = ""
)

/** Slice name used by the Todo sample. */
const val TODO_SLICE = "todo"

/**
 * Library ids the Todo sample relies on (its HTTP client, and Koin for its ViewModel and wiring);
 * always included when the sample is on.
 */
val TODO_LIBRARIES = setOf("ktor", "serialization", "koin")

data class LibraryOption(
    val id: String,
    val name: String,
    val description: String,
    val category: String,
    /** Empty = works everywhere. Otherwise at least one of these targets must be selected. */
    val requiresAnyOf: Set<Target> = emptySet(),
) {
    fun isAvailable(targets: Set<Target>) = requiresAnyOf.isEmpty() || targets.any { it in requiresAnyOf }
}

object Catalog {
    val libraries = listOf(
        LibraryOption("arrow", "Arrow-kt", "Typed errors: use cases return Either<DomainError, T>", "Functional"),
        LibraryOption("datetime", "kotlinx-datetime", "Multiplatform date/time", "Utilities"),
        LibraryOption("serialization", "kotlinx.serialization", "JSON serialization", "Networking"),
        LibraryOption("ktor", "Ktor client", "HTTP client with the right engine per platform", "Networking"),
        LibraryOption("koin", "Koin", "Dependency injection (core + Compose/ViewModel)", "Dependency injection"),

        LibraryOption("kermit", "Kermit", "Multiplatform logging (wired into AppLogger)", "Observability"),
        LibraryOption("kotest", "Kotest", "Spec-style tests + expressive assertions", "Testing"),
        LibraryOption("turbine", "Turbine", "Testing for Kotlin Flows", "Testing"),
        LibraryOption(
            "flyway", "Flyway", "SQL migrations for the server: PostgreSQL (docker-compose), H2 in tests",
            "Database", setOf(Target.SERVER),
        ),
        LibraryOption(
            "occurrent", "Occurrent", "Event sourcing. JVM only, added to Android/Desktop/Server source sets",
            "Event sourcing", setOf(Target.ANDROID, Target.DESKTOP, Target.SERVER),
        ),
    )
    val byId = libraries.associateBy { it.id }

    /** Kotlin keywords, plus the app's own packages next to the slices' `<pkg>.<slice>` (core, ui, app). */
    private val reserved = setOf(
        "core", "ui", "app", "object", "class", "fun", "val", "var", "in", "is", "as", "if", "else", "when",
        "for", "while", "do", "try", "package", "interface", "return", "typealias",
    )

    fun validate(spec: ProjectSpecification): List<String> = buildList {
        if (spec.name.isBlank() || spec.slug().isEmpty()) add("Project name is required.")
        if (!Regex("^[a-z][a-z0-9]*(\\.[a-z][a-z0-9]*)+$").matches(spec.packageName))
            add("Package must look like com.example.app (lowercase letters/digits).")
        if (spec.targets.isEmpty()) add("Select at least one platform.")
        if (spec.slices.isEmpty() && !spec.sampleTodo) add("Add at least one vertical slice.")
        if (spec.sampleTodo && Target.SERVER !in spec.targets) add("The Todo sample needs the Server platform.")
        if (spec.sampleTodo && TODO_SLICE in spec.slices) add("Slice name '$TODO_SLICE' is used by the Todo sample.")
        spec.slices.forEach {
            if (!Regex("^[a-z][a-z0-9]{1,20}$").matches(it) || it in reserved)
                add("Invalid slice name '$it' (lowercase letters/digits, 2-21 chars, not a Kotlin keyword).")
        }
        if (spec.slices.toSet().size != spec.slices.size) add("Slice names must be unique.")
        spec.libraries.forEach { id ->
            val lib = byId[id]
            when {
                lib == null -> add("Unknown library '$id'.")
                !lib.isAvailable(spec.targets) -> add("${lib.name} needs one of: ${lib.requiresAnyOf.joinToString { it.label }}.")
            }
        }
    }
}

fun ProjectSpecification.slug(): String = name.trim().lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')
fun ProjectSpecification.validate(): List<String> = Catalog.validate(this)
