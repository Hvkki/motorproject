package dev.fingertip.core.skill

import dev.fingertip.core.screen.Role
import dev.fingertip.core.screen.Selector
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SkillLibraryTest {

    private fun skill(id: String, title: String, vararg utterances: String) = Skill(
        id = id,
        title = title,
        utterances = utterances.toList(),
        steps = listOf(Capture(Selector(role = Role.TEXT), name = "v"), Speak("{v}")),
    )

    private val library = SkillLibrary(
        listOf(
            skill(
                "whatsapp.read_last_message", "Read my last WhatsApp message",
                "read my last whatsapp message", "what did i get on whatsapp",
            ),
            skill("battery.level", "Check the battery level", "how much battery do i have", "battery level"),
            skill("bank.balance", "Read my account balance", "what is my balance", "read my balance"),
        ),
    )

    @Test
    fun `exact phrasing scores perfectly`() {
        val match = assertNotNull(library.match("read my last whatsapp message"))

        assertEquals("whatsapp.read_last_message", match.skill.id)
        assertEquals(1.0, match.score)
    }

    @Test
    fun `word order and filler words do not matter`() {
        val match = assertNotNull(library.match("hey could you please read my whatsapp last message"))

        assertEquals("whatsapp.read_last_message", match.skill.id)
    }

    @Test
    fun `matches on the title when no utterance fits`() {
        val match = assertNotNull(library.match("check battery level"))

        assertEquals("battery.level", match.skill.id)
    }

    @Test
    fun `handles simple plurals`() {
        val match = assertNotNull(library.match("read my last whatsapp messages"))

        assertEquals("whatsapp.read_last_message", match.skill.id)
    }

    @Test
    fun `returns nothing for an unrelated request`() {
        // Escalating to a reasoning agent is correct here. Guessing could send a
        // message or spend money, so a false positive is much worse than a miss.
        assertNull(library.match("book me a flight to Cairo"))
    }

    @Test
    fun `returns nothing for an empty request`() {
        assertNull(library.match("   "))
        assertNull(library.match("!!!"))
    }

    @Test
    fun `does not confuse two similar money skills`() {
        val match = assertNotNull(library.match("what is my balance"))

        assertEquals("bank.balance", match.skill.id)
    }

    @Test
    fun `rank offers alternatives for disambiguation`() {
        val ranked = library.rank("read my balance", minScore = 0.2)

        assertTrue(ranked.isNotEmpty())
        assertEquals("bank.balance", ranked.first().skill.id)
        // Scores must come back ordered so a "did you mean?" prompt is sensible.
        assertEquals(ranked.map { it.score }.sortedDescending(), ranked.map { it.score })
        // One entry per skill, not one per utterance.
        assertEquals(ranked.map { it.skill.id }.distinct().size, ranked.size)
    }

    @Test
    fun `lookup by id works`() {
        assertNotNull(library.byId("battery.level"))
        assertNull(library.byId("nope"))
    }

    @Test
    fun `duplicate ids are rejected`() {
        assertFailsWith<IllegalArgumentException> {
            SkillLibrary(listOf(skill("dup", "One", "one"), skill("dup", "Two", "two")))
        }
    }

    @Test
    fun `builds from json documents`() {
        val json = """
            {
              "id": "battery.level",
              "title": "Check the battery level",
              "utterances": ["battery level"],
              "steps": [
                { "action": "capture", "selector": { "role": "TEXT" }, "as": "level" },
                { "action": "speak", "template": "Battery {level}" }
              ]
            }
        """.trimIndent()

        val loaded = SkillLibrary.fromJson(listOf(json))

        assertEquals(1, loaded.skills.size)
        assertNotNull(loaded.match("battery level"))
    }
}
