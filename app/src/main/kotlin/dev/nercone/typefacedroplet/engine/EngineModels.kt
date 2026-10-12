package dev.nercone.typefacedroplet.engine

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

// The data exchanged with the engine. Every type mirrors a type of the `droplet` crate and
// its JSON form exactly; see crates/droplet/src/config.rs and engine.rs.

@Serializable
data class FontSource(val path: String, val offset: Long = 0, val length: Long? = null)

@Serializable
data class FontEntry(val id: String, val source: FontSource, val index: Int = 0, val rename: String? = null)

@Serializable
enum class FaceStyle {
    @SerialName("normal") NORMAL,
    @SerialName("italic") ITALIC,
}

@Serializable
data class Face(val font: String, val style: FaceStyle? = null, val weight: Int? = null, val axes: Map<String, Float> = emptyMap())

@Serializable
data class Role(val name: String, val faces: List<Face>, val axes: Map<String, Float> = emptyMap())

/** What a rule matches. Serialized as `{"family": pattern}`, `{"file": pattern}` or `{"language": pattern}`. */
@Serializable(with = Matcher.Serializer::class)
sealed interface Matcher {
    val pattern: String

    data class Family(override val pattern: String) : Matcher
    data class File(override val pattern: String) : Matcher
    data class Language(override val pattern: String) : Matcher

    val key: String
        get() = when (this) {
            is Family -> FAMILY
            is File -> FILE
            is Language -> LANGUAGE
        }

    companion object {
        const val FAMILY = "family"
        const val FILE = "file"
        const val LANGUAGE = "language"

        fun of(key: String, pattern: String): Matcher = when (key) {
            FAMILY -> Family(pattern)
            FILE -> File(pattern)
            LANGUAGE -> Language(pattern)
            else -> throw SerializationException("unknown matcher $key")
        }
    }

    object Serializer : KSerializer<Matcher> {
        override val descriptor: SerialDescriptor = buildClassSerialDescriptor("Matcher")

        override fun serialize(encoder: Encoder, value: Matcher) {
            (encoder as JsonEncoder).encodeJsonElement(buildJsonObject { put(value.key, JsonPrimitive(value.pattern)) })
        }

        override fun deserialize(decoder: Decoder): Matcher {
            val entry = (decoder as JsonDecoder).decodeJsonElement().jsonObject.entries.singleOrNull()
                ?: throw SerializationException("a matcher has exactly one key")
            return of(entry.key, entry.value.jsonPrimitive.content)
        }
    }
}

@Serializable
data class FallbackEntry(val role: String, val fallbackFor: String? = null)

/** What a rule does: replace with a role, or prepend a fallback family. */
sealed interface Action {
    data class Replace(val role: String) : Action
    data class Prepend(val entries: List<FallbackEntry>) : Action

    /** The roles the action uses. */
    val roles: List<String>
        get() = when (this) {
            is Replace -> listOf(role)
            is Prepend -> entries.map { it.role }
        }

    fun renameRole(from: String, to: String): Action = when (this) {
        is Replace -> if (role == from) Replace(to) else this
        is Prepend -> Prepend(entries.map { if (it.role == from) it.copy(role = to) else it })
    }

    /** The action without the role `name`, or `null` if nothing would be left of it. */
    fun withoutRole(name: String): Action? = when (this) {
        is Replace -> takeIf { role != name }
        is Prepend -> entries.filter { it.role != name }.takeIf { it.isNotEmpty() }?.let { Prepend(it) }
    }
}

/** A rule. Serialized as `{"match": {...}, "replace": role}` or `{"match": {...}, "prepend": [...]}`. */
@Serializable(with = Rule.Serializer::class)
data class Rule(val match: Matcher, val action: Action) {
    object Serializer : KSerializer<Rule> {
        override val descriptor: SerialDescriptor = buildClassSerialDescriptor("Rule")
        val entries = ListSerializer(FallbackEntry.serializer())

        override fun serialize(encoder: Encoder, value: Rule) {
            val json = (encoder as JsonEncoder).json
            encoder.encodeJsonElement(buildJsonObject {
                put("match", json.encodeToJsonElement(Matcher.Serializer, value.match))
                when (val action = value.action) {
                    is Action.Replace -> put("replace", JsonPrimitive(action.role))
                    is Action.Prepend -> put("prepend", json.encodeToJsonElement(entries, action.entries))
                }
            })
        }

