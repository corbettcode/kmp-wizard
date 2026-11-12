# KMP Wizard

A [Kobweb](https://github.com/varabyte/kobweb) web app that walks you through a few choices and generates a
downloadable Kotlin Multiplatform project as a ZIP.

Pick the targets (Android, iOS, desktop, web, Ktor server), name your vertical slices, and add optional libraries
(Arrow, Kermit, Kotest, Koin, Ktor, Occurrent, kotlinx-datetime, Turbine, Flyway, ...). The generated project uses
clean architecture split into vertical slices: each slice gets its own `feature/<slice>/` directory with `shared`,
`client` and `server` modules. An optional Todo sample shows a full round trip from a Compose screen over HTTP to a
Ktor server backed by MongoDB.

## Project layout

```
site/src/jsMain      wizard UI (Compose HTML + Silk)
site/src/jvmMain     API: POST /api/generate, plus the project generator (kmpwizard.generator)
site/src/commonMain  ProjectSpecification and validation, shared by UI and API
```

## Run locally

```bash
cd site
kobweb run
```

Then open [http://localhost:8080](http://localhost:8080).

## Docker

The `Dockerfile` exports the site in Kobweb's fullstack layout and runs the Kobweb server on a Java 21 runtime:

```bash
docker build -t kmp-wizard .
docker run --rm -p 8080:8080 kmp-wizard
```

The build takes a few minutes: the export renders each page in headless Chromium, which Kobweb downloads during the
build.

## Deploy to DigitalOcean App Platform

`.do/app.yaml` describes the app: one 512 MB instance built from the `Dockerfile` on `master`, serving port 8080 and
redeploying on every push.

1. Give DigitalOcean's GitHub app access to this repository.
2. Create the app:

   ```bash
   doctl apps create --spec .do/app.yaml
   ```

   or use **Create App** in the control panel and pick this repository; it detects the `Dockerfile`.
3. After changing the spec, apply it with `doctl apps update <app-id> --spec .do/app.yaml`.
