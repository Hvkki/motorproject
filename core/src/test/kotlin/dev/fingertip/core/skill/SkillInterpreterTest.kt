package dev.fingertip.core.skill

import dev.fingertip.core.privacy.Redactor
import dev.fingertip.core.screen.Role
import dev.fingertip.core.screen.ScreenSerializer
import dev.fingertip.core.screen.Selector
import dev.fingertip.core.testing.FakeDevice
import dev.fingertip.core.testing.Nodes
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SkillInterpreterTest {

    private fun whatsapp() = FakeDevice.build {
        screen(
            "home", "com.android.launcher", title = "Home",
            root = Nodes.container(Nodes.button("Phone"), Nodes.button("Messages")),
        )
        screen(
            "chats", "com.whatsapp", title = "Chats",
            root = Nodes.container(
                Nodes.list(Nodes.listItem("Mom"), Nodes.listItem("Ali"), label = "Conversations"),
            ),
        )
        screen(
            "chat_mom", "com.whatsapp", title = "Mom",
            root = Nodes.container(
                Nodes.list(
                    Nodes.listItem("Hi honey"),
                    Nodes.listItem("Are you coming for dinner?"),
                    label = "Messages",
                ),
            ),
        )
        app("com.whatsapp", "chats")
        // A real tap is followed by a screen transition that takes time.
        onTap("chats", "Mom", goTo = "chat_mom", delayMs = 600)
        startAt("home")
    }

    private val readLastMessage = Skill(
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

    @Test
    fun `replays a multi-step skill end to end`() {
        val device = whatsapp()
        val spoken = mutableListOf<String>()

        val result = SkillInterpreter(device, onSpeak = spoken::add).execute(readLastMessage)

        val success = assertIs<SkillResult.Success>(result, "expected success but got $result")
        assertEquals("Are you coming for dinner?", success.captures["message"])
        assertEquals(listOf("Last message: Are you coming for dinner?"), success.spoken)
        // Speech is emitted through the callback too, so TTS can begin mid-skill.
        assertEquals(success.spoken, spoken)
        assertEquals(listOf("com.whatsapp"), device.launchLog)
        assertEquals(listOf("Mom"), device.tapLog)
        assertEquals("chat_mom", device.currentScreen)
    }

    @Test
    fun `waits through a delayed screen transition instead of failing`() {
        val device = whatsapp()

        val result = SkillInterpreter(device).execute(readLastMessage)

        assertIs<SkillResult.Success>(result)
        // Virtual time advanced past the 600ms transition without a real sleep.
        assertTrue(result.elapsedMs >= 600, "expected the clock to advance, was ${result.elapsedMs}ms")
    }

    @Test
    fun `reports a missing element as an escalation-ready failure`() {
        val device = whatsapp()
        val skill = Skill(
            id = "whatsapp.broken",
            title = "Open archived chats",
            app = "com.whatsapp",
            steps = listOf(
                Launch("com.whatsapp"),
                Tap(Selector(text = "Archived")),
            ),
        )

        val result = SkillInterpreter(device).execute(skill)

        val failure = assertIs<SkillResult.Failure>(result)
        assertEquals(FailureReason.ELEMENT_NOT_FOUND, failure.reason)
        assertEquals(1, failure.stepIndex)
        // The captured screen is what a repair agent needs to regenerate the skill.
        assertNotNull(failure.screen)
        assertContains(failure.detail, "Archived")
    }

    @Test
    fun `failure message is phrased for a person, not a debugger`() {
        val device = whatsapp()
        val skill = Skill(
            id = "whatsapp.broken",
            title = "Open archived chats",
            app = "com.whatsapp",
            steps = listOf(Launch("com.whatsapp"), Tap(Selector(text = "Archived"))),
        )

        val failure = assertIs<SkillResult.Failure>(SkillInterpreter(device).execute(skill))

        assertContains(failure.message, "Open archived chats")
        // No selector syntax, stack traces or handles in what the user hears.
        listOf("Selector", "handle", "null", "Exception").forEach {
            assertTrue(it !in failure.message, "spoken message leaked '$it': ${failure.message}")
        }
    }

    @Test
    fun `the screen attached to a failure is redacted`() {
        val device = FakeDevice.build {
            screen(
                "login", "com.example.bank", title = "Sign in",
                root = Nodes.container(
                    Nodes.editText(hint = "Password", value = "hunter2", isPassword = true),
                    Nodes.text("Card 4111 1111 1111 1111"),
                ),
            )
            app("com.example.bank", "login")
        }
        val skill = Skill(
            id = "bank.balance",
            title = "Read my balance",
            app = "com.example.bank",
            steps = listOf(Launch("com.example.bank"), Tap(Selector(text = "Accounts"))),
        )

        val failure = assertIs<SkillResult.Failure>(SkillInterpreter(device).execute(skill))
        val screen = assertNotNull(failure.screen)
        val serialized = ScreenSerializer().serialize(screen)

        assertTrue("hunter2" !in serialized, "password leaked into failure payload: $serialized")
        assertTrue("4111" !in serialized, "card number leaked into failure payload: $serialized")
        assertContains(serialized, "[redacted]")
    }

    @Test
    fun `captures the raw value on device but redacts it at egress`() {
        // The whole privacy thesis in one test: the user hears their own code,
        // the network never does.
        val device = FakeDevice.build {
            screen(
                "sms", "com.android.messaging", title = "Messages",
                root = Nodes.container(Nodes.listItem("Your verification code is 483920")),
            )
            app("com.android.messaging", "sms")
        }
        val skill = Skill(
            id = "sms.read_code",
            title = "Read my verification code",
            app = "com.android.messaging",
            steps = listOf(
                Launch("com.android.messaging"),
                Capture(Selector(role = Role.LIST_ITEM, index = -1), name = "code"),
                Speak("{code}"),
            ),
        )

        val success = assertIs<SkillResult.Success>(SkillInterpreter(device).execute(skill))

        // Spoken to its owner: raw.
        assertContains(success.captures.getValue("code"), "483920")
        assertContains(success.spoken.single(), "483920")

        // Sent off the device: masked.
        val egress = ScreenSerializer().serialize(Redactor().redact(device.snapshot()))
        assertTrue("483920" !in egress, "one-time code leaked at egress: $egress")
    }

    @Test
    fun `rejects a template referencing a capture that never happened`() {
        val device = whatsapp()
        val skill = Skill(
            id = "broken.template",
            title = "Broken",
            steps = listOf(Speak("Your balance is {balance}")),
        )

        val failure = assertIs<SkillResult.Failure>(SkillInterpreter(device).execute(skill))

        assertEquals(FailureReason.MISSING_CAPTURE, failure.reason)
        assertContains(failure.detail, "balance")
    }

    @Test
    fun `scrolls until the target appears`() {
        val device = FakeDevice.build {
            screen(
                "inbox1", "com.mail", title = "Inbox",
                root = Nodes.container(
                    Nodes.list(Nodes.listItem("Bill"), Nodes.listItem("Newsletter"), label = "Messages"),
                ),
            )
            screen(
                "inbox2", "com.mail", title = "Inbox",
                root = Nodes.container(
                    Nodes.list(Nodes.listItem("Older thread"), Nodes.listItem("Bank statement"), label = "Messages"),
                ),
            )
            onScroll("inbox1", "Messages", goTo = "inbox2")
            startAt("inbox1")
        }
        val skill = Skill(
            id = "mail.open_statement",
            title = "Open my bank statement",
            steps = listOf(
                ScrollUntil(
                    container = Selector(desc = "Messages"),
                    target = Selector(text = "Bank statement"),
                ),
                Tap(Selector(text = "Bank statement")),
            ),
        )

        val result = SkillInterpreter(device).execute(skill)

        assertIs<SkillResult.Success>(result)
        assertEquals(1, device.scrollLog.size, "should stop scrolling once found: ${device.scrollLog}")
        assertEquals(listOf("Bank statement"), device.tapLog)
    }

    @Test
    fun `gives up when the list runs out before the target appears`() {
        val device = FakeDevice.build {
            screen(
                "inbox", "com.mail", title = "Inbox",
                root = Nodes.container(Nodes.list(Nodes.listItem("Bill"), label = "Messages")),
            )
        }
        val skill = Skill(
            id = "mail.missing",
            title = "Open my bank statement",
            steps = listOf(
                ScrollUntil(
                    container = Selector(desc = "Messages"),
                    target = Selector(text = "Nothing here"),
                ),
            ),
        )

        val failure = assertIs<SkillResult.Failure>(SkillInterpreter(device).execute(skill))

        assertEquals(FailureReason.ELEMENT_NOT_FOUND, failure.reason)
    }

    @Test
    fun `types into a field and reflects the value back`() {
        val device = FakeDevice.build {
            screen(
                "search", "com.mail", title = "Search",
                root = Nodes.container(Nodes.editText(hint = "Search mail", id = "query")),
            )
        }
        val skill = Skill(
            id = "mail.search",
            title = "Search my mail",
            steps = listOf(
                TypeText(Selector(viewId = "query"), "bank statement"),
                Capture(Selector(viewId = "query"), name = "typed", field = Field.TEXT),
                Speak("Searching for {typed}"),
            ),
        )

        val success = assertIs<SkillResult.Success>(SkillInterpreter(device).execute(skill))

        assertEquals(listOf("Search mail" to "bank statement"), device.typeLog)
        assertEquals("bank statement", success.captures["typed"])
        assertEquals("Searching for bank statement", success.spoken.single())
    }

    @Test
    fun `an assertion stops the skill rather than tapping blindly`() {
        val device = whatsapp()
        val skill = Skill(
            id = "whatsapp.guarded",
            title = "Send a message",
            app = "com.whatsapp",
            steps = listOf(
                Launch("com.whatsapp"),
                // We expect to be in a chat; we are actually on the chat list.
                Assert(Selector(desc = "Messages")),
                Tap(Selector(role = Role.LIST_ITEM, index = 0)),
            ),
        )

        val failure = assertIs<SkillResult.Failure>(SkillInterpreter(device).execute(skill))

        assertEquals(FailureReason.ASSERTION_FAILED, failure.reason)
        assertTrue(device.tapLog.isEmpty(), "must not act after a failed assertion")
    }

    @Test
    fun `enforces the overall time budget`() {
        val device = whatsapp()
        val skill = Skill(
            id = "slow.skill",
            title = "Something slow",
            steps = listOf(Sleep(5_000), Sleep(5_000), Speak("done")),
        )

        val failure = assertIs<SkillResult.Failure>(
            SkillInterpreter(device, config = SkillInterpreter.Config(totalBudgetMs = 1_000)).execute(skill),
        )

        assertEquals(FailureReason.BUDGET_EXCEEDED, failure.reason)
    }

    @Test
    fun `reports a launch failure for an app that is not installed`() {
        val device = whatsapp()
        val skill = Skill(
            id = "missing.app",
            title = "Open my bank",
            steps = listOf(Launch("com.example.notinstalled")),
        )

        val failure = assertIs<SkillResult.Failure>(SkillInterpreter(device).execute(skill))

        assertEquals(FailureReason.APP_LAUNCH_FAILED, failure.reason)
    }
}
