package kmpwizard.generator

/**
 * Source of an id type for an entity: a value class [idType] in [packageName] wrapping a Kotlin UUID version 7,
 * so ids sort by creation time. Used by every slice's item and by the Todo sample.
 */
fun uuidIdType(
    packageName: String
    , idType: String
    , entity: String
) = """
    @file:OptIn(ExperimentalUuidApi::class)

    package $packageName

    import kotlin.jvm.JvmInline
    import kotlin.uuid.ExperimentalUuidApi
    import kotlin.uuid.Uuid

    /**
     * Identity of a [$entity]: a UUID version 7. Its leading bits are a millisecond timestamp,
     * so ids sort by creation time (also in their string form).
     */
    @JvmInline
    value class $idType(
        val value: Uuid
    ) {
        override fun toString() = value.toString()

        companion object {
            /** A new, time-ordered id. */
            fun generate() = $idType(Uuid.generateV7())

            /** Parses the hex-and-dash form; throws IllegalArgumentException if [text] is not a UUID. */
            fun parse(
                text: String
            ) = $idType(Uuid.parse(text))

            /** Parses the hex-and-dash form; null if [text] is not a UUID. */
            fun parseOrNull(
                text: String
            ) = Uuid
                .parseOrNull(text)
                ?.let(::$idType)
        }
    }
""".trimIndent()