        override fun deserialize(decoder: Decoder): Rule {
            val json = (decoder as JsonDecoder).json
            val element = decoder.decodeJsonElement().jsonObject
            val match = json.decodeFromJsonElement(Matcher.Serializer, element["match"] ?: throw SerializationException("a rule needs match"))
            val action = when {
                element["replace"] != null -> Action.Replace(element.getValue("replace").jsonPrimitive.content)
                element["prepend"] != null -> Action.Prepend(json.decodeFromJsonElement(entries, element.getValue("prepend")))
                else -> throw SerializationException("a rule needs replace or prepend")
            }
            return Rule(match, action)
        }
    }
}

@Serializable
data class Config(
    val version: Int = VERSION,
    val fonts: List<FontEntry> = emptyList(),
    val roles: List<Role> = emptyList(),
    val rules: List<Rule> = emptyList(),
    val components: List<String> = emptyList(),
    val restart: List<String> = emptyList(),
    val guardSeconds: Int = 60,
    val allowUnknownVersions: Boolean = false,
) {
    fun role(name: String): Role? = roles.firstOrNull { it.name == name }

    fun font(id: String): FontEntry? = fonts.firstOrNull { it.id == id }

    /** The rules whose action uses the role `name`. */
    fun rulesUsing(name: String): List<Rule> = rules.filter { name in it.action.roles }

    /** Replaces the role `name` with `role`, renaming it in every rule; adds `role` if there is no role `name`. */
    fun replaceRole(name: String, role: Role): Config {
        if (role(name) == null) return copy(roles = roles + role)
        return copy(
            roles = roles.map { if (it.name == name) role else it },
            rules = rules.map { rule -> rule.copy(action = rule.action.renameRole(name, role.name)) },
        )
    }

    /** Removes the role `name` and every use of it; a rule left with nothing to do is removed. */
    fun deleteRole(name: String): Config = copy(
        roles = roles.filter { it.name != name },
        rules = rules.mapNotNull { rule -> rule.action.withoutRole(name)?.let { rule.copy(action = it) } },
    )

    /** A role name that no role has yet, based on `base`. */
    fun uniqueRoleName(base: String): String {
        val names = roles.map { it.name }.toSet()
        return generateSequence(1) { it + 1 }.map { if (it == 1) base else "$base ($it)" }.first { it !in names }
    }

    /** The names of the roles some rule uses. */
    fun usedRoles(): Set<String> = rules.flatMapTo(mutableSetOf()) { it.action.roles }

    companion object {
        const val VERSION = 1
        const val HOME = "@home"
        const val BACKGROUND = "@background"
        const val MAX_NAME_LENGTH = 63
        const val MAX_GUARD_SECONDS = 3600
        val PACKAGE = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")
        val COMPONENT = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+/[A-Za-z0-9_.$]+")

        fun isRestartTarget(target: String): Boolean = target == HOME || target == BACKGROUND || PACKAGE.matches(target)

        fun isComponent(component: String): Boolean = COMPONENT.matches(component)
    }
}

/** The fonts of the standard roles, for the recommended configuration (`droplet preset`). */
@Serializable
data class Preset(
    val sans: FontEntry? = null,
    val sansItalic: FontEntry? = null,
    val serif: FontEntry? = null,
    val serifItalic: FontEntry? = null,
    val mono: FontEntry? = null,
    val monoItalic: FontEntry? = null,
)

@Serializable
data class AxisInfo(val tag: String, val minimum: Float, val default: Float, val maximum: Float, val hidden: Boolean)

@Serializable
data class FaceInfo(
    val index: Int,
    val family: String? = null,
    val subfamily: String? = null,
    val fullName: String? = null,
    val postscriptName: String? = null,
    val weight: Int,
    val italic: Boolean,
    val outline: String,
    val axes: List<AxisInfo>,
    val instances: Int,
) {
    val weightAxis: AxisInfo? get() = axes.firstOrNull { it.tag == "wght" }
}

@Serializable
data class FontInfo(val format: String, val size: Long, val faces: List<FaceInfo>)

@Serializable
data class HashResult(val sha256: String)

@Serializable
data class FamilyInfo(
    val kind: String,
    val element: String,
    val name: String? = null,
    val languages: List<String> = emptyList(),
    val variant: String? = null,
    val files: List<String> = emptyList(),
    val to: String? = null,
)

@Serializable
data class ComponentRecord(val user: Int, val component: String, val state: String)

/** The guard state. Serialized with a `state` discriminator. */
@Serializable(with = GuardState.Serializer::class)
sealed interface GuardState {
    data object Idle : GuardState
    data class Watching(val guard: Int, val until: Long) : GuardState
    data object Passed : GuardState
    data class Tripped(val reason: String, val time: Long) : GuardState

