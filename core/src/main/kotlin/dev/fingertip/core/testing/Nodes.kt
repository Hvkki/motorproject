package dev.fingertip.core.testing

import dev.fingertip.core.screen.Bounds
import dev.fingertip.core.screen.Node
import dev.fingertip.core.screen.Role

/**
 * Terse constructors for building screen fixtures.
 *
 * All of them supply non-empty bounds, because a zero-area node is treated as
 * off-screen and pruned — an easy trap that makes a fixture look empty for
 * reasons unrelated to the behaviour under test.
 */
object Nodes {

    private val nextTop = java.util.concurrent.atomic.AtomicInteger(0)

    /** Sequential non-overlapping bounds, so fixtures look plausibly like a real layout. */
    private fun nextBounds(height: Int = 120): Bounds {
        val top = nextTop.getAndAccumulate(height) { current, h -> (current + h) % 2400 }
        return Bounds(0, top, 1080, top + height)
    }

    fun container(vararg children: Node, id: String? = null): Node =
        Node(
            role = Role.CONTAINER,
            viewId = id,
            bounds = Bounds(0, 0, 1080, 2400),
            children = children.toList(),
        )

    fun list(vararg children: Node, id: String? = null, scrollable: Boolean = true, label: String? = null): Node =
        Node(
            role = Role.LIST,
            contentDescription = label,
            viewId = id,
            bounds = Bounds(0, 0, 1080, 2000),
            scrollable = scrollable,
            children = children.toList(),
        )

    fun listItem(text: String, id: String? = null, clickable: Boolean = true): Node =
        Node(
            role = Role.LIST_ITEM,
            text = text,
            viewId = id,
            bounds = nextBounds(),
            clickable = clickable,
        )

    fun text(text: String, id: String? = null): Node =
        Node(role = Role.TEXT, text = text, viewId = id, bounds = nextBounds(60))

    fun button(label: String, id: String? = null, enabled: Boolean = true, byDescription: Boolean = false): Node =
        Node(
            role = Role.BUTTON,
            text = if (byDescription) null else label,
            contentDescription = if (byDescription) label else null,
            viewId = id,
            bounds = nextBounds(140),
            clickable = true,
            enabled = enabled,
        )

    fun editText(
        hint: String,
        value: String? = null,
        id: String? = null,
        isPassword: Boolean = false,
    ): Node = Node(
        role = Role.EDIT_TEXT,
        text = value,
        contentDescription = hint,
        viewId = id,
        bounds = nextBounds(150),
        clickable = true,
        editable = true,
        isPassword = isPassword,
    )

    fun switch(label: String, checked: Boolean, id: String? = null): Node =
        Node(
            role = Role.SWITCH,
            text = label,
            viewId = id,
            bounds = nextBounds(120),
            clickable = true,
            checked = checked,
        )

    /** A layout wrapper with no label and no behaviour — the noise the serializer should collapse. */
    fun wrapper(vararg children: Node): Node =
        Node(role = Role.CONTAINER, bounds = Bounds(0, 0, 1080, 2400), children = children.toList())
}
