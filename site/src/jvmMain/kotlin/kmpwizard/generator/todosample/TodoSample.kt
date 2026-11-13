package kmpwizard.generator.todosample

import kmpwizard.TODO_SLICE
import kmpwizard.generator.ProjectContext
import kmpwizard.generator.SERVER_PORT
import kmpwizard.generator.coreUiPkg
import kmpwizard.generator.featureSrc
import kmpwizard.generator.slicePkg

// ---------- Todo sample: clients -> HTTP -> server -> MongoDB, sharing domain + DTOs via core ----------

/** Result type the Todo use cases return, depending on whether Arrow is selected. */
private fun todoResult(
    c: ProjectContext
    , t: String
) = if (c.lib("arrow")) "Either<DomainError, $t>" else "Result<$t>"

private fun todoResultImports(
    c: ProjectContext
) = if (c.lib("arrow")) "import arrow.core.Either\nimport ${c.pkg}.core.DomainError\n" else ""

fun todoSample(
    c: ProjectContext
    , f: MutableMap<String, String>
) {
    val s = TODO_SLICE
    val p = slicePkg(c, s)
    // Every layer of the slice lives in one package, across feature/todo/shared, client and server.
    val b = featureSrc(c, s, "commonMain")
    // Client-only code (Compose, Ktor client) goes in feature/todo/client; the server never depends on it.
    val ub = featureSrc(c, s, "clientMain")
    val hasArrow = c.lib("arrow")
    val imports = todoResultImports(c)
    fun r(
        t: String
    ) = todoResult(c, t)

    // Where clients find the server. The Android emulator reaches the host machine at 10.0.2.2.
    val core = "core/src/%s/kotlin/${c.dir}/core"
    f["${core.format("commonMain")}/ServerUrl.kt"] = """
            package ${c.pkg}.core

            /** Base URL of the Ktor server, per platform. */
            expect val defaultServerUrl: String
        """.trimIndent()
    listOf("androidMain" to "android", "iosMain" to "ios", "jvmMain" to "jvm", "webMain" to "web")
        .filter {
            c.isSetActive(it.first)
        }
        .forEach { (set, name) ->
            val host = if (set == "androidMain") "10.0.2.2" else "localhost"
            f["${core.format(set)}/ServerUrl.$name.kt"] =
                "package ${c.pkg}.core\n\nactual val defaultServerUrl: String = \"http://$host:$SERVER_PORT\"\n"
        }

    // ----- domain -----
    f["$b/domain/Todo.kt"] =
        "package $p.domain\n\ndata class Todo(val id: TodoId, val title: String, val done: Boolean)\n"
    f["$b/domain/TodoId.kt"] = """
            @file:OptIn(ExperimentalUuidApi::class)

            package $p.domain

            import kotlin.jvm.JvmInline
            import kotlin.uuid.ExperimentalUuidApi
            import kotlin.uuid.Uuid

            /**
             * Identity of a [Todo]: a UUID version 7. Its leading bits are a millisecond timestamp,
             * so ids sort by creation time (also in their string form).
             */
            @JvmInline
            value class TodoId(val value: Uuid) {
                override fun toString() = value.toString()

                companion object {
                    /** A new, time-ordered id. */
                    fun generate() = TodoId(Uuid.generateV7())

                    /** Parses the hex-and-dash form; throws IllegalArgumentException if [text] is not a UUID. */
                    fun parse(text: String) = TodoId(Uuid.parse(text))

                    /** Parses the hex-and-dash form; null if [text] is not a UUID. */
                    fun parseOrNull(text: String) = Uuid.parseOrNull(text)?.let(::TodoId)
                }
            }
        """.trimIndent()
    f["$b/domain/TodoRepository.kt"] = """
            package $p.domain

            interface TodoRepository {
                suspend fun all(): List<Todo>
                suspend fun add(title: String): Todo
                /** Returns null when no todo has this id. */
                suspend fun setDone(id: TodoId, done: Boolean): Todo?
                /** Returns false when no todo has this id. */
                suspend fun delete(id: TodoId): Boolean
            }
        """.trimIndent()
    f["$b/domain/Attempt.kt"] = if (hasArrow) """
            package $p.domain

            import arrow.core.Either
            import ${c.pkg}.core.DomainError

            /** Runs [block], turning failures into [DomainError]s. */
            internal inline fun <T> attempt(block: () -> T): Either<DomainError, T> =
                Either.catch(block).mapLeft {
                    when (it) {
                        is IllegalArgumentException -> DomainError.Invalid(it.message ?: "invalid")
                        is NoSuchElementException -> DomainError.NotFound(it.message ?: "not found")
                        else -> DomainError.Unexpected(it.message ?: "unknown")
                    }
                }
        """.trimIndent() else """
            package $p.domain

            /**
             * Runs [block], capturing failures.
             * IllegalArgumentException = invalid input, NoSuchElementException = unknown id.
             */
            internal inline fun <T> attempt(block: () -> T): Result<T> = runCatching(block)
        """.trimIndent()
    f["$b/domain/TodoUseCases.kt"] = """
            package $p.domain

            __IMPORTS__

            class GetTodosUseCase(private val repository: TodoRepository) {
                suspend operator fun invoke(): ${r("List<Todo>")} = attempt { repository.all() }
            }

            class AddTodoUseCase(private val repository: TodoRepository) {
                suspend operator fun invoke(title: String): ${r("Todo")} = attempt {
                    require(title.isNotBlank()) { "Title must not be blank" }
                    repository.add(title.trim())
                }
            }

            class SetTodoDoneUseCase(private val repository: TodoRepository) {
                suspend operator fun invoke(id: TodoId, done: Boolean): ${r("Todo")} = attempt {
                    repository.setDone(id, done) ?: throw NoSuchElementException("No todo with id ${'$'}id")
                }
            }

            class DeleteTodoUseCase(private val repository: TodoRepository) {
                suspend operator fun invoke(id: TodoId): ${r("Unit")} = attempt {
                    if (!repository.delete(id)) throw NoSuchElementException("No todo with id ${'$'}id")
                }
            }
        """.trimIndent()
        .replace("__IMPORTS__\n", imports)
        .replace("\n\n\n", "\n\n")

    // ----- data: wire format shared with the server, plus the HTTP client adapter -----
    f["$b/data/TodoDtos.kt"] = """
            package $p.data

            import kotlinx.serialization.Serializable
            import $p.domain.Todo
            import $p.domain.TodoId

            /** Wire format of /api/$s, used by both the HTTP client and the server. [id] is a UUID v7 string. */
            @Serializable
            data class TodoDto(val id: String, val title: String, val done: Boolean)

            @Serializable
            data class NewTodoDto(val title: String)

            @Serializable
            data class TodoPatchDto(val done: Boolean)

            fun Todo.toDto() = TodoDto(id.toString(), title, done)
            fun TodoDto.toDomain() = Todo(TodoId.parse(id), title, done)
        """.trimIndent()
    f["$ub/data/HttpTodoRepository.kt"] = """
            package $p.data

            import io.ktor.client.HttpClient
            import io.ktor.client.call.body
            import io.ktor.client.plugins.ClientRequestException
            import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
            import io.ktor.client.request.delete
            import io.ktor.client.request.get
            import io.ktor.client.request.patch
            import io.ktor.client.request.post
            import io.ktor.client.request.setBody
            import io.ktor.http.ContentType
            import io.ktor.http.HttpStatusCode
            import io.ktor.http.contentType
            import io.ktor.serialization.kotlinx.json.json
            import $p.domain.Todo
            import $p.domain.TodoId
            import $p.domain.TodoRepository

            /** The engine is picked per platform from the Ktor engine on the classpath. */
            internal fun todoHttpClient() = HttpClient {
                expectSuccess = true
                install(ContentNegotiation) { json() }
            }

            /** Talks to the server's /api/$s endpoints. Ktor stays an implementation detail of core. */
            class HttpTodoRepository internal constructor(private val client: HttpClient, baseUrl: String) : TodoRepository {
                constructor(baseUrl: String) : this(todoHttpClient(), baseUrl)

                private val url = "${'$'}baseUrl/api/$s"

                override suspend fun all(): List<Todo> =
                    client.get(url).body<List<TodoDto>>().map { it.toDomain() }

                override suspend fun add(title: String): Todo =
                    client.post(url) {
                        contentType(ContentType.Application.Json)
                        setBody(NewTodoDto(title))
                    }.body<TodoDto>().toDomain()

                override suspend fun setDone(id: TodoId, done: Boolean): Todo? = nullIfNotFound {
                    client.patch("${'$'}url/${'$'}id") {
                        contentType(ContentType.Application.Json)
                        setBody(TodoPatchDto(done))
                    }.body<TodoDto>().toDomain()
                }

                override suspend fun delete(id: TodoId): Boolean =
                    nullIfNotFound { client.delete("${'$'}url/${'$'}id") } != null

                private inline fun <T> nullIfNotFound(block: () -> T): T? = try {
                    block()
                } catch (e: ClientRequestException) {
                    if (e.response.status == HttpStatusCode.NotFound) null else throw e
                }
            }
        """.trimIndent()

    // ----- tests: use cases against an in-memory fake -----
    val tb = featureSrc(c, s, "commonTest")
    f["$tb/FakeTodoRepository.kt"] = """
            package $p

            import $p.domain.Todo
            import $p.domain.TodoId
            import $p.domain.TodoRepository

            class FakeTodoRepository : TodoRepository {
                private val todos = linkedMapOf<TodoId, Todo>()

                override suspend fun all() = todos.values.toList()
                override suspend fun add(title: String) = Todo(TodoId.generate(), title, false).also { todos[it.id] = it }
                override suspend fun setDone(id: TodoId, done: Boolean) = todos[id]?.copy(done = done)?.also { todos[id] = it }
                override suspend fun delete(id: TodoId) = todos.remove(id) != null
            }
        """.trimIndent()
    f["$tb/TodoUseCasesTest.kt"] = if (c.lib("kotest")) """
            package $p

            import $p.domain.AddTodoUseCase
            import $p.domain.GetTodosUseCase
            import $p.domain.SetTodoDoneUseCase
            import $p.domain.TodoId
            import io.kotest.core.spec.style.StringSpec
            import io.kotest.matchers.nulls.shouldBeNull
            import io.kotest.matchers.shouldBe

            class TodoUseCasesTest : StringSpec({
                "added todos are listed" {
                    val repo = FakeTodoRepository()
                    AddTodoUseCase(repo)("  Buy milk ")
                    GetTodosUseCase(repo)().getOrNull()!!.map { it.title } shouldBe listOf("Buy milk")
                }
                "blank titles are rejected" {
                    AddTodoUseCase(FakeTodoRepository())(" ").getOrNull().shouldBeNull()
                }
                "marking an unknown todo fails" {
                    SetTodoDoneUseCase(FakeTodoRepository())(TodoId.generate(), true).getOrNull().shouldBeNull()
                }
            })
        """.trimIndent() else """
            package $p

            import $p.domain.AddTodoUseCase
            import $p.domain.GetTodosUseCase
            import $p.domain.SetTodoDoneUseCase
            import $p.domain.TodoId
            import kotlinx.coroutines.test.runTest
            import kotlin.test.Test
            import kotlin.test.assertEquals
            import kotlin.test.assertNull

            class TodoUseCasesTest {
                @Test fun addedTodosAreListed() = runTest {
                    val repo = FakeTodoRepository()
                    AddTodoUseCase(repo)("  Buy milk ")
                    assertEquals(listOf("Buy milk"), GetTodosUseCase(repo)().getOrNull()!!.map { it.title })
                }

                @Test fun blankTitlesAreRejected() = runTest {
                    assertNull(AddTodoUseCase(FakeTodoRepository())(" ").getOrNull())
                }

                @Test fun markingAnUnknownTodoFails() = runTest {
                    assertNull(SetTodoDoneUseCase(FakeTodoRepository())(TodoId.generate(), true).getOrNull())
                }
            }
        """.trimIndent()

    if (!c.hasUi) return

    // ----- presentation: TodoViewModel (from Koin) holds the state, TodoScreen renders it -----
    val report =
        if (hasArrow) "fold({ report(it.message); null }, { report(null); it })"
        else "fold(onSuccess = { report(null); it }, onFailure = { report(it.message ?: \"error\"); null })"
    f["$ub/presentation/TodoViewModel.kt"] = """
            package $p.presentation

            import androidx.compose.runtime.getValue
            import androidx.compose.runtime.mutableStateOf
            import androidx.compose.runtime.setValue
            import androidx.lifecycle.ViewModel
            import androidx.lifecycle.viewModelScope
            import kotlinx.coroutines.flow.MutableStateFlow
            import kotlinx.coroutines.flow.StateFlow
            import kotlinx.coroutines.flow.asStateFlow
            import kotlinx.coroutines.flow.update
            import kotlinx.coroutines.launch
            __IMPORTS__
            import $p.domain.AddTodoUseCase
            import $p.domain.DeleteTodoUseCase
            import $p.domain.GetTodosUseCase
            import $p.domain.SetTodoDoneUseCase
            import $p.domain.Todo
            import $p.domain.TodoId

            data class TodoUiState(
                val todos: List<Todo> = emptyList(),
                val loading: Boolean = true,
                /** Last server error, cleared by the next successful call. */
                val error: String? = null,
            )

            /**
             * State holder of the Todo screen. It outlives the composition (e.g. switching tabs), so the list
             * isn't reloaded each time the screen is shown. Work runs in [viewModelScope], cancelled when it's cleared.
             */
            class TodoViewModel(
                private val getTodos: GetTodosUseCase,
                private val addTodo: AddTodoUseCase,
                private val setTodoDone: SetTodoDoneUseCase,
                private val deleteTodo: DeleteTodoUseCase,
            ) : ViewModel() {
                private val _state = MutableStateFlow(TodoUiState())
                val state: StateFlow<TodoUiState> = _state.asStateFlow()

                /** Text field input. Compose state, not a flow, so typing updates it synchronously. */
                var newTitle by mutableStateOf("")

                init { refresh() }

                fun refresh() {
                    _state.update { it.copy(loading = true) }
                    viewModelScope.launch {
                        val todos = getTodos().orReport()
                        _state.update { it.copy(loading = false, todos = todos ?: it.todos) }
                    }
                }

                fun add() {
                    val title = newTitle.takeIf { it.isNotBlank() } ?: return
                    viewModelScope.launch {
                        addTodo(title).orReport()?.let { todo ->
                            _state.update { it.copy(todos = it.todos + todo) }
                            newTitle = ""
                        }
                    }
                }

                fun setDone(id: TodoId, done: Boolean) {
                    viewModelScope.launch {
                        setTodoDone(id, done).orReport()?.let { updated ->
                            _state.update { s -> s.copy(todos = s.todos.map { if (it.id == updated.id) updated else it }) }
                        }
                    }
                }

                fun delete(id: TodoId) {
                    viewModelScope.launch {
                        deleteTodo(id).orReport()?.let { _state.update { s -> s.copy(todos = s.todos.filterNot { it.id == id }) } }
                    }
                }

                private fun report(error: String?) = _state.update { it.copy(error = error) }

                /** Shows a failure and returns null, or clears the error and returns the value. */
                private fun <T> ${r("T")}.orReport(): T? = $report
            }
        """.trimIndent()
        .replace("__IMPORTS__\n", imports)
    f["$ub/presentation/TodoScreen.kt"] = """
            package $p.presentation

            import androidx.compose.foundation.layout.Arrangement
            import androidx.compose.foundation.layout.Column
            import androidx.compose.foundation.layout.Row
            import androidx.compose.foundation.layout.padding
            import androidx.compose.material3.Button
            import androidx.compose.material3.Checkbox
            import androidx.compose.material3.MaterialTheme
            import androidx.compose.material3.OutlinedTextField
            import androidx.compose.material3.Text
            import androidx.compose.material3.TextButton
            import androidx.compose.runtime.Composable
            import androidx.compose.runtime.collectAsState
            import androidx.compose.runtime.getValue
            import androidx.compose.ui.Alignment
            import androidx.compose.ui.Modifier
            import androidx.compose.ui.text.style.TextDecoration
            import androidx.compose.ui.unit.dp

            @Composable
            fun TodoScreen(viewModel: TodoViewModel) {
                val state by viewModel.state.collectAsState()

                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = viewModel.newTitle,
                            onValueChange = { viewModel.newTitle = it },
                            label = { Text("New todo") },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                        Button(enabled = viewModel.newTitle.isNotBlank(), onClick = viewModel::add) { Text("Add") }
                    }
                    state.error?.let { Text("Server error: ${'$'}it", color = MaterialTheme.colorScheme.error) }
                    when {
                        state.loading -> Text("Loading…")
                        state.todos.isEmpty() -> Text("Nothing to do.")
                    }
                    state.todos.forEach { todo ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = todo.done, onCheckedChange = { viewModel.setDone(todo.id, it) })
                            Text(
                                todo.title,
                                modifier = Modifier.weight(1f),
                                textDecoration = if (todo.done) TextDecoration.LineThrough else null,
                            )
                            TextButton(onClick = { viewModel.delete(todo.id) }) { Text("Delete") }
                        }
                    }
                }
            }
        """.trimIndent()
    // Koin builds the object graph; the screen gets its ViewModel with koinViewModel().
    f["$ub/TodoModule.kt"] = """
        package $p

        import org.koin.core.module.dsl.factoryOf
        import org.koin.core.module.dsl.viewModelOf
        import org.koin.dsl.module
        import ${c.pkg}.core.defaultServerUrl
        import $p.data.HttpTodoRepository
        import $p.domain.AddTodoUseCase
        import $p.domain.DeleteTodoUseCase
        import $p.domain.GetTodosUseCase
        import $p.domain.SetTodoDoneUseCase
        import $p.domain.TodoRepository
        import $p.presentation.TodoViewModel

        /** Composition root of the `$s` slice on clients: todos are stored on the server over HTTP. Loaded by App. */
        val todoModule = module {
            single<TodoRepository> { HttpTodoRepository(defaultServerUrl) }
            factoryOf(::GetTodosUseCase)
            factoryOf(::AddTodoUseCase)
            factoryOf(::SetTodoDoneUseCase)
            factoryOf(::DeleteTodoUseCase)
            viewModelOf(::TodoViewModel)
        }
    """.trimIndent()
    f["$ub/TodoFeature.kt"] = """
        package $p

        import androidx.compose.runtime.Composable
        import org.koin.compose.viewmodel.koinViewModel
        import ${coreUiPkg(c)}.Feature
        import $p.presentation.TodoScreen

        object TodoFeature : Feature {
            override val title = "Todo"

            /** The ViewModel comes from [todoModule], scoped to the platform's ViewModelStoreOwner. */
            @Composable override fun Content() = TodoScreen(koinViewModel())
        }
    """.trimIndent()
}

