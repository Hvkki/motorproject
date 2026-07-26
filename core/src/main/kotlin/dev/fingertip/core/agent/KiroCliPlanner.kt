package dev.fingertip.core.agent

import java.util.concurrent.TimeUnit

/** Outcome of running an external command. */
data class CommandResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    /** True when the command was killed for exceeding its timeout. */
    val timedOut: Boolean = false,
)

/**
 * Runs an external command. Injected so [KiroCliPlanner] is testable without a
 * subprocess, a network, or credentials.
 */
fun interface CommandRunner {
    fun run(command: List<String>, stdin: String?, timeoutMs: Long): CommandResult
}

/**
 * A [Planner] backed by Kiro CLI in headless mode.
 *
 * ## Where this runs
 *
 * **Not on the phone.** Kiro CLI installs on macOS, Windows and Linux only, and
 * `KIRO_API_KEY` authenticates that local process rather than a hosted endpoint a
 * device can call. So the topology is:
 *
 *     phone  ──HTTPS──▶  your backend  ──subprocess──▶  kiro-cli --no-interactive
 *
 * This class is the backend half. The phone side is a thin [Planner] that posts
 * the request to that backend.
 *
 * ## Credentials
 *
 * The key is read from the environment by `kiro-cli` itself and is never placed on
 * the command line, where it would be visible in the process table and in any log
 * that echoes commands. This class never reads, stores or prints it.
 *
 * Worth knowing before building a product on this: Kiro API keys are per-user and
 * draw on that user's subscription credits, so one key serving many users is not a
 * supported multi-tenant model. Either each user supplies their own, or this stays
 * a single-user deployment.
 *
 * ## Tools
 *
 * No tool-trust flags are passed. This planner wants a decision, not actions —
 * Fingertip performs the tapping through the accessibility API, and letting the
 * CLI act on its own would put a second, unsupervised actor in the loop, outside
 * the confirmation gates.
 */
class KiroCliPlanner(
    private val runner: CommandRunner = ProcessCommandRunner(),
    private val config: Config = Config(),
) : Planner {

    data class Config(
        /** Resolved on PATH by default. */
        val executable: String = "kiro-cli",
        /** Optional model or effort selection, e.g. listOf("--effort", "low"). */
        val extraArgs: List<String> = emptyList(),
        /**
         * Per-decision ceiling. The agent loop has its own overall budget; this
         * stops one hung call from consuming it.
         */
        val timeoutMs: Long = 60_000,
        /**
         * Retries after an unparseable reply, told exactly what was wrong.
         *
         * One retry is worth it because malformed output is usually a formatting
         * slip a specific correction fixes. More than that mostly burns credits.
         */
        val parseRetries: Int = 1,
    )

    override fun next(request: PlanRequest): PlanDecision {
        var correction: String? = null

        repeat(config.parseRetries + 1) { attempt ->
            val prompt = buildString {
                append(PlanCodec.buildPrompt(request))
                if (correction != null) {
                    appendLine()
                    appendLine("Your previous reply could not be used: $correction")
                    appendLine("Reply with ONE valid JSON object and nothing else.")
                }
            }

            val result = runner.run(command(), prompt, config.timeoutMs)

            if (result.timedOut) {
                return PlanDecision.GiveUp("Kiro CLI timed out after ${config.timeoutMs}ms.")
            }
            if (result.exitCode != 0) {
                return PlanDecision.GiveUp(describeExit(result))
            }

            when (val parsed = PlanCodec.parse(result.stdout)) {
                is PlanCodec.ParseResult.Ok -> return parsed.decision
                is PlanCodec.ParseResult.Invalid -> {
                    correction = parsed.reason
                    if (attempt == config.parseRetries) {
                        return PlanDecision.GiveUp("Kiro CLI reply was unusable: ${parsed.reason}")
                    }
                }
            }
        }
        // Unreachable: the loop above always returns.
        return PlanDecision.GiveUp("Kiro CLI produced no decision.")
    }

    private fun command(): List<String> = buildList {
        add(config.executable)
        add("chat")
        add("--no-interactive")
        // Trust no tools at all. This planner must only return a decision;
        // Fingertip performs the tapping through the accessibility API, behind the
        // confirmation gates. Letting the CLI act directly would put a second,
        // unsupervised actor on the user's phone.
        add("--trust-tools=")
        addAll(config.extraArgs)
        // The observation and rules arrive on stdin; this argument is only the
        // directive, keeping a large screen dump off the command line.
        add("Read the instructions on standard input and reply with one JSON object only.")
    }

    /** Turns an exit code into something diagnosable. Documented codes: 0, 1, 3. */
    private fun describeExit(result: CommandResult): String {
        val detail = result.stderr.trim().ifBlank { result.stdout.trim() }.take(300)
        val meaning = when (result.exitCode) {
            1 -> "Kiro CLI failed. Check that KIRO_API_KEY is set and valid."
            3 -> "Kiro CLI could not start its MCP servers."
            127 -> "kiro-cli was not found on PATH."
            else -> "Kiro CLI exited with code ${result.exitCode}."
        }
        return if (detail.isBlank()) meaning else "$meaning $detail"
    }
}

/**
 * Real [CommandRunner] over [ProcessBuilder].
 *
 * The child inherits this process's environment, which is how `KIRO_API_KEY`
 * reaches `kiro-cli` without ever being handled here.
 */
class ProcessCommandRunner : CommandRunner {
    override fun run(command: List<String>, stdin: String?, timeoutMs: Long): CommandResult {
        val process = try {
            ProcessBuilder(command).start()
        } catch (error: Exception) {
            // Most often a missing executable. Report it like a shell would.
            return CommandResult(127, "", error.message ?: "could not start ${command.firstOrNull()}")
        }

        if (stdin != null) {
            runCatching { process.outputStream.bufferedWriter().use { it.write(stdin) } }
        } else {
            runCatching { process.outputStream.close() }
        }

        // Drain both pipes on separate threads: a full stderr buffer will otherwise
        // block the child forever and the timeout will fire for the wrong reason.
        val stdout = StringBuilder()
        val stderr = StringBuilder()
        val outThread = Thread { process.inputStream.bufferedReader().forEachLine { stdout.appendLine(it) } }
        val errThread = Thread { process.errorStream.bufferedReader().forEachLine { stderr.appendLine(it) } }
        outThread.isDaemon = true
        errThread.isDaemon = true
        outThread.start()
        errThread.start()

        val finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
        if (!finished) {
            process.destroyForcibly()
            outThread.join(1_000)
            errThread.join(1_000)
            return CommandResult(-1, stdout.toString(), stderr.toString(), timedOut = true)
        }
        outThread.join(2_000)
        errThread.join(2_000)
        return CommandResult(process.exitValue(), stdout.toString(), stderr.toString())
    }
}
