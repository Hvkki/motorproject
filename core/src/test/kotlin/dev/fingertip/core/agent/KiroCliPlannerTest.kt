package dev.fingertip.core.agent

import dev.fingertip.core.privacy.Redactor
import dev.fingertip.core.screen.ScreenSnapshot
import dev.fingertip.core.testing.Nodes
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The Kiro CLI adapter, verified without a subprocess or credentials.
 *
 * The command line, the retry-on-malformed-output behaviour and the exit-code
 * handling are all checked through a fake [CommandRunner]. The one thing left
 * untested is the real process invocation, which is why it is isolated in
 * [ProcessCommandRunner] and kept as small as possible.
 */
class KiroCliPlannerTest {

    private class FakeRunner(private vararg val replies: CommandResult) : CommandRunner {
        val commands = mutableListOf<List<String>>()
        val stdins = mutableListOf<String?>()
        var calls = 0
            private set

        override fun run(command: List<String>, stdin: String?, timeoutMs: Long): CommandResult {
            commands += command
            stdins += stdin
            return replies.getOrElse(calls++) { replies.last() }
        }
    }

    private fun ok(stdout: String) = CommandResult(0, stdout, "")

    private fun request() = PlanRequest(
        goal = "search google for the weather",
        screen = Redactor().redact(ScreenSnapshot.of("com.example", Nodes.container(Nodes.button("Search")))),
        wire = "app=com.example\n[1] button \"Search\" (tap)\n",
        history = emptyList(),
        stepNumber = 1,
    )

    // --- command construction -------------------------------------------------

    @Test
    fun `invokes kiro-cli headlessly and never trusts tools`() {
        val runner = FakeRunner(ok("""{"decision":"done","summary":"ok"}"""))

        KiroCliPlanner(runner).next(request())

        val command = runner.commands.single()
        assertEquals("kiro-cli", command.first())
        assertContains(command, "chat")
        assertContains(command, "--no-interactive")
        // Without a trust flag, tool use requires approval and is therefore blocked
        // in non-interactive mode. Trusting everything would put a second,
        // unsupervised actor on the user's phone.
        assertTrue("--trust-all-tools" !in command, "must never trust all tools")
        assertTrue(command.none { it.startsWith("--trust") }, "no trust flags expected: $command")
    }

    @Test
    fun `never places credentials on the command line`() {
        // The key must reach kiro-cli through the inherited environment. On the
        // command line it would be visible in the process table and in any log
        // that echoes commands.
        val runner = FakeRunner(ok("""{"decision":"done","summary":"ok"}"""))

        KiroCliPlanner(runner).next(request())

        val flattened = runner.commands.single().joinToString(" ")
        listOf("KIRO_API_KEY", "ksk_", "--api-key", "--key").forEach {
            assertTrue(it !in flattened, "credential material appeared in the command: $flattened")
        }
    }

    @Test
    fun `passes the observation as the positional argument, not on stdin`() {
        // Verified against kiro-cli 2.14.2: piped stdin is not consumed as context.
        // It burns a turn trying to shell out to read it, so the prompt never
        // reaches the model.
        val runner = FakeRunner(ok("""{"decision":"done","summary":"ok"}"""))

        KiroCliPlanner(runner).next(request())

        assertEquals(null, runner.stdins.single(), "stdin is not read by kiro-cli")
        val prompt = runner.commands.single().last()
        assertContains(prompt, "search google for the weather")
        assertContains(prompt, "BEGIN UNTRUSTED SCREEN CONTENT")
        assertContains(prompt, "Do not use any tools")
    }

    @Test
    fun `retry correction travels in the argument too`() {
        val runner = FakeRunner(
            ok("no json at all"),
            ok("""{"decision":"done","summary":"ok"}"""),
        )

        KiroCliPlanner(runner).next(request())

        assertContains(runner.commands[1].last(), "previous reply could not be used")
    }

