package dev.fingertip.core.privacy

import dev.fingertip.core.screen.Node
import dev.fingertip.core.screen.ScreenSnapshot

/**
 * A snapshot that has passed through [Redactor].
 *
 * The constructor is `internal`, so code outside this module — notably the
 * Android app and any network client — *cannot* fabricate one. Anything that
 * transmits or logs screen content accepts only this type, which turns "remember
 * to redact" from a review-time convention into a compile-time guarantee.
 *
 * @param redactionCount how many values were masked; surfaced in the UI so a
 *   blind user can be told "3 sensitive values were hidden" rather than silently
 *   receiving degraded output.
 */
class RedactedSnapshot internal constructor(
    val snapshot: ScreenSnapshot,
    val redactionCount: Int,
)

/** Tunable policy. Defaults are deliberately conservative. */
data class RedactionPolicy(
    /** Mask contents of nodes Android flags as password inputs. Do not disable. */
    val redactPasswordFields: Boolean = true,
    /** Mask 13-19 digit runs (payment cards). */
    val redactCardNumbers: Boolean = true,
    /** Mask 4-8 digit codes when the surrounding text mentions a code/OTP. */
    val redactOneTimeCodes: Boolean = true,
    /** Mask IBAN-shaped strings. */
    val redactIbans: Boolean = true,
    /** Mask values in fields whose id/label names a secret (cvv, pin, ...). */
    val redactSecretNamedFields: Boolean = true,
    val placeholder: String = "[redacted]",
)

/**
 * Removes sensitive values from screen content **before it crosses a trust
 * boundary** (cloud model, crash report, analytics, log file).
 *
 * Design note, and the reason this class is small but load-bearing:
 * on-device skill execution reads the *raw* snapshot, because reading your
 * banking balance aloud to you is the entire point of the product. Redaction
 * applies only at egress. Getting that boundary right is what makes an agent
 * with full screen access defensible at all.
 */
class Redactor(private val policy: RedactionPolicy = RedactionPolicy()) {

    private var count = 0

    /** Redacts a full snapshot, producing the only type accepted at egress. */
    fun redact(snapshot: ScreenSnapshot): RedactedSnapshot {
        count = 0
        val root = redactNode(snapshot.root)
        return RedactedSnapshot(snapshot.copy(root = root), count)
    }

    private fun redactNode(node: Node): Node {
        val secretByFlag = policy.redactPasswordFields && node.isPassword
        val secretByName = policy.redactSecretNamedFields && namesASecret(node)

        val redacted = if (secretByFlag || secretByName) {
            // Blank the value but keep the label so the agent still knows a
            // password field exists and can tell the user to type into it.
            if (!node.text.isNullOrEmpty()) count++
            node.copy(
                text = if (node.text.isNullOrEmpty()) node.text else policy.placeholder,
                isPassword = true,
            )
        } else {
            node.copy(
                text = node.text?.let(::redactText),
                contentDescription = node.contentDescription?.let(::redactText),
            )
        }
        return redacted.copy(children = node.children.map(::redactNode))
    }

    /**
     * Masks sensitive substrings in free text. Public because failure messages
     * and captured values also need scrubbing before they are sent anywhere.
     */
    fun redactText(input: String): String {
        var out = input
        // IBANs first: an IBAN contains a long digit run that the card pattern
        // would otherwise chew out of the middle, leaving the country prefix behind.
        if (policy.redactIbans) out = out.replaceCounting(IBAN)
        if (policy.redactCardNumbers) out = out.replaceCounting(CARD_NUMBER)
        if (policy.redactOneTimeCodes && OTP_CONTEXT.containsMatchIn(out)) {
            out = out.replaceCounting(SHORT_CODE)
        }
        return out
    }

    /** Redacts text and reports how many values were masked, without snapshot ceremony. */
    fun redactTextCounted(input: String): Pair<String, Int> {
        count = 0
        val out = redactText(input)
        return out to count
    }

    private fun String.replaceCounting(pattern: Regex): String =
        pattern.replace(this) {
            count++
            policy.placeholder
        }

    private fun namesASecret(node: Node): Boolean {
        // Only applies to input fields: a *label* reading "Password" must survive,
        // otherwise the agent can no longer describe the login screen at all.
        if (!node.editable) return false
        val haystack = listOfNotNull(node.viewId, node.contentDescription)
            .joinToString(" ")
            .lowercase()
        return SECRET_FIELD_NAMES.any { it in haystack }
    }

    private companion object {
        /**
         * 13-19 digits, optionally grouped by spaces or dashes. Anchored on
         * non-digit boundaries so it will not bite into longer identifiers.
         */
        val CARD_NUMBER = Regex("(?<![\\d])\\d(?:[ -]?\\d){12,18}(?![\\d])")

        /**
         * Country code, two check digits, then 11-30 alphanumerics.
         *
         * Real IBAN lengths are not multiples of four (a GB IBAN is 22 chars), so
         * this must not assume neat four-character groups — it allows an optional
         * space before each character to accept the printed display form too.
         */
        val IBAN = Regex("\\b[A-Z]{2}\\d{2}(?:[ ]?[A-Z0-9]){11,30}\\b")

        /** Words that indicate nearby digits are a one-time code. */
        val OTP_CONTEXT = Regex(
            "\\b(otp|one[- ]?time|verification|verify|passcode|auth(?:entication)? code|security code|2fa|code is|your code)\\b",
            RegexOption.IGNORE_CASE,
        )

        val SHORT_CODE = Regex("(?<![\\d])\\d{4,8}(?![\\d])")

        val SECRET_FIELD_NAMES = listOf("password", "passwd", "pin", "cvv", "cvc", "secret", "otp", "seed")
    }
}
