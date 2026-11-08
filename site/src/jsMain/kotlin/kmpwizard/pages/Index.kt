package kmpwizard.pages

import androidx.compose.runtime.*
import com.varabyte.kobweb.browser.api
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
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.jetbrains.compose.web.attributes.InputType
import org.jetbrains.compose.web.css.LineStyle
import org.jetbrains.compose.web.css.cssRem
import org.jetbrains.compose.web.css.px
import org.jetbrains.compose.web.dom.H1
import org.jetbrains.compose.web.dom.H3
import org.jetbrains.compose.web.dom.Text
import org.w3c.dom.HTMLAnchorElement
import org.w3c.dom.url.URL
import org.w3c.files.Blob
import org.w3c.files.BlobPropertyBag
private val steps = listOf("Project", "Platforms", "Slices", "Libraries", "Review")

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
    var sampleTodo by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    // Drop libraries that became unavailable when platforms changed (e.g. Occurrent).
    val spec = ProjectSpec(
        name,
        pkg,
        targets,
        slices,
        libs.filter { Catalog.byId[it]?.isAvailable(targets) == true }
            .toSet(),
        sampleTodo = sampleTodo && Target.SERVER in targets,
    )
    val errors = spec.validate()

    Column(
        Modifier.fillMaxWidth().maxWidth(52.cssRem).margin(leftRight = autoLength).padding(2.cssRem).gap(1.cssRem)
    ) {
        H1 { Text("KMP Project Wizard") }

        Row(Modifier.gap(1.cssRem)) {
            steps.forEachIndexed { i, label ->
                SpanText(
                    "${i + 1}. $label",
                    Modifier.fontWeight(if (i == step) FontWeight.Bold else FontWeight.Normal)
                        .opacity(if (i == step) 1.0 else 0.55).cursor(Cursor.Pointer).onClick { step = i }
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
                        Checkbox(t in targets, onCheckedChange = { on -> targets = if (on) targets + t else targets - t }) {
                            SpanText(t.label)
                        }
                    }
                }
                2 -> {
                    H3 { Text("Vertical slices") }
                    SpanText(
                        "Each slice gets its own domain / data / presentation packages, a use case, a screen and a test.",
                        Modifier.opacity(0.7)
                    )
                    slices.forEach { s ->
                        Row(Modifier.gap(1.cssRem), verticalAlignment = Alignment.CenterVertically) {
                            SpanText(s)
                            Button(onClick = { slices = slices - s }) { Text("Remove") }
                        }
                    }
                    Row(Modifier.gap(0.5.cssRem), verticalAlignment = Alignment.CenterVertically) {
                        Input(InputType.Text, value = newSlice, onValueChange = { newSlice = it.lowercase() }, modifier = Modifier.flexGrow(1), placeholder = "e.g. orders")
                        Button(onClick = {
                            if (newSlice.isNotBlank() && newSlice !in slices) { slices = slices + newSlice.trim(); newSlice = "" }
                        }) { Text("Add slice") }
                    }
                    H3 { Text("Sample app") }
                    val serverOn = Target.SERVER in targets
                    Checkbox(
                        checked = serverOn && sampleTodo,
                        onCheckedChange = { on ->
                            sampleTodo = on
                            // Pre-select the libraries the sample uses; they stay selected if it's turned off again.
                            if (on) libs = libs + TODO_LIBRARIES
                        },
                        enabled = serverOn,
                    ) {
                        SpanText(
                            "Todo app: a '$TODO_SLICE' slice whose screens call the Ktor server, which stores todos in MongoDB" +
                                if (serverOn) "" else " (select the Server platform first)"
                        )
                    }
                }
                3 -> Catalog.libraries.groupBy { it.category }.forEach { (cat, items) ->
                    H3 { Text(cat) }
                    items.forEach { lib ->
                        val ok = lib.isAvailable(targets)
                        // The Todo sample needs these, so they can't be turned off while it's selected.
                        val requiredByTodo = spec.sampleTodo && lib.id in TODO_LIBRARIES
                        Checkbox(
                            checked = ok && (lib.id in libs || requiredByTodo),
                            onCheckedChange = { on -> libs = if (on) libs + lib.id else libs - lib.id },
                            enabled = ok && !requiredByTodo,
                        ) {
                            SpanText("${lib.name}: ${lib.description}" + if (requiredByTodo) " (used by the Todo sample)" else "")
                        }
                    }
                }
                else -> {
                    H3 { Text("Review") }
                    SpanText("${spec.name} (${spec.packageName})")
                    SpanText("Platforms: ${spec.targets.joinToString { it.label }.ifEmpty { "none" }}")
                    SpanText("Slices: ${spec.slices.joinToString().ifEmpty { "none" }}")
                    SpanText("Todo sample (MongoDB): ${if (spec.sampleTodo) "yes" else "no"}")
                    SpanText("Libraries: ${spec.libraries.joinToString().ifEmpty { "none" }}")
                    errors.forEach { SpanText("⚠ $it", Modifier.color(Colors.Red)) }
                    status?.let { SpanText(it) }
                }
            }
        }

        Row(Modifier.gap(1.cssRem)) {
            Button(onClick = { step-- }, enabled = step > 0) { Text("Back") }
            if (step < steps.lastIndex) {
                Button(onClick = { step++ }) { Text("Next") }
            } else {
                Button(
                    enabled = errors.isEmpty() && !busy,
                    onClick = {
                        scope.launch {
                            busy = true; status = "Generating…"
                            val bytes = window.api.tryPostBytes("generate", body = Json.encodeToString(spec).encodeToByteArray())
                            status = if (bytes == null) "Generation failed, check your selections." else {
                                download(bytes, "${spec.slug()}.zip"); "Downloaded ${spec.slug()}.zip"
                            }
                            busy = false
                        }
                    },
                ) { Text(if (busy) "Generating…" else "Generate & download ZIP") }
            }
        }
    }
}

private val card = Modifier.fillMaxWidth().padding(1.5.cssRem).gap(0.75.cssRem)
    .border(1.px, LineStyle.Solid, Colors.LightGray).borderRadius(0.5.cssRem)

@Composable
private fun Field(label: String, value: String, onChange: (String) -> Unit) {
    Column(Modifier.gap(0.25.cssRem)) {
        SpanText(label, Modifier.fontWeight(FontWeight.SemiBold))
        Input(InputType.Text, value = value, onValueChange = onChange, modifier = Modifier.fillMaxWidth())
    }
}

private fun download(bytes: ByteArray, fileName: String) {
    val blob = Blob(arrayOf<dynamic>(bytes), BlobPropertyBag("application/zip"))
    val url = URL.createObjectURL(blob)
    val a = document.createElement("a") as HTMLAnchorElement
    a.href = url
    a.download = fileName
    a.click()
    URL.revokeObjectURL(url)
}
