# Builds the Kobweb site (static pages + API server) and runs it as a single container.
# Used by DigitalOcean App Platform (see .do/app.yaml); also works locally:
#   docker build -t kmp-wizard . && docker run --rm -p 8080:8080 kmp-wizard

# ---------- build: export the site in fullstack layout ----------
FROM eclipse-temurin:21-jdk-noble AS build

# `kobwebExport` snapshots each page in headless Chromium (via Playwright). Kobweb downloads the browser
# itself; Playwright's install-deps adds the system libraries it needs (Playwright needs Node 20+,
# newer than Ubuntu's).
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl ca-certificates \
    && curl -fsSL https://deb.nodesource.com/setup_22.x | bash - \
    && apt-get install -y --no-install-recommends nodejs \
    && npx --yes playwright install-deps chromium \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /project
COPY . .

# Same as `kobweb export --layout fullstack`, without needing the Kobweb CLI.
# Kotlin compiles in-process: a Kotlin daemon outlives the build and deletes its files in ~/.kotlin while
# App Platform's builder (kaniko) snapshots the layer, which fails the build.
RUN ./gradlew --no-daemon --console=plain :site:kobwebExport \
    -Pkotlin.compiler.execution.strategy=in-process \
    -PkobwebReuseServer=false \
    -PkobwebEnv=DEV \
    -PkobwebRunLayout=FULLSTACK \
    -PkobwebBuildTarget=RELEASE \
    -PkobwebExportLayout=FULLSTACK \
    && rm -rf /root/.kotlin

# ---------- run: just a JRE, the exported site and the server jar ----------
FROM eclipse-temurin:21-jre-noble

WORKDIR /app
COPY --from=build /project/site/.kobweb .kobweb

# Port is set in site/.kobweb/conf.yaml.
EXPOSE 8080
ENTRYPOINT [".kobweb/server/start.sh"]
