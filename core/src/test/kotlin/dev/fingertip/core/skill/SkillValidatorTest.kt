package dev.fingertip.core.skill

import dev.fingertip.core.screen.Role
import dev.fingertip.core.screen.Selector
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * These checks are the merge gate for machine-generated skills. Each test
 * corresponds to a mistake an agent authoring a skill actually tends to make.
 */
class SkillValidatorTest {

    private fun problems(skill: Skill) = SkillValidator.validate(skill)

    private fun errors(skill: Skill) = problems(skill).filter { it.severity == SkillValidator.Severity.ERROR }

    private fun warnings(skill: Skill) = problems(skill).filter { it.severity == SkillValidator.Severity.WARNING }

    @Test
    fun `a well formed skill passes cleanly`() {
        val skill = Skill(
            id = "whatsapp.read_last_message",
            title = "Read my last WhatsApp message",
            app = "com.whatsapp",
            utterances = listOf("read my last whatsapp message"),
            steps = listOf(
                Launch("com.whatsapp"),
                WaitFor(Selector(desc = "Conversations")),
                Tap(Selector(role = Role.LIST_ITEM, index = 0)),
                WaitFor(Selector(desc = "Messages")),
                Capture(Selector(role = Role.LIST_ITEM, index = -1), name = "message"),
                Speak("Last message: {message}"),
            ),
        )

        assertEquals(emptyList(), problems(skill), "expected no problems")
        assertTrue(SkillValidator.isValid(skill))
    }

    @Test
    fun `speaking a variable before capturing it is an error`() {
        val skill = Skill(
            id = "bad.order",
            title = "Read my balance",
            steps = listOf(
                Speak("Your balance is {balance}"),
                Capture(Selector(role = Role.TEXT), name = "balance"),
            ),
        )

        val errors = errors(skill)

        assertEquals(1, errors.size, errors.toString())
        assertTrue("balance" in errors.single().message)
        assertFalse(SkillValidator.isValid(skill))
    }

    @Test
    fun `typing an uncaptured variable is an error`() {
        val skill = Skill(
            id = "bad.type",
            title = "Search",
            steps = listOf(TypeText(Selector(editable = true), "{query}")),
        )

        assertEquals(1, errors(skill).size)
    }

    @Test
    fun `tapping immediately after launch is flagged as flaky`() {
        val skill = Skill(
            id = "flaky.tap",
            title = "Open settings",
            app = "com.android.settings",
            utterances = listOf("open settings"),
            steps = listOf(Launch("com.android.settings"), Tap(Selector(text = "Wi-Fi"))),
        )

        val warnings = warnings(skill)

        assertTrue(warnings.any { "waitFor" in it.message }, warnings.toString())
    }

    @Test
    fun `launching a different app than declared is flagged`() {
        val skill = Skill(
            id = "mismatch",
            title = "Open chats",
            app = "com.whatsapp",
            utterances = listOf("open chats"),
            steps = listOf(Launch("com.telegram"), WaitFor(Selector(desc = "Chats"))),
        )

        assertTrue(warnings(skill).any { "com.telegram" in it.message })
    }

    @Test
    fun `capturing without ever speaking is flagged`() {
        // Silent success is indistinguishable from failure to a blind user.
        val skill = Skill(
            id = "silent",
            title = "Read balance",
            utterances = listOf("read balance"),
            steps = listOf(Capture(Selector(role = Role.TEXT), name = "balance")),
        )

        assertTrue(warnings(skill).any { "no feedback" in it.message })
    }

    @Test
    fun `long fixed sleeps are discouraged`() {
        val skill = Skill(
            id = "sleepy",
            title = "Slow thing",
            utterances = listOf("slow thing"),
            steps = listOf(Sleep(10_000), Speak("done")),
        )

        assertTrue(warnings(skill).any { "waitFor" in it.message })
    }

    @Test
    fun `missing utterances is a warning not an error`() {
        val skill = Skill(
            id = "no.utterances",
            title = "Something",
            steps = listOf(Speak("hello")),
        )

        assertTrue(SkillValidator.isValid(skill))
        assertTrue(warnings(skill).any { "utterances" in it.message })
    }

    @Test
    fun `declaring an app without launching it is flagged`() {
        val skill = Skill(
            id = "assumes.foreground",
            title = "Read chat",
            app = "com.whatsapp",
            utterances = listOf("read chat"),
            steps = listOf(Capture(Selector(role = Role.TEXT), name = "m"), Speak("{m}")),
        )

        assertTrue(warnings(skill).any { "never launches" in it.message })
    }

    @Test
    fun `problem formatting includes the step number`() {
        val skill = Skill(
            id = "bad.order",
            title = "Read balance",
            steps = listOf(Speak("{missing}")),
        )

        assertTrue(errors(skill).single().toString().contains("step 0"))
    }
}
