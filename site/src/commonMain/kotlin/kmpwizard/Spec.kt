package kmpwizard

import kotlinx.serialization.Serializable

@Serializable
enum class Target(val label: String) {
    ANDROID("Android"), IOS("iOS"), DESKTOP("Desktop (JVM)"), WEB("Web (Wasm)"), SERVER("Server (Ktor)")
}

@Serializable
data class ProjectSpec(
    val name: String,
    val packageName: String,
    val targets: Set<Target>,
    val slices: List<String>,
    val libraries: Set<String>,
)

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
        LibraryOption("kotest", "Kotest", "Spec-style tests + expressive assertions", "Testing"),
        LibraryOption("turbine", "Turbine", "Testing for Kotlin Flows", "Testing"),
        LibraryOption("kermit", "Kermit", "Multiplatform logging (wired into AppLogger)", "Observability"),
        LibraryOption("arrow", "Arrow-kt", "Typed errors: use cases return Either<DomainError, T>", "Functional"),
        LibraryOption("ktor", "Ktor client", "HTTP client with the right engine per platform", "Networking"),
        LibraryOption("serialization", "kotlinx.serialization", "JSON serialization", "Networking"),
        LibraryOption("datetime", "kotlinx-datetime", "Multiplatform date/time", "Utilities"),
        LibraryOption(
            "occurrent", "Occurrent", "Event sourcing. JVM only, added to Android/Desktop/Server source sets",
            "Event sourcing", setOf(Target.ANDROID, Target.DESKTOP, Target.SERVER),
        ),
    )
    val byId = libraries.associateBy { it.id }

    private val reserved = setOf(
        "core", "object", "class", "fun", "val", "var", "in", "is", "as", "if", "else", "when",
        "for", "while", "do", "try", "package", "interface", "return", "typealias",
    )

    fun validate(spec: ProjectSpec): List<String> = buildList {
        if (spec.name.isBlank() || spec.slug().isEmpty()) add("Project name is required.")
        if (!Regex("^[a-z][a-z0-9]*(\\.[a-z][a-z0-9]*)+$").matches(spec.packageName))
            add("Package must look like com.example.app (lowercase letters/digits).")
        if (spec.targets.isEmpty()) add("Select at least one platform.")
        if (spec.slices.isEmpty()) add("Add at least one vertical slice.")
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

fun ProjectSpec.slug(): String = name.trim().lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')
fun ProjectSpec.validate(): List<String> = Catalog.validate(this)
