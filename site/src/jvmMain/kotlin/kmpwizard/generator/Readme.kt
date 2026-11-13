package kmpwizard.generator

import kmpwizard.TODO_SLICE
import kmpwizard.Target

fun readme(
    c: ProjectContext
) = buildString {
    val libraries = c.libIds
        .sorted()
        .joinToString()
        .ifEmpty {
            "none"
        }
    appendLine("# ${c.spec.name}")
    appendLine("Generated Kotlin Multiplatform project${if (c.hasUi) " (Compose Multiplatform UI)" else ""}.\n")
    appendLine("Targets: ${c.spec.targets.joinToString { it.label }}")
    appendLine("Libraries: $libraries\n")
    appendLine("## Setup")
    appendLine(
        "Includes the Gradle $GRADLE wrapper: run `./gradlew` (or `gradlew.bat` on Windows); " +
            "no Gradle install needed.\n"
    )
    appendLine("## Structure")
    appendLine(
        "Follows the [recommended KMP project structure]" +
            "(https://kotlinlang.org/docs/multiplatform/multiplatform-project-recommended-structure.html):"
    )
    appendLine("entry points are separate modules, shared code is split into logic and UI.\n")
    appendLine("```")
    appendLine("core/                       shared kernel used by every slice (logging, errors), no UI")
    if (c.hasUi) appendLine(
        "coreUI/                     UI kernel used by every slice: the Feature contract the app shell renders"
    )
    appendLine("feature/")
    appendLine(
        "  <slice>/                  one directory per vertical slice, package <pkg>.<slice>, split by where it runs"
    )
    appendLine("    shared/                 KMP, every target")
    appendLine("      domain/               entities, repository interfaces, use cases (no framework imports)")
    appendLine("      data/                 repository implementations, wire DTOs")
    if (c.hasUi) {
        appendLine("    client/                 KMP + Compose, client targets only")
        appendLine("      presentation/         UI state + Compose screens")
        appendLine("      <Slice>Feature.kt     slice composition root for the app shell")
    }
    if (c.has(Target.SERVER)) {
        appendLine("    server/                 JVM, server only")
        appendLine("      server/               Ktor routes + server-only adapters")
    }
    appendLine("  (${c.allSlices.joinToString()})")
    if (c.hasUi) {
        appendLine("app/")
        appendLine(
            "  sharedUI/                 Compose app shell (App, AppFeatures: registry of slices), " +
                "depends on every feature/<slice>/client"
        )
        if (c.has(kmpwizard.Target.ANDROID)) appendLine("  androidApp/               Android entry point")
        if (c.has(kmpwizard.Target.DESKTOP)) appendLine("  desktopApp/               desktop (JVM) entry point")
        if (c.has(kmpwizard.Target.WEB)) appendLine(
            "  webApp/                   web entry point (JS + Wasm, shared webMain)"
        )
        if (c.has(kmpwizard.Target.IOS)) appendLine(
            "  iosApp/                   Xcode host app, imports the `$IOS_FRAMEWORK` framework"
        )
    }
    if (c.has(kmpwizard.Target.SERVER)) appendLine(
        "server/                     Ktor bootstrap (config, plugins, wiring), depends on every feature/<slice>/server"
    )
    appendLine("```")
    appendLine(
        "Dependency rule: presentation -> domain <- data <- server. " +
            "Slices must not import each other; share through `core`.\n"
    )
    appendLine(
        "Each slice is a directory under `feature/` with its own `shared`, `client` and `server` modules " +
            "(`:feature:<slice>:shared` etc.),"
    )
    appendLine(
        "all in one package, so the apps carry no server code and the server no Compose. " +
            "Slices depend only on `core` (and `coreUI`), never on each other.\n"
    )
    appendLine("Platform entry points use the package `${c.pkg}.app.<platform>`.")
    if (c.has(kmpwizard.Target.SERVER)) {
        appendLine("The server exposes each slice over HTTP (`GET /api/<slice>`).")
        appendLine(
            "Its port is set in `server/src/main/resources/application.conf` " +
                "(default $SERVER_PORT, or the `PORT` env var)."
        )
    }
    if (c.hasTodo) {
        val viewModel = if (c.hasUi) "`TodoViewModel` (from Koin) -> " else ""
        appendLine("\n## Todo sample")
        appendLine(
            "The `$TODO_SLICE` slice is a full round trip: Compose screen -> $viewModel" +
                "use cases -> `HttpTodoRepository` (Ktor client)"
        )
        appendLine(
            "-> `/api/$TODO_SLICE` on the server -> use cases -> `MongoTodoRepository` (server only) -> MongoDB."
        )
        appendLine(
            "All of it lives in package `${c.pkg}.$TODO_SLICE` under `feature/$TODO_SLICE/`; " +
                "clients and server share its domain, use cases and wire DTOs from `feature/$TODO_SLICE/shared`.\n"
        )
        appendLine("1. Start MongoDB: `docker compose up -d` (or set `MONGODB_URI` to your own instance).")
        appendLine("2. Start the server: `./gradlew :server:run`.")
        appendLine(
            "3. Run any client and open the Todo tab. Clients use `http://localhost:$SERVER_PORT`; " +
                "the Android emulator uses `http://10.0.2.2:$SERVER_PORT`."
        )
        appendLine("   Change it in `core/src/*Main/kotlin/${c.dir}/core/ServerUrl.*.kt`.")
    }
    if (c.lib(FlywayGenerator.ID)) append(FlywayGenerator.readme(c))
    appendLine("\n## Run")
    if (c.has(kmpwizard.Target.DESKTOP)) appendLine("- Desktop: `./gradlew :app:desktopApp:run`")
    if (c.has(kmpwizard.Target.ANDROID)) appendLine("- Android: `./gradlew :app:androidApp:installDebug`")
    if (c.has(kmpwizard.Target.WEB)) {
        appendLine("- Web (Wasm): `./gradlew :app:webApp:wasmJsBrowserDevelopmentRun`")
        appendLine("- Web (JS): `./gradlew :app:webApp:jsBrowserDevelopmentRun`")
    }
    if (c.has(kmpwizard.Target.IOS)) appendLine("- iOS: see `app/iosApp/README.md`")
    if (c.has(kmpwizard.Target.SERVER)) appendLine(
        "- Server: `./gradlew :server:run`, then open http://localhost:$SERVER_PORT/api/${c.allSlices.first()}"
    )
    appendLine("- Tests: `./gradlew allTests`" + if (c.has(Target.SERVER)) " and `./gradlew :server:test`" else "")
}
