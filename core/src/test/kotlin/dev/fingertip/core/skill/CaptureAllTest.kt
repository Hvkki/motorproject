package dev.fingertip.core.skill

import dev.fingertip.core.screen.Role
import dev.fingertip.core.screen.Selector
import dev.fingertip.core.testing.FakeDevice
import dev.fingertip.core.testing.Nodes
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Reading a whole list in one step.
 *
 * This is what separates "read my last message" from "total up what I spent on
 * groceries". Doing the latter with repeated single captures would cost one model
 * call per row, which is both slow and expensive on a long list.
 */
class CaptureAllTest {

    private fun transactions() = FakeDevice.build {
        screen(
            "transactions", "com.example.bank", title = "Transactions",
            root = Nodes.container(
                Nodes.list(
                    Nodes.listItem("12 Jul  TESCO GROCERIES  -42.15"),
                    Nodes.listItem("11 Jul  SHELL FUEL  -60.00"),
                    Nodes.listItem("09 Jul  ALDI GROCERIES  -18.40"),
                    label = "Transactions",
                ),
                Nodes.button("Export", enabled = false),
            ),
        )
    }

    @Test
    fun `reads every matching row into one value`() {
        val device = transactions()
        val skill = Skill(
            id = "bank.read_all",
            title = "Read my transactions",
            utterances = listOf("read my transactions"),
            steps = listOf(
                CaptureAll(Selector(role = Role.LIST_ITEM), name = "rows"),
                Speak("You have {rows_count} transactions."),
            ),
        )

        val success = assertIs<SkillResult.Success>(SkillInterpreter(device).execute(skill))

        val rows = success.captures.getValue("rows")
        assertContains(rows, "TESCO GROCERIES")
        assertContains(rows, "ALDI GROCERIES")
        assertEquals(3, rows.lines().size)
        // The count is published alongside, so a template can use it directly.
        assertEquals("3", success.captures["rows_count"])
        assertEquals("You have 3 transactions.", success.spoken.single())
    }

    @Test
    fun `an empty list is an answer, not a failure`() {
        // "You have no unread messages" is a correct outcome. Failing here would
        // send the agent off to retry something that is already true.
        val device = FakeDevice.build {
            screen(
                "inbox", "com.example.mail", title = "Inbox",
                root = Nodes.container(Nodes.text("No messages")),
            )
        }
        val skill = Skill(
            id = "mail.count",
            title = "Count my messages",
            utterances = listOf("count my messages"),
            steps = listOf(
                CaptureAll(Selector(role = Role.LIST_ITEM), name = "messages"),
                Speak("You have {messages_count} messages."),
            ),
        )

        val success = assertIs<SkillResult.Success>(SkillInterpreter(device).execute(skill))

        assertEquals("", success.captures["messages"])
        assertEquals("0", success.captures["messages_count"])
        assertEquals("You have 0 messages.", success.spoken.single())
    }

    @Test
    fun `respects the limit so a long list cannot flood a prompt`() {
        val device = FakeDevice.build {
            screen(
                "big", "com.example", title = "Big",
                root = Nodes.container(
                    Nodes.list(
                        *(1..200).map { Nodes.listItem("Row $it") }.toTypedArray(),
                        label = "Rows",
                    ),
                ),
            )
        }
        val skill = Skill(
            id = "big.read",
            title = "Read rows",
            utterances = listOf("read rows"),
            steps = listOf(
                CaptureAll(Selector(role = Role.LIST_ITEM), name = "rows", limit = 10),
                Speak("{rows_count}"),
            ),
        )

        val success = assertIs<SkillResult.Success>(SkillInterpreter(device).execute(skill))

        assertEquals("10", success.captures["rows_count"])
        assertEquals(10, success.captures.getValue("rows").lines().size)
    }

    @Test
    fun `skips elements the user could not perceive`() {
        val device = FakeDevice.build {
            screen(
                "mixed", "com.example", title = "Mixed",
                root = Nodes.container(
                    Nodes.listItem("Visible row"),
                    Nodes.listItem("Hidden row").copy(visible = false),
                    Nodes.listItem("Disabled row").copy(enabled = false),
                ),
            )
        }
        val skill = Skill(
            id = "mixed.read",
            title = "Read rows",
            utterances = listOf("read rows"),
            steps = listOf(CaptureAll(Selector(role = Role.LIST_ITEM), name = "rows"), Speak("{rows}")),
        )

        val success = assertIs<SkillResult.Success>(SkillInterpreter(device).execute(skill))

        assertEquals("Visible row", success.captures["rows"])
    }

    @Test
    fun `round trips through JSON`() {
        val skill = Skill(
            id = "bank.read_all",
            title = "Read my transactions",
            utterances = listOf("read my transactions"),
            steps = listOf(
                CaptureAll(Selector(role = Role.LIST_ITEM), name = "rows", limit = 20),
                Speak("{rows_count} rows"),
            ),
        )

        assertEquals(skill, SkillJson.decode(SkillJson.encode(skill)))
    }

    @Test
    fun `decodes the documented wire format`() {
        val json = """
            {
              "id": "bank.total_groceries",
              "title": "Total my groceries",
              "utterances": ["total up my groceries"],
              "steps": [
                { "action": "captureAll", "selector": { "role": "LIST_ITEM" }, "as": "rows", "limit": 100 },
                { "action": "speak", "template": "Found {rows_count} transactions." }
              ]
            }
        """.trimIndent()

        val skill = SkillJson.decode(json)

        val step = assertIs<CaptureAll>(skill.steps.first())
        assertEquals("rows", step.name)
        assertEquals(100, step.limit)
        assertTrue(SkillValidator.isValid(skill), SkillValidator.validate(skill).toString())
    }

    @Test
    fun `the validator accepts a count placeholder it never saw captured`() {
        // rows_count is published by the executor, not by an explicit capture, so
        // the validator has to know about it or it would reject valid skills.
        val skill = Skill(
            id = "x.y",
            title = "X",
            utterances = listOf("x"),
            steps = listOf(CaptureAll(Selector(role = Role.LIST_ITEM), name = "rows"), Speak("{rows_count}")),
        )

        assertEquals(
            emptyList(),
            SkillValidator.validate(skill).filter { it.severity == SkillValidator.Severity.ERROR },
        )
    }

    @Test
    fun `rejects a non-positive limit`() {
        val skill = Skill(
            id = "x.y",
            title = "X",
            utterances = listOf("x"),
            steps = listOf(CaptureAll(Selector(role = Role.LIST_ITEM), name = "rows", limit = 0), Speak("{rows}")),
        )

        assertTrue(SkillValidator.validate(skill).any { it.message.contains("limit must be positive") })
    }
}
