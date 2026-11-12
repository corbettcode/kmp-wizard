package kmpwizard.generator

// ---------- Flyway: SQL schema migrations for the server, PostgreSQL at runtime, H2 in tests ----------

const val FLYWAY = "13.12.2"
const val POSTGRESQL_JDBC = "42.7.14"
const val H2 = "2.5.252"

/**
 * Everything the "flyway" library option adds to a generated project. Server only:
 * ProjectGenerator calls these hooks when `c.lib(FlywayGenerator.ID)` is true.
 */
object FlywayGenerator {
    const val ID = "flyway"

    /** Database name, user and password used by docker-compose and the default JDBC URL. */
    private fun dbName(c: ProjectContext) = c.slug.replace('-', '_')
    private const val DB_USER = "app"
    private const val DB_PASSWORD = "app"
    private const val DB_PORT = 5432

    /** H2 in PostgreSQL mode, so server tests run the real migrations without Docker. */
    private fun h2Url(name: String) =
        "jdbc:h2:mem:$name;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1"

    /** Entries for `[versions]` in libs.versions.toml. */
    fun versions(v: MutableMap<String, String>) {
        v["flyway"] = FLYWAY
        v["postgresql"] = POSTGRESQL_JDBC
        v["h2"] = H2
    }

    /** Entries for `[libraries]`: alias, module, version ref. */
    fun libraries(lib: (alias: String, module: String, ref: String) -> Unit) {
        lib("flyway-core", "org.flywaydb:flyway-core", "flyway")
        lib("flyway-database-postgresql", "org.flywaydb:flyway-database-postgresql", "flyway")
        lib("postgresql", "org.postgresql:postgresql", "postgresql")
        lib("h2", "com.h2database:h2", "h2")
    }

    /** Lines for the `dependencies {}` block of server/build.gradle.kts (no leading indent). */
    val serverDependencies = listOf(
        "implementation(libs.flyway.core)",
        "runtimeOnly(libs.flyway.database.postgresql)",
        "runtimeOnly(libs.postgresql)",
        "testRuntimeOnly(libs.h2)",
    )

    /** A `services:` entry and its `volumes:` entry for docker-compose.yml. */
    fun dockerService(c: ProjectContext) = """
        postgres:
          image: postgres:18
          environment:
            POSTGRES_DB: ${dbName(c)}
            POSTGRES_USER: $DB_USER
            POSTGRES_PASSWORD: $DB_PASSWORD
          ports:
            - "$DB_PORT:$DB_PORT"
          volumes:
            - postgres-data:/var/lib/postgresql
    """.trimIndent() to "postgres-data:"

    fun applicationImports(c: ProjectContext) = listOf(
        "import ${c.appPkg("server")}.db.DatabaseConfig",
        "import ${c.appPkg("server")}.db.migrate",
    )

    /** Statements for `Application.module()`, run before routing is set up (no leading indent). */
    val applicationStartup = listOf(
        "// Brings the SQL schema up to date before serving requests; startup fails if the database is unreachable.",
        "migrate(DatabaseConfig.from(environment.config.config(\"database\")))",
    )

    /** `database {}` block appended to application.conf. */
    fun config(c: ProjectContext) = """
        database {
            url = "jdbc:postgresql://localhost:$DB_PORT/${dbName(c)}"
            url = ${'$'}{?DATABASE_URL}
            user = "$DB_USER"
            user = ${'$'}{?DATABASE_USER}
            password = "$DB_PASSWORD"
            password = ${'$'}{?DATABASE_PASSWORD}
        }
    """.trimIndent()

    /** Expression for the test config of ApplicationTest: application.conf with the database swapped for H2. */
    const val TEST_CONFIG = "ApplicationConfig(\"application.conf\").withTestDatabase()"

    fun testImports(c: ProjectContext) = listOf("import ${c.appPkg("server")}.db.withTestDatabase")

