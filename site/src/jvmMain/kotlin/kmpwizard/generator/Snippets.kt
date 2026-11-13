package kmpwizard.generator

// ---------- Multi-line code snippets spliced into templates ----------

/**
 * Indents every line of this snippet after the first by [indent], the indentation of the template line it is
 * spliced into. Inside a `trimIndent()` template, count the template's own indentation too.
 */
fun String.indentContinuation(
    indent: Int
) = lines()
    .joinToString("\n" + " ".repeat(indent))

/**
 * A `fold(...)` call over a use case result, one argument per line: Arrow's `Either` (failure first) when
 * [hasArrow], otherwise `kotlin.Result` (named arguments). [onFailure] and [onSuccess] are lambda bodies;
 * a body of several lines (statements separated by `\n`) becomes a block.
 */
fun foldCall(
    hasArrow: Boolean
    , onFailure: String
    , onSuccess: String
) = if (hasArrow) "fold(\n    ${lambda(onFailure)}\n    , ${lambda(onSuccess)}\n)"
else "fold(\n    onSuccess = ${lambda(onSuccess)}\n    , onFailure = ${lambda(onFailure)}\n)"

/** `{ body }` for a one-line body, otherwise a block whose statements sit one level in from a `fold` argument. */
private fun lambda(
    body: String
) = if ('\n' !in body) "{ $body }"
else body
    .lines()
    .joinToString(separator = "\n", prefix = "{\n", postfix = "\n    }") {
        "        $it"
    }