    @Test
    fun `passes through caller-supplied arguments such as effort`() {
        val runner = FakeRunner(ok("""{"decision":"done","summary":"ok"}"""))

        KiroCliPlanner(
            runner,
            KiroCliPlanner.Config(extraArgs = listOf("--effort", "low")),
        ).next(request())

        val command = runner.commands.single()
        assertContains(command, "--effort")
        assertContains(command, "low")
    }

    // --- decisions ------------------------------------------------------------

    @Test
    fun `returns the parsed decision`() {
        val runner = FakeRunner(
            ok("""{"decision":"act","why":"tap search","step":{"action":"tap","selector":{"text":"Search"}}}"""),
        )

        val decision = KiroCliPlanner(runner).next(request())

        val act = assertIs<PlanDecision.Act>(decision)
        assertEquals("tap search", act.why)
    }

    @Test
    fun `retries once with a specific correction after unusable output`() {
        val runner = FakeRunner(
            ok("I think you should tap the search button."),
            ok("""{"decision":"act","step":{"action":"tap","selector":{"text":"Search"}}}"""),
        )

        val decision = KiroCliPlanner(runner).next(request())

        assertIs<PlanDecision.Act>(decision)
        assertEquals(2, runner.calls)
        // Telling the model exactly what was wrong is far more effective than a
        // bare retry.
        val secondPrompt = runner.commands[1].last()
        assertContains(secondPrompt, "previous reply could not be used")
        assertContains(secondPrompt, "No JSON object")
    }

    @Test
    fun `gives up after exhausting retries`() {
        val runner = FakeRunner(ok("no json here"), ok("still no json"))

        val decision = KiroCliPlanner(runner).next(request())

        val giveUp = assertIs<PlanDecision.GiveUp>(decision)
        assertContains(giveUp.reason, "unusable")
        assertEquals(2, runner.calls)
    }

    @Test
    fun `does not retry when retries are disabled`() {
        val runner = FakeRunner(ok("no json"))

        KiroCliPlanner(runner, KiroCliPlanner.Config(parseRetries = 0)).next(request())

        assertEquals(1, runner.calls)
    }

    // --- failure modes --------------------------------------------------------

    @Test
    fun `explains a missing API key rather than a bare exit code`() {
        // Exit 1 is by far the most common first-run failure, so the message has to
        // point at the actual cause.
        val runner = FakeRunner(CommandResult(1, "", "not authenticated"))

        val giveUp = assertIs<PlanDecision.GiveUp>(KiroCliPlanner(runner).next(request()))

        assertContains(giveUp.reason, "KIRO_API_KEY")
        assertContains(giveUp.reason, "not authenticated")
    }

    @Test
    fun `explains a missing executable`() {
        val runner = FakeRunner(CommandResult(127, "", "kiro-cli: not found"))

        val giveUp = assertIs<PlanDecision.GiveUp>(KiroCliPlanner(runner).next(request()))

        assertContains(giveUp.reason, "not found on PATH")
    }

    @Test
    fun `explains an MCP startup failure`() {
        val runner = FakeRunner(CommandResult(3, "", ""))

        val giveUp = assertIs<PlanDecision.GiveUp>(KiroCliPlanner(runner).next(request()))

        assertContains(giveUp.reason, "MCP")
    }

    @Test
    fun `reports a timeout without retrying`() {
        // Retrying a hung call would consume the agent loop's whole budget.
        val runner = FakeRunner(CommandResult(-1, "", "", timedOut = true))

        val giveUp = assertIs<PlanDecision.GiveUp>(
            KiroCliPlanner(runner, KiroCliPlanner.Config(timeoutMs = 1_234)).next(request()),
        )

        assertContains(giveUp.reason, "timed out")
        assertContains(giveUp.reason, "1234")
        assertEquals(1, runner.calls)
    }

    @Test
    fun `a give_up decision flows back to the agent loop unchanged`() {
        val runner = FakeRunner(ok("""{"decision":"give_up","reason":"no search field on screen"}"""))

        val giveUp = assertIs<PlanDecision.GiveUp>(KiroCliPlanner(runner).next(request()))

        assertEquals("no search field on screen", giveUp.reason)
    }
}
