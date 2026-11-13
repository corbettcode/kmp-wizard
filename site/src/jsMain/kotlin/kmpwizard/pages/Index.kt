package kmpwizard.pages

import androidx.compose.runtime.*
import com.varabyte.kobweb.browser.api
import com.varabyte.kobweb.browser.http.bodyAsBytes
import com.varabyte.kobweb.browser.http.bodyOf
import com.varabyte.kobweb.compose.css.Cursor
import com.varabyte.kobweb.compose.css.FontWeight
import com.varabyte.kobweb.compose.css.autoLength
import com.varabyte.kobweb.compose.foundation.layout.Column
import com.varabyte.kobweb.compose.foundation.layout.Row
import com.varabyte.kobweb.compose.ui.Alignment
import com.varabyte.kobweb.compose.ui.Modifier
import com.varabyte.kobweb.compose.ui.graphics.Colors
import com.varabyte.kobweb.compose.ui.modifiers.*
import com.varabyte.kobweb.core.Page
import com.varabyte.kobweb.silk.components.forms.Button
import com.varabyte.kobweb.silk.components.forms.Checkbox
import com.varabyte.kobweb.silk.components.forms.Input
import com.varabyte.kobweb.silk.components.text.SpanText
import kmpwizard.*
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import org.jetbrains.compose.web.attributes.InputType
import org.jetbrains.compose.web.css.LineStyle
import org.jetbrains.compose.web.css.cssRem
import org.jetbrains.compose.web.css.em
import org.jetbrains.compose.web.css.px
import org.jetbrains.compose.web.dom.H1
import org.jetbrains.compose.web.dom.H3
import org.jetbrains.compose.web.dom.Text
import org.w3c.dom.HTMLAnchorElement
import org.w3c.dom.url.URL
import org.w3c.files.Blob
import org.w3c.files.BlobPropertyBag

private val steps = listOf("Project", "Platforms", "Slices", "Libraries", "Git", "Review")

