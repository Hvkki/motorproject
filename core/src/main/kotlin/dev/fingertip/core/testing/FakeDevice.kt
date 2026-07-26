package dev.fingertip.core.testing

import dev.fingertip.core.device.Device
import dev.fingertip.core.device.ScrollDirection
import dev.fingertip.core.screen.Bounds
import dev.fingertip.core.screen.Node
import dev.fingertip.core.screen.ScreenSnapshot

/**
 * A scriptable phone.
 *
 * Lives in `main` rather than `test` deliberately: this is the substrate for
 * scheduled skill-regression runs as well as unit tests. Third-party app UIs
 * change without warning, so being able to replay every skill against recorded
 * screens — on a plain JVM, no emulator — is what keeps a skill library from
 * silently rotting.
 *
 * Time is virtual: [sleep] advances a counter instead of blocking, so a skill
 * with an 8-second timeout is exercised in microseconds.
 */
class FakeDevice private constructor(
    private val screens: MutableMap<String, ScreenSnapshot>,
    private val tapTransitions: Map<Key, Transition>,
    private val scrollTransitions: Map<Key, Transition>,
    private var current: String,
    private val installedApps: Map<String, String>,
    override val screenBounds: Bounds,
) : Device {

    private data class Key(val screen: String, val label: String)

    private data class Transition(val target: String, val delayMs: Long = 0)

    private var clock: Long = 0
    private var pending: Pair<String, Long>? = null

    /** Ordered log of what the interpreter did. Assert against this in tests. */
    val tapLog = mutableListOf<String>()
    val typeLog = mutableListOf<Pair<String, String>>()
    val scrollLog = mutableListOf<String>()
    val globalActionLog = mutableListOf<String>()
    var launchLog = mutableListOf<String>()
        private set

    /** Name of the screen currently displayed, after resolving pending transitions. */
    val currentScreen: String
        get() {
            settle()
            return current
        }

    override fun snapshot(): ScreenSnapshot {
        settle()
        val screen = screens[current] ?: error("No screen named '$current'")
        return screen.copy(capturedAtMs = clock)
    }

    override fun launchApp(packageName: String): Boolean {
        launchLog += packageName
        val target = installedApps[packageName] ?: return false
        current = target
        pending = null
        return true
    }

    override fun tap(handle: Int): Boolean {
        val node = node(handle) ?: return false
        if (!node.clickable) return false
        val label = node.label ?: "handle:$handle"
        tapLog += label
        applyTransition(tapTransitions[Key(current, label)])
        return true
    }

    override fun longPress(handle: Int): Boolean {
        val node = node(handle) ?: return false
        if (!node.longClickable) return false
        tapLog += "long:${node.label ?: handle}"
        return true
    }

    override fun setText(handle: Int, text: String): Boolean {
        val node = node(handle) ?: return false
        if (!node.editable) return false
        typeLog += (node.label ?: "handle:$handle") to text
        // Reflect the edit back into the screen so later steps can read it.
        screens[current] = screens.getValue(current).let { snapshot ->
            snapshot.copy(root = replace(snapshot.root, handle) { it.copy(text = text) })
        }
        return true
    }

    override fun scroll(handle: Int, direction: ScrollDirection): Boolean {
        val node = node(handle) ?: return false
        if (!node.scrollable) return false
        val label = node.label ?: "handle:$handle"
        val transition = scrollTransitions[Key(current, label)]
        scrollLog += "$label:${direction.name.lowercase()}"
        // No registered transition means the list cannot scroll any further.
        if (transition == null) return false
        applyTransition(transition)
        return true
    }

    override fun swipe(fromX: Int, fromY: Int, toX: Int, toY: Int, durationMs: Long): Boolean {
        globalActionLog += "swipe($fromX,$fromY->$toX,$toY)"
        clock += durationMs
        return true
    }

    override fun pressBack(): Boolean {
        globalActionLog += "back"
        return true
    }

    override fun pressHome(): Boolean {
        globalActionLog += "home"
        current = HOME_SCREEN.takeIf { screens.containsKey(it) } ?: current
        return true
    }

    override fun sleep(millis: Long) {
        clock += millis
    }

    override fun nowMs(): Long = clock

    /** Force the clock forward without a sleep, e.g. to simulate a slow network. */
    fun advance(millis: Long) {
        clock += millis
    }

    private fun applyTransition(transition: Transition?) {
        if (transition == null) return
        if (transition.delayMs <= 0) {
            current = transition.target
            pending = null
        } else {
            // Screen change lands in the future; snapshot() will pick it up.
            pending = transition.target to (clock + transition.delayMs)
        }
    }

    private fun settle() {
        val (target, at) = pending ?: return
        if (clock >= at) {
            current = target
            pending = null
        }
    }

    private fun node(handle: Int): Node? {
        settle()
        return screens[current]?.nodeByHandle(handle)
    }

    private fun replace(node: Node, handle: Int, transform: (Node) -> Node): Node =
        if (node.handle == handle) transform(node)
        else node.copy(children = node.children.map { replace(it, handle, transform) })

    companion object {
        const val HOME_SCREEN = "home"

        fun build(block: Builder.() -> Unit): FakeDevice = Builder().apply(block).build()
    }

    class Builder {
        private val screens = linkedMapOf<String, ScreenSnapshot>()
        private val taps = mutableMapOf<Key, Transition>()
        private val scrolls = mutableMapOf<Key, Transition>()
        private val apps = mutableMapOf<String, String>()
        private var start: String? = null
        private var bounds = Bounds(0, 0, 1080, 2400)

        /** Registers a screen. [root] children are auto-assigned stable handles. */
        fun screen(
            name: String,
            packageName: String,
            title: String? = null,
            screenshotAvailable: Boolean = true,
            root: Node,
        ) {
            screens[name] = ScreenSnapshot.of(
                packageName = packageName,
                root = root,
                title = title,
                screenshotAvailable = screenshotAvailable,
            )
            if (start == null) start = name
        }

        /** Tapping the node labelled [label] on [screen] navigates to [goTo] after [delayMs]. */
        fun onTap(screen: String, label: String, goTo: String, delayMs: Long = 0) {
            taps[Key(screen, label)] = Transition(goTo, delayMs)
        }

        /** Scrolling [label] on [screen] moves to [goTo]; unregistered means "cannot scroll". */
        fun onScroll(screen: String, label: String, goTo: String, delayMs: Long = 0) {
            scrolls[Key(screen, label)] = Transition(goTo, delayMs)
        }

        /** Makes [packageName] launchable, landing on [screen]. */
        fun app(packageName: String, screen: String) {
            apps[packageName] = screen
        }

        fun startAt(screen: String) {
            start = screen
        }

        fun screenSize(width: Int, height: Int) {
            bounds = Bounds(0, 0, width, height)
        }

        fun build(): FakeDevice {
            val startScreen = requireNotNull(start) { "FakeDevice needs at least one screen" }
            return FakeDevice(screens, taps, scrolls, startScreen, apps, bounds)
        }
    }
}