/** Server side of the Todo sample: MongoDB adapter + HTTP routes. */
fun todoServer(
    c: ProjectContext
    , f: MutableMap<String, String>
) {
    val s = TODO_SLICE
    val p = slicePkg(c, s)
    val tp = "$p.server"
    val td = "${featureSrc(c, s, "serverMain")}/server"
    val hasArrow = c.lib("arrow")
    f["$td/MongoTodoRepository.kt"] = """
            package $tp

            import com.mongodb.client.model.Filters.eq
            import com.mongodb.client.model.FindOneAndUpdateOptions
            import com.mongodb.client.model.ReturnDocument
            import com.mongodb.client.model.Sorts
            import com.mongodb.client.model.Updates
            import com.mongodb.kotlin.client.coroutine.MongoDatabase
            import kotlinx.coroutines.flow.map
            import kotlinx.coroutines.flow.toList
            import org.bson.Document
            import $p.domain.Todo
            import $p.domain.TodoId
            import $p.domain.TodoRepository

            /**
             * Stores todos in the `todos` collection. Server-only data adapter for the `$s` slice.
             * `_id` holds the [TodoId] as a UUID v7 string, so sorting by `_id` sorts by creation time.
             */
            class MongoTodoRepository(db: MongoDatabase) : TodoRepository {
                private val todos = db.getCollection<Document>("todos")

                override suspend fun all(): List<Todo> =
                    todos.find().sort(Sorts.ascending("_id")).map { it.toTodo() }.toList()

                override suspend fun add(title: String): Todo {
                    val doc = Document("_id", TodoId.generate().toString()).append("title", title).append("done", false)
                    todos.insertOne(doc)
                    return doc.toTodo()
                }

                override suspend fun setDone(id: TodoId, done: Boolean): Todo? =
                    todos.findOneAndUpdate(
                        eq("_id", id.toString()),
                        Updates.set("done", done),
                        FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER),
                    )?.toTodo()

                override suspend fun delete(id: TodoId): Boolean =
                    todos.deleteOne(eq("_id", id.toString())).deletedCount > 0
            }

            private fun Document.toTodo() = Todo(TodoId.parse(getString("_id")), getString("title"), getBoolean("done", false))
        """.trimIndent()

    fun fold(
        ok: String
    ) = if (hasArrow) "fold({ call.respondError(it) }, { $ok })"
    else "fold(onSuccess = { $ok }, onFailure = { call.respondError(it) })"
    val respondError = if (hasArrow) """
            private suspend fun ApplicationCall.respondError(error: DomainError) = when (error) {
                is DomainError.Invalid -> respond(HttpStatusCode.BadRequest, error.message)
                is DomainError.NotFound -> respond(HttpStatusCode.NotFound, error.message)
                is DomainError.Unexpected -> respond(HttpStatusCode.InternalServerError, error.message)
            }
        """.trimIndent() else """
            private suspend fun ApplicationCall.respondError(error: Throwable) = when (error) {
                is IllegalArgumentException -> respond(HttpStatusCode.BadRequest, error.message ?: "Bad request")
                is NoSuchElementException -> respond(HttpStatusCode.NotFound, error.message ?: "Not found")
                else -> respond(HttpStatusCode.InternalServerError, error.message ?: "error")
            }
        """.trimIndent()
    f["$td/TodoRoutes.kt"] = """
            package $tp

            import io.ktor.http.HttpStatusCode
            import io.ktor.server.application.ApplicationCall
            import io.ktor.server.request.receive
            import io.ktor.server.response.respond
            import io.ktor.server.routing.Route
            import io.ktor.server.routing.delete
            import io.ktor.server.routing.get
            import io.ktor.server.routing.patch
            import io.ktor.server.routing.post
            import io.ktor.server.routing.route
            ${if (hasArrow) "import ${c.pkg}.core.DomainError" else ""}
            import $p.data.NewTodoDto
            import $p.data.TodoPatchDto
            import $p.data.toDto
            import $p.domain.AddTodoUseCase
            import $p.domain.DeleteTodoUseCase
            import $p.domain.GetTodosUseCase
            import $p.domain.SetTodoDoneUseCase
            import $p.domain.TodoId
            import $p.domain.TodoRepository

            /**
             * HTTP adapter for the `$s` slice.
             *   GET    /api/$s           list
             *   POST   /api/$s           create  {"title": "..."}
             *   PATCH  /api/$s/{id}      update  {"done": true}
             *   DELETE /api/$s/{id}      delete
             * {id} is a UUID v7; anything else gets 400 Bad Request.
             */
            fun Route.todoRoutes(repository: TodoRepository) {
                val getTodos = GetTodosUseCase(repository)
                val addTodo = AddTodoUseCase(repository)
                val setDone = SetTodoDoneUseCase(repository)
                val deleteTodo = DeleteTodoUseCase(repository)
                route("/api/$s") {
                    get { getTodos().${fold("todos -> call.respond(todos.map { it.toDto() })")} }
                    post {
                        val body = call.receive<NewTodoDto>()
                        addTodo(body.title).${fold("call.respond(HttpStatusCode.Created, it.toDto())")}
                    }
                    patch("/{id}") {
                        val id = call.todoId() ?: return@patch call.respondInvalidId()
                        val body = call.receive<TodoPatchDto>()
                        setDone(id, body.done).${fold("call.respond(it.toDto())")}
                    }
                    delete("/{id}") {
                        val id = call.todoId() ?: return@delete call.respondInvalidId()
                        deleteTodo(id).${fold("call.respond(HttpStatusCode.NoContent)")}
                    }
                }
            }

            private fun ApplicationCall.todoId() = parameters["id"]?.let(TodoId::parseOrNull)

            private suspend fun ApplicationCall.respondInvalidId() =
                respond(HttpStatusCode.BadRequest, "Todo id must be a UUID")

            __RESPOND_ERROR__
        """.trimIndent()
        .replace("\n\n\n", "\n\n")
        .replace("route\n\n", "route\n")
        .replace("__RESPOND_ERROR__", respondError) + "\n"
}
