package dev.fingertip.core.skill

/**
 * The on-device skill pack: maps what the user said to a skill to replay.
 *
 * Matching is intentionally simple lexical scoring rather than embeddings. It
 * runs in microseconds with no model, no network and no battery cost, which
 * keeps the common case ("read my last message", said fifty times a day)
 * instant. Fuzzy natural-language understanding is the *fallback* when nothing
 * here scores well enough, not the default path.
 */
class SkillLibrary(skills: List<Skill>) {

    val skills: List<Skill> = skills.toList()

    private val byId: Map<String, Skill> = skills.associateBy { it.id }

    init {
        val duplicates = skills.groupBy { it.id }.filterValues { it.size > 1 }.keys
        require(duplicates.isEmpty()) { "Duplicate skill ids: ${duplicates.sorted().joinToString()}" }
    }

    data class Match(
        val skill: Skill,
        /** 0.0 to 1.0. 1.0 means the phrasing matched an example exactly. */
        val score: Double,
        /** Which registered utterance produced the score, for debugging. */
        val matchedUtterance: String,
    )

    fun byId(id: String): Skill? = byId[id]

    /**
     * Best match for a spoken phrase, or null when nothing is confident enough.
     *
     * A null result should escalate to the reasoning agent rather than guessing:
     * running the wrong skill could send a message or spend money, so a false
     * positive is far more costly than admitting uncertainty.
     */
    fun match(utterance: String, minScore: Double = 0.6): Match? {
        val queryTokens = tokenize(utterance)
        if (queryTokens.isEmpty()) return null

        var best: Match? = null
        for (skill in skills) {
            val candidates = skill.utterances + skill.title
            for (candidate in candidates) {
                val score = score(queryTokens, tokenize(candidate))
                if (score > (best?.score ?: 0.0)) {
                    best = Match(skill, score, candidate)
                }
            }
        }
        return best?.takeIf { it.score >= minScore }
    }

    /** All matches above the bar, best first — for "did you mean?" disambiguation. */
    fun rank(utterance: String, minScore: Double = 0.4, limit: Int = 3): List<Match> {
        val queryTokens = tokenize(utterance)
        if (queryTokens.isEmpty()) return emptyList()
        return skills.mapNotNull { skill ->
            (skill.utterances + skill.title)
                .map { candidate -> Match(skill, score(queryTokens, tokenize(candidate)), candidate) }
                .maxByOrNull { it.score }
        }
            .filter { it.score >= minScore }
            .sortedByDescending { it.score }
            .take(limit)
    }

    /**
     * Harmonic mean of precision and recall over token sets.
     *
     * Symmetric scoring matters: it penalises both "user said far more than the
     * skill covers" and "skill is far broader than what the user asked for",
     * either of which usually means it is the wrong skill.
     */
    private fun score(query: Set<String>, candidate: Set<String>): Double {
        if (candidate.isEmpty()) return 0.0
        if (query == candidate) return 1.0
        val overlap = query.intersect(candidate).size
        if (overlap == 0) return 0.0
        val precision = overlap.toDouble() / query.size
        val recall = overlap.toDouble() / candidate.size
        return 2 * precision * recall / (precision + recall)
    }

    private fun tokenize(text: String): Set<String> =
        text.lowercase()
            .split(NON_WORD)
            .asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && it !in STOPWORDS }
            .map(::stem)
            .toSet()

    /** Crudest possible stemmer: strips a trailing plural 's'. Enough for command phrases. */
    private fun stem(token: String): String =
        if (token.length > 3 && token.endsWith('s') && !token.endsWith("ss")) token.dropLast(1) else token

    companion object {
        private val NON_WORD = Regex("[^\\p{L}\\p{N}]+")

        /**
         * Filler that carries no intent. Kept small on purpose — over-aggressive
         * stopword lists destroy short commands like "go back".
         */
        private val STOPWORDS = setOf(
            "a", "an", "the", "my", "me", "please", "can", "you", "could", "would",
            "to", "for", "of", "on", "in", "is", "it", "and", "i", "do", "just", "hey",
        )

        /** Builds a library from raw JSON documents, e.g. files in a skills/ directory. */
        fun fromJson(documents: List<String>): SkillLibrary =
            SkillLibrary(documents.map(SkillJson::decode))
    }
}
