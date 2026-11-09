package kmpwizard.generator

import kmpwizard.TODO_SLICE
import kmpwizard.Target

fun readme(c: ProjectContext) = buildString {
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
        if (c.has(kmpwizard.Target.ANDROID)) appendLine("  androidApp/               Android entry point")
        if (c.has(kmpwizard.Target.DESKTOP)) appendLine("  desktopApp/               desktop (JVM) entry point")
        if (c.has(kmpwizard.Target.WEB)) appendLine("  webApp/                   web entry point (JS + Wasm, shared webMain)")
        if (c.has(kmpwizard.Target.IOS)) appendLine("  iosApp/                   Xcode host app, imports the `$IOS_FRAMEWORK` framework")
    }
    if (c.has(kmpwizard.Target.SERVER)) appendLine("server/                     Ktor server, depends on core only")
    appendLine("```")
    appendLine("Dependency rule: presentation -> domain <- data. Slices must not import each other; share through `core`.\n")
    appendLine("Platform entry points use the package `${c.pkg}.app.<platform>`.")
    if (c.has(kmpwizard.Target.SERVER)) {
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
    if (c.has(kmpwizard.Target.DESKTOP)) appendLine("- Desktop: `./gradlew :app:desktopApp:run`")
    if (c.has(kmpwizard.Target.ANDROID)) appendLine("- Android: `./gradlew :app:androidApp:installDebug`")
    if (c.has(kmpwizard.Target.WEB)) {
        appendLine("- Web (Wasm): `./gradlew :app:webApp:wasmJsBrowserDevelopmentRun`")
        appendLine("- Web (JS): `./gradlew :app:webApp:jsBrowserDevelopmentRun`")
    }
    if (c.has(kmpwizard.Target.IOS)) appendLine("- iOS: see `app/iosApp/README.md`")
    if (c.has(kmpwizard.Target.SERVER)) appendLine("- Server: `./gradlew :server:run`, then open http://localhost:$SERVER_PORT/api/${c.allSlices.first()}")
    appendLine("- Tests: `./gradlew allTests`" + if (c.has(Target.SERVER)) " and `./gradlew :server:test`" else "")
}
