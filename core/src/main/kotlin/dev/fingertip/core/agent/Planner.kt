package dev.fingertip.core.agent

import dev.fingertip.core.privacy.RedactedSnapshot
import dev.fingertip.core.skill.Step

/**
 * What the agent is allowed to decide after looking at a screen.
 *
 * The vocabulary is deliberately the same [Step] type that skills are made of.
 * That is what lets a trajectory the agent discovers be saved verbatim as a
 * replayable skill, with no translation and no second execution path.
 */
sealed interface PlanDecision {
    /** Perform one step. [why] is for logs and for explaining itself to the user. */
    data class Act(val step: Step, val why: String) : PlanDecision

    /** The goal is achieved. [summary] is spoken to the user. */
    data class Done(val summary: String) : PlanDecision

    /**
     * The agent needs information only the user has.
     *
     * Distinct from failure: asking "which account?" is correct behaviour, and
     * guessing would be dangerous.
     */
    data class AskUser(val question: String) : PlanDecision

    /** The goal cannot be achieved from here. */
    data class GiveUp(val reason: String) : PlanDecision
}

/**
 * One observation handed to the planner.
 *
 * [screen] is a [RedactedSnapshot], so a planner implementation is structurally
 * incapable of receiving unredacted screen content — passwords, card numbers and
 * one-time codes are already masked before this type can exist.
 */
data class PlanRequest(
    /** What the user asked for, in their words. */
    val goal: String,
    val screen: RedactedSnapshot,
    /** The compact serialised tree: what a language model actually reads. */
    val wire: String,
    /**
     * What has happened so far, oldest first, including failures.
     *
     * Feeding failures back is what lets the agent correct itself rather than
     * repeat a step that already missed.
     */
    val history: List<String>,
    /** 1-based step number, so a planner can behave differently when running long. */
    val stepNumber: Int,
)

/**
 * Decides the next action. The expensive, non-deterministic part of the system.
 *
 * Implemented in production by a language model over [PlanRequest.wire]; in tests
 * by a script. Everything around it — perception, safety, loop detection,
 * execution, skill recording — is deterministic and tested without a model.
 */
fun interface Planner {
    fun next(request: PlanRequest): PlanDecision
}