    object Serializer : KSerializer<GuardState> {
        override val descriptor: SerialDescriptor = buildClassSerialDescriptor("GuardState")

        override fun serialize(encoder: Encoder, value: GuardState) {
            (encoder as JsonEncoder).encodeJsonElement(buildJsonObject {
                when (value) {
                    Idle -> put("state", JsonPrimitive("idle"))
                    Passed -> put("state", JsonPrimitive("passed"))
                    is Watching -> {
                        put("state", JsonPrimitive("watching"))
                        put("guard", JsonPrimitive(value.guard))
                        put("until", JsonPrimitive(value.until))
                    }
                    is Tripped -> {
                        put("state", JsonPrimitive("tripped"))
                        put("reason", JsonPrimitive(value.reason))
                        put("time", JsonPrimitive(value.time))
                    }
                }
            })
        }

        override fun deserialize(decoder: Decoder): GuardState {
            val element: JsonObject = (decoder as JsonDecoder).decodeJsonElement().jsonObject
            fun field(name: String): String = element[name]?.jsonPrimitive?.content ?: throw SerializationException("guard state needs $name")
            return when (field("state")) {
                "idle" -> Idle
                "passed" -> Passed
                "watching" -> Watching(field("guard").toInt(), field("until").toLong())
                "tripped" -> Tripped(field("reason"), field("time").toLong())
                else -> throw SerializationException("unknown guard state")
            }
        }
    }
}

/** A root manager that runs boot scripts before the Android framework starts. */
@Serializable
enum class RootManager {
    @SerialName("magisk") MAGISK,
    @SerialName("kernelSU") KERNEL_SU,
    @SerialName("aPatch") APATCH,
}

/** The boot scripts that apply the saved configuration before the framework starts. */
@Serializable
data class BootHook(val managers: List<RootManager> = emptyList(), val scripts: List<String> = emptyList()) {
    val installed: Boolean get() = scripts.isNotEmpty()
}

@Serializable
data class StatusReport(
    val api: Int? = null,
    val uid: Long,
    val applied: Boolean,
    val saved: Boolean = false,
    val generation: Long,
    val mounted: List<String>,
    val directories: List<String>,
    val served: List<String>,
    val guard: GuardState,
    val components: List<ComponentRecord>,
    val documents: List<String>,
    val hook: BootHook = BootHook(),
)

@Serializable
data class GenerationReport(val replaced: List<List<String>>, val inserted: List<JsonElement>, val files: List<String>)

@Serializable
data class InstalledFont(val id: String, val fileName: String, val path: String, val index: Int, val face: FaceInfo)

/** How the font service took the generated documents. */
@Serializable
enum class ReloadOutcome {
    /** Processes started from now on use the new fonts. */
    @SerialName("served") SERVED,

    /** The font service did not reload; a soft restart is needed. */
    @SerialName("ignored") IGNORED,

    /** No rule matched any family of the system. */
    @SerialName("unchanged") UNCHANGED,

    /** The framework had not started and reads the documents when it starts. */
    @SerialName("atStart") AT_START,
}

@Serializable
data class ApplyReport(
    val generation: Long,
    val documents: Map<String, GenerationReport>,
    val fonts: List<InstalledFont>,
    val reload: ReloadOutcome,
    val restarted: List<String>,
    val guard: Int? = null,
) {
    /** The number of families replaced in the document the framework reads. */
    val replaced: Int get() = documents.values.maxOfOrNull { it.replaced.size } ?: 0
}

@Serializable
data class RevertReport(val restarted: List<String>)

@Serializable
data class SoftRestartReport(val guard: Int? = null)

@Serializable
data class ValidationReport(val problems: List<String>)

@Serializable
data class VersionReport(val version: String, val configVersion: Int)

@Serializable
enum class LogLevel {
    @SerialName("info") INFO,
    @SerialName("warning") WARNING,
    @SerialName("error") ERROR,
}

@Serializable
data class LogLine(val time: Long, val level: LogLevel, val message: String)

@Serializable
data class EngineError(val kind: String, val message: String)

/** The envelope of every engine response. */
@Serializable
data class EngineResponse(val ok: Boolean, val result: JsonElement? = null, val error: EngineError? = null, val log: List<LogLine> = emptyList())

/** The JSON settings shared by every exchange with the engine. */
object EngineJson {
    val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
    }
}
