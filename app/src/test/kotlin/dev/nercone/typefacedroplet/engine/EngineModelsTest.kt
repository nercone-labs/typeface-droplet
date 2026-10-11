package dev.nercone.typefacedroplet.engine

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The JSON exchanged with the engine must have exactly the shape the engine reads and writes
 * (crates/droplet/src/config.rs, state.rs, engine.rs). The expected documents below are written
 * from that format, not produced by this code.
 */
class EngineModelsTest {
    private val json = EngineJson.json

    private fun parse(text: String): JsonElement = Json.parseToJsonElement(text)

    @Test
    fun rulesUseTheExternallyTaggedForm() {
        val replace = Rule(Matcher.Family("google-sans*"), Action.Replace("sans"))
        assertEquals(parse("""{"match":{"family":"google-sans*"},"replace":"sans"}"""), json.encodeToJsonElement(Rule.serializer(), replace))
        val prepend = Rule(Matcher.Language("ja"), Action.Prepend(listOf(FallbackEntry("sans"), FallbackEntry("serif", "serif"))))
        assertEquals(
            parse("""{"match":{"language":"ja"},"prepend":[{"role":"sans"},{"role":"serif","fallbackFor":"serif"}]}"""),
            json.encodeToJsonElement(Rule.serializer(), prepend),
        )
        assertEquals(Rule(Matcher.File("Roboto-*"), Action.Replace("sans")), json.decodeFromString(Rule.serializer(), """{"match":{"file":"Roboto-*"},"replace":"sans"}"""))
    }

    @Test
    fun configurationOmitsAbsentOptionalFields() {
        val config = Config(
            fonts = listOf(FontEntry("a".repeat(64), FontSource("/fonts/a.ttf"))),
            roles = listOf(Role("sans", listOf(Face("a".repeat(64))))),
        )
        val element = json.encodeToJsonElement(Config.serializer(), config).jsonObject
        assertEquals(parse("1"), element["version"])
        val font = element.getValue("fonts").toString()
        assertFalse("an absent rename is omitted, not null", font.contains("rename"))
        assertFalse("an absent length is omitted, not null", font.contains("length"))
        val face = element.getValue("roles").toString()
        assertFalse(face.contains("style"))
        assertFalse(face.contains("weight"))
        assertTrue(element.containsKey("guardSeconds"))
        assertTrue(element.containsKey("allowUnknownVersions"))
    }

    @Test
    fun configurationRoundTripsTheEngineFormat() {
        val text = """{"version":1,"fonts":[{"id":"${"b".repeat(64)}","source":{"path":"/a.apk","offset":4096,"length":100},"index":2,"rename":"Roboto"}],
            "roles":[{"name":"sans","faces":[{"font":"${"b".repeat(64)}","style":"italic","weight":700,"axes":{"opsz":14.0}}],"axes":{"GRAD":0.0}}],
            "rules":[{"match":{"family":"sans-serif"},"replace":"sans"}],"components":["a.b/.C"],"restart":["@home"],"guardSeconds":30,"allowUnknownVersions":true}"""
        val config = json.decodeFromString(Config.serializer(), text)
        assertEquals(FontSource("/a.apk", 4096, 100), config.fonts[0].source)
        assertEquals(FaceStyle.ITALIC, config.roles[0].faces[0].style)
        assertEquals(14.0f, config.roles[0].faces[0].axes.getValue("opsz"))
        assertEquals(30, config.guardSeconds)
        assertEquals(parse(text), json.encodeToJsonElement(Config.serializer(), config))
        assertEquals(setOf("sans"), config.usedRoles())
    }

    @Test
    fun guardStatesUseTheStateDiscriminator() {
        assertEquals(GuardState.Idle, json.decodeFromString(GuardState.serializer(), """{"state":"idle"}"""))
        assertEquals(GuardState.Passed, json.decodeFromString(GuardState.serializer(), """{"state":"passed"}"""))
        assertEquals(GuardState.Watching(12, 34), json.decodeFromString(GuardState.serializer(), """{"state":"watching","guard":12,"until":34}"""))
        val tripped = GuardState.Tripped("system_server died", 56)
        assertEquals(parse("""{"state":"tripped","reason":"system_server died","time":56}"""), json.encodeToJsonElement(GuardState.serializer(), tripped))
    }

    @Test
    fun responsesAreDecodedWithTheirLog() {
        val response = json.decodeFromString(
            EngineResponse.serializer(),
            """{"ok":false,"error":{"kind":"refused","message":"no"},"log":[{"time":1,"level":"warning","message":"careful"}]}""",
        )
        assertFalse(response.ok)
        assertEquals(EngineError("refused", "no"), response.error)
        assertEquals(LogLine(1, LogLevel.WARNING, "careful"), response.log.single())
        val status = """{"api":37,"uid":0,"applied":true,"generation":3,"mounted":["fontsXML"],"directories":["/system/fonts"],
            "served":["TypeFaceDroplet-x.ttf"],"guard":{"state":"passed"},"components":[{"user":0,"component":"a.b/.C","state":"default"}],"documents":["fontsXML"]}"""
        val report = json.decodeFromString(StatusReport.serializer(), status)
        assertTrue(report.applied)
        assertEquals(GuardState.Passed, report.guard)
        assertEquals(ComponentRecord(0, "a.b/.C", "default"), report.components.single())
    }
}
