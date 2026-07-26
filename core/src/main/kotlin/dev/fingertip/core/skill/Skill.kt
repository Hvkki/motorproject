package dev.fingertip.core.skill

import dev.fingertip.core.device.ScrollDirection
import dev.fingertip.core.screen.Selector
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * A deterministic, replayable recipe for one task on one app.
 *
 * This is the central artifact of the architecture. An expensive reasoning agent
 * is invoked *once* to explore a novel task and emit a Skill; from then on the
 * task replays locally with no model call — instant, free, offline-capable and
 * identical every time. That converts a per-invocation cost into a
 * per-task-type cost, which is the only way an agent like this is affordable
 * and fast enough for someone who relies on it dozens of times a day.
 *
 * Skills are plain JSON on purpose: they are human-reviewable, diffable in a
 * pull request, and shareable between users, so one person getting unstuck can
 * fix that app for everybody.
 */
@Serializable
data class Skill(
    /** Stable dotted id, e.g. `whatsapp.read_last_message`. */
    val id: String,
    /** Bumped whenever steps change, so devices can pull updates. */
    val version: Int = 1,
    /** Short human title, safe to speak aloud. */
    val title: String,
    /** Target package, when app-specific. */
    val app: String? = null,
    /** Example phrasings a user might say. Matched loosely by [SkillLibrary]. */
    val utterances: List<String> = emptyList(),
    /** Free-text note for reviewers; never spoken. */
    val notes: String? = null,
    val steps: List<Step>,
) {
    init {
        require(id.isNotBlank()) { "Skill id must not be blank" }
        require(steps.isNotEmpty()) { "Skill '$id' has no steps" }
    }
}

/**
 * One action in a skill.
 *
 * Serialised with an `action` discriminator, so a step reads as
 * `{"action":"tap","selector":{"text":"Send"}}`.
 */
@Serializable
sealed interface Step {
    /** Human phrasing used in progress announcements and failure reports. */
    val describe: String
}

/** Brings an app to the foreground. */
@Serializable
@SerialName("launch")
data class Launch(val app: String) : Step {
    override val describe get() = "open $app"
}

/**
 * Blocks until [selector] matches, polling the screen.
 *
 * The single most important step type for reliability: apps animate, load over
 * the network, and show splash screens. Skills that tap immediately after
 * launching are the classic flaky failure.
 */
@Serializable
@SerialName("waitFor")
data class WaitFor(
    val selector: Selector,
    val timeoutMs: Long = 8_000,
    val pollMs: Long = 250,
) : Step {
    override val describe get() = "wait for ${selector.describe()}"
}

@Serializable
@SerialName("tap")
data class Tap(val selector: Selector) : Step {
    override val describe get() = "tap ${selector.describe()}"
}

@Serializable
@SerialName("longPress")
data class LongPress(val selector: Selector) : Step {
    override val describe get() = "long press ${selector.describe()}"
}

/** Types into an editable field. [text] may reference captures as `{name}`. */
@Serializable
@SerialName("type")
data class TypeText(val selector: Selector, val text: String) : Step {
    override val describe get() = "type into ${selector.describe()}"
}

/** Scrolls a container one page. */
@Serializable
@SerialName("scroll")
data class Scroll(
    val selector: Selector,
    val direction: ScrollDirection = ScrollDirection.FORWARD,
) : Step {
    override val describe get() = "scroll ${direction.name.lowercase()}"
}

/** Scrolls [container] repeatedly until [target] appears, or gives up. */
@Serializable
@SerialName("scrollUntil")
data class ScrollUntil(
    val container: Selector,
    val target: Selector,
    val direction: ScrollDirection = ScrollDirection.FORWARD,
    val maxScrolls: Int = 12,
) : Step {
    override val describe get() = "scroll until ${target.describe()}"
}

@Serializable
@SerialName("back")
data object Back : Step {
    override val describe get() = "go back"
}

@Serializable
@SerialName("home")
data object Home : Step {
    override val describe get() = "go to home screen"
}

/** Which part of a node to capture. */
@Serializable
enum class Field { TEXT, DESC, LABEL }

/**
 * Reads a value off the screen into a named variable.
 *
 * Captures come from the *raw* on-device snapshot, never the redacted one: the
 * whole purpose may be to read the user their own balance or verification code.
 * Redaction applies when data leaves the device, not when it is spoken to its
 * owner.
 */
@Serializable
@SerialName("capture")
data class Capture(
    val selector: Selector,
    @SerialName("as") val name: String,
    val field: Field = Field.LABEL,
    /** When true, a missing node yields an empty string instead of failing. */
    val optional: Boolean = false,
) : Step {
    override val describe get() = "read $name"
}

/** Speaks a template such as `"Last message: {message}"`. */
@Serializable
@SerialName("speak")
data class Speak(val template: String) : Step {
    override val describe get() = "speak"
}

/** Fixed pause. Prefer [WaitFor]; this exists for un-observable animations. */
@Serializable
@SerialName("sleep")
data class Sleep(val millis: Long) : Step {
    override val describe get() = "pause ${millis}ms"
}

/** Fails the skill unless the expectation holds. Guards against silent misnavigation. */
@Serializable
@SerialName("assert")
data class Assert(val selector: Selector, val present: Boolean = true) : Step {
    override val describe get() = if (present) "expect ${selector.describe()}" else "expect no ${selector.describe()}"
}

/** JSON codec for skills. Lenient about unknown keys so older clients tolerate newer skill packs. */
object SkillJson {
    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    val format: Json = Json {
        classDiscriminator = "action"
        ignoreUnknownKeys = true
        prettyPrint = true
        prettyPrintIndent = "  "
        encodeDefaults = false
    }

    fun decode(text: String): Skill = format.decodeFromString(Skill.serializer(), text)

    fun encode(skill: Skill): String = format.encodeToString(Skill.serializer(), skill)
}