@Page
@Composable
fun HomePage() {
    var step by remember { mutableStateOf(0) }
    var name by remember { mutableStateOf("My KMP App") }
    var pkg by remember { mutableStateOf("com.example.myapp") }
    var targets by remember { mutableStateOf(Target.entries.toSet()) }
    var slices by remember { mutableStateOf(listOf("greeting")) }
    var newSlice by remember { mutableStateOf("") }
    var libs by remember { mutableStateOf(setOf("kotest", "kermit")) }
    var wantsSampleTodo by remember { mutableStateOf(false) }
    var wantsGitInit by remember { mutableStateOf(false) }
    var commitMessage by remember { mutableStateOf("") }
    var isBusy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    // Drop libraries that became unavailable when platforms changed (e.g. Occurrent).
    val spec = ProjectSpecification(
        name
        , pkg
        , targets
        , slices
        , libs
            .filter {
                Catalog.byId[it]?.isAvailable(targets) == true
            }
            .toSet()
        , sampleTodo = wantsSampleTodo && Target.SERVER in targets
        , gitInit = wantsGitInit
        , commitMessage = if (wantsGitInit) commitMessage.trim() else ""
    )
    val errors = spec.validate()

    Column(
        Modifier
            .fillMaxWidth()
            .maxWidth(52.cssRem)
            .margin(leftRight = autoLength)
            .padding(2.cssRem)
            .gap(1.cssRem)
    ) {
        H1 {
            Text("KMP Project Wizard ")
            SpanText(
                "v$APP_VERSION"
                , Modifier
                    .fontSize(0.5.em)
                    .fontWeight(FontWeight.Normal)
                    .color(Colors.Gray)
            )
        }

        Row(Modifier.gap(1.cssRem)) {
            steps.forEachIndexed { i, label ->
                SpanText(
                    "${i + 1}. $label"
                    , Modifier
                        .fontWeight(if (i == step) FontWeight.Bold else FontWeight.Normal)
                        .opacity(if (i == step) 1.0 else 0.55)
                        .cursor(Cursor.Pointer)
                        .onClick {
                            step = i
                        }
                )
            }
        }

        Column(card) {
            when (step) {
                0 -> {
                    Field("Project name", name) { name = it }
                    Field("Base package", pkg) { pkg = it }
                }
                1 -> {
                    H3 { Text("Target platforms") }
                    Target.entries.forEach { t ->
                        Checkbox(
                            t in targets
                            , onCheckedChange = { isChecked -> targets = if (isChecked) targets + t else targets - t }
                        ) {
                            SpanText(t.label)
                        }
                    }
                }
                2 -> {
                    H3 { Text("Vertical slices") }
                    SpanText(
                        "Each slice gets its own domain / data / presentation packages, " +
                            "a use case, a screen and a test."
                        , Modifier.opacity(0.7)
                    )
                    slices.forEach { s ->
                        Row(
                            Modifier.gap(1.cssRem)
                            , verticalAlignment = Alignment.CenterVertically
                        ) {
                            SpanText(s)
                            Button(onClick = { slices = slices - s }) { Text("Remove") }
                        }
                    }
                    Row(
                        Modifier.gap(0.5.cssRem)
                        , verticalAlignment = Alignment.CenterVertically
                    ) {
                        Input(
                            InputType.Text
                            , value = newSlice
                            , onValueChange = { newSlice = it.lowercase() }
                            , modifier = Modifier.flexGrow(1)
                            , placeholder = "e.g. orders"
                        )
                        Button(
                            onClick = {
                                if (newSlice.isNotBlank() && newSlice !in slices) {
                                    slices = slices + newSlice.trim()
                                    newSlice = ""
                                }
                            }
                        ) { Text("Add slice") }
                    }
                    H3 { Text("Sample app") }
                    val isServerSelected = Target.SERVER in targets
                    Checkbox(
                        checked = isServerSelected && wantsSampleTodo
                        , onCheckedChange = { isChecked ->
                            wantsSampleTodo = isChecked
                            // Pre-select the libraries the sample uses; they stay selected if it's turned off again.
                            if (isChecked) libs = libs + TODO_LIBRARIES
                        }
                        , enabled = isServerSelected
                    ) {
                        SpanText(
                            "Todo app: a '$TODO_SLICE' slice whose screens call the Ktor server, " +
                                "which stores todos in MongoDB" +
                                if (isServerSelected) "" else " (select the Server platform first)"
                        )
                    }
                }
                3 -> Catalog.libraries
                    .groupBy {
                        it.category
                    }
                    .forEach { (cat, items) ->
                        H3 { Text(cat) }
                        items.forEach { lib ->
                            val isAvailable = lib.isAvailable(targets)
                            // The Todo sample needs these, so they can't be turned off while it's selected.
                            val isRequiredByTodo = spec.sampleTodo && lib.id in TODO_LIBRARIES
                            Checkbox(
                                checked = isAvailable && (lib.id in libs || isRequiredByTodo)
                                , onCheckedChange = { isChecked ->
                                    libs = if (isChecked) libs + lib.id else libs - lib.id
                                }
                                , enabled = isAvailable && !isRequiredByTodo
                            ) {
                                SpanText(
                                    "${lib.name}: ${lib.description}" +
                                        if (isRequiredByTodo) " (used by the Todo sample)" else ""
                                )
                            }
                        }
                    }
                4 -> {
                    H3 { Text("Git repository (optional)") }
                    Checkbox(
                        wantsGitInit
                        , onCheckedChange = { wantsGitInit = it }
                    ) {
                        SpanText("Set up a local git repository (branch 'main')")
                    }
                    if (wantsGitInit) {
                        Column(Modifier.gap(0.25.cssRem)) {
                            SpanText(
                                "Initial commit message (optional)"
                                , Modifier.fontWeight(FontWeight.SemiBold)
                            )
                            Input(
                                InputType.Text
                                , value = commitMessage
                                , onValueChange = { commitMessage = it }
                                , modifier = Modifier.fillMaxWidth()
                                , placeholder = "e.g. Initial commit"
                            )
                        }
                        SpanText(
                            "With a message, all files are committed as \"KMP Wizard\" (amend with " +
                                "git commit --amend --reset-author to make it yours). Leave it empty to only " +
                                "initialize the repository and make the first commit yourself."
                            , Modifier.opacity(0.7)
                        )
                    }
                }
                else -> {
                    val platforms = spec.targets
                        .joinToString {
                            it.label
                        }
                        .ifEmpty {
                            "none"
                        }
                    val sliceNames = spec.slices
                        .joinToString()
                        .ifEmpty {
                            "none"
                        }
                    val libraries = spec.libraries
                        .joinToString()
                        .ifEmpty {
                            "none"
                        }
                    H3 { Text("Review") }
                    SpanText("${spec.name} (${spec.packageName})")
                    SpanText("Platforms: $platforms")
                    SpanText("Slices: $sliceNames")
                    SpanText("Todo sample (MongoDB): ${if (spec.sampleTodo) "yes" else "no"}")
                    SpanText("Libraries: $libraries")
                    SpanText(
                        "Git: " + when {
                            !spec.gitInit -> "no"
                            spec.commitMessage.isEmpty() -> "initialize repository, no commit"
                            else -> "initialize repository and commit \"${spec.commitMessage}\""
                        }
                    )
                    errors.forEach {
                        SpanText(
                            "⚠ $it"
                            , Modifier.color(Colors.Red)
                        )
                    }
                    status?.let { SpanText(it) }
                }
            }
        }

        Row(Modifier.gap(1.cssRem)) {
            Button(
                onClick = { step-- }
                , enabled = step > 0
            ) { Text("Back") }
            if (step < steps.lastIndex) {
                Button(onClick = { step++ }) { Text("Next") }
            } else {
                Button(
                    enabled = errors.isEmpty() && !isBusy
                    , onClick = {
                        scope.launch {
                            isBusy = true
                            status = "Generating…"

                            val bytes = window
                                .api
                                .tryPost(
                                    "generate"
                                    , body = bodyOf(
                                        Json.encodeToString(spec)
                                        , "application/json"
                                    )
                                ) {
                                    bodyAsBytes()
                                }

                            status = if (bytes == null) "Generation failed, check your selections." else {
                                download(bytes, "${spec.slug()}.zip")
                                "Downloaded ${spec.slug()}.zip"
                            }
                            isBusy = false
                        }
                    }
                ) { Text(if (isBusy) "Generating…" else "Generate & download ZIP") }
            }
        }
    }
}

private val card = Modifier
    .fillMaxWidth()
    .padding(1.5.cssRem)
    .gap(0.75.cssRem)
    .border(1.px, LineStyle.Solid, Colors.LightGray)
    .borderRadius(0.5.cssRem)

@Composable
private fun Field(
    label: String
    , value: String
    , onChange: (String) -> Unit
) {
    Column(Modifier.gap(0.25.cssRem)) {
        SpanText(
            label
            , Modifier.fontWeight(FontWeight.SemiBold)
        )
        Input(
            InputType.Text
            , value = value
            , onValueChange = onChange
            , modifier = Modifier.fillMaxWidth()
        )
    }
}

private fun download(
    bytes: ByteArray
    , fileName: String
) {
    val blob = Blob(arrayOf<dynamic>(bytes), BlobPropertyBag("application/zip"))
    val url = URL.createObjectURL(blob)
    val a = document.createElement("a") as HTMLAnchorElement
    a.href = url
    a.download = fileName
    a.click()
    URL.revokeObjectURL(url)
}