    fun readme(c: ProjectContext) = buildString {
        appendLine("\n## Database (Flyway)")
        appendLine("The server keeps its SQL schema in versioned migrations under `server/src/main/resources/db/migration`.")
        appendLine("On startup it runs [Flyway](https://documentation.red-gate.com/flyway), which applies any pending ones to PostgreSQL.\n")
        appendLine("1. Start PostgreSQL: `docker compose up -d postgres` (or point `DATABASE_URL`, `DATABASE_USER`, `DATABASE_PASSWORD` at your own).")
        appendLine("2. Start the server: `./gradlew :server:run`. The log shows which migrations were applied.")
        appendLine("3. To change the schema, add a new file such as `V2__add_column.sql`. Never edit a migration that has already run.\n")
        appendLine("Server tests run the same migrations against an in-memory H2 database in PostgreSQL mode, so they need no Docker.")
        appendLine("Connection settings live in the `database` block of `server/src/main/resources/application.conf`.")
    }

    /** Server source, the first migration and tests. */
    fun files(c: ProjectContext, f: MutableMap<String, String>) {
        val sp = c.appPkg("server")
        val sd = c.appDir("server")
        f["server/src/main/kotlin/$sd/db/Migrations.kt"] = """
            package $sp.db

            import io.ktor.server.config.ApplicationConfig
            import org.flywaydb.core.Flyway

            /** Connection settings for the SQL database. */
            data class DatabaseConfig(val url: String, val user: String, val password: String) {
                companion object {
                    /** Reads the `database` block of application.conf. */
                    fun from(config: ApplicationConfig) = DatabaseConfig(
                        url = config.property("url").getString(),
                        user = config.property("user").getString(),
                        password = config.property("password").getString(),
                    )
                }
            }

            /**
             * Applies pending migrations from `src/main/resources/db/migration` (files named `V<n>__<description>.sql`)
             * and returns how many ran. Already-applied migrations are skipped, so this is safe on every start.
             */
            fun migrate(db: DatabaseConfig): Int =
                Flyway.configure()
                    .dataSource(db.url, db.user, db.password)
                    .locations("classpath:db/migration")
                    .load()
                    .migrate()
                    .migrationsExecuted
        """.trimIndent() + "\n"
        f["server/src/main/resources/db/migration/V1__create_app_setting.sql"] = """
            -- First migration. Flyway applies the files in db/migration in version order (V1, V2, ...)
            -- and records each one in flyway_schema_history. Add changes as new files; don't edit applied ones.
            CREATE TABLE app_setting (
                name    VARCHAR(100)  PRIMARY KEY,
                setting VARCHAR(1000) NOT NULL
            );

            INSERT INTO app_setting (name, setting) VALUES ('schema.owner', 'flyway');
        """.trimIndent() + "\n"
        f["server/src/test/kotlin/$sd/db/TestDatabase.kt"] = """
            package $sp.db

            import io.ktor.server.config.ApplicationConfig
            import io.ktor.server.config.MapApplicationConfig
            import io.ktor.server.config.mergeWith

            /** In-memory H2 in PostgreSQL mode: runs the real migrations without a database server. */
            fun testDatabase(name: String) = DatabaseConfig("${h2Url("\$name")}", "sa", "")

            /** This config with its `database` block pointing at an in-memory H2 database. */
            fun ApplicationConfig.withTestDatabase(name: String = "app"): ApplicationConfig {
                val db = testDatabase(name)
                return mergeWith(
                    MapApplicationConfig(
                        "database.url" to db.url,
                        "database.user" to db.user,
                        "database.password" to db.password,
                    )
                )
            }
        """.trimIndent() + "\n"
        f["server/src/test/kotlin/$sd/db/MigrationsTest.kt"] = """
            package $sp.db

            import java.sql.DriverManager
            import kotlin.test.Test
            import kotlin.test.assertEquals
            import kotlin.test.assertTrue

            class MigrationsTest {
                @Test fun migrationsApplyToAnEmptyDatabase() {
                    val db = testDatabase("migrations-fresh")
                    assertTrue(migrate(db) > 0)
                    DriverManager.getConnection(db.url, db.user, db.password).use { conn ->
                        conn.createStatement().executeQuery("SELECT setting FROM app_setting WHERE name = 'schema.owner'").use {
                            assertTrue(it.next())
                            assertEquals("flyway", it.getString(1))
                        }
                    }
                }

                @Test fun migratingTwiceAppliesNothingTheSecondTime() {
                    val db = testDatabase("migrations-twice")
                    migrate(db)
                    assertEquals(0, migrate(db))
                }
            }
        """.trimIndent() + "\n"
    }
}
