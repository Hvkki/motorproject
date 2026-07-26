package dev.fingertip.core.skill

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The merge gate for the shipped skill pack.
 *
 * This is the test that makes agent-authored skills safe to accept. When a
 * reasoning agent explores a task and opens a pull request adding a skill, CI
 * runs this: the JSON must parse, ids must be unique and match their filename,
 * and [SkillValidator] must find no errors. All without an emulator, so it is
 * fast and deterministic enough to block a merge on.
 *
 * Warnings are printed rather than failed, because some are legitimate for
 * unusual skills; a human reads them during review.
 */
class SkillPackTest {

    private val skillsDir: File = findSkillsDir()

    private val files: List<File> = skillsDir.listFiles { f: File -> f.extension == "json" }
        ?.sortedBy { it.name }
        ?: emptyList()

    @Test
    fun `the skill pack is not empty`() {
        assertTrue(files.isNotEmpty(), "no skills found in ${skillsDir.absolutePath}")
    }

    @Test
    fun `every skill parses`() {
        files.forEach { file ->
            runCatching { SkillJson.decode(file.readText()) }
                .onFailure { fail("${file.name} does not parse: ${it.message}") }
        }
    }

    @Test
    fun `every skill passes validation without errors`() {
        val report = StringBuilder()
        var errors = 0

        files.forEach { file ->
            val skill = SkillJson.decode(file.readText())
            SkillValidator.validate(skill).forEach { problem ->
                report.appendLine("${file.name}: $problem")
                if (problem.severity == SkillValidator.Severity.ERROR) errors++
            }
        }

        if (report.isNotEmpty()) println("Skill pack review notes:\n$report")
        assertTrue(errors == 0, "skill pack has $errors validation error(s):\n$report")
    }

    @Test
    fun `filenames match skill ids`() {
        // Keeps the pack navigable and makes merge conflicts obvious in a PR.
        files.forEach { file ->
            val skill = SkillJson.decode(file.readText())
            assertTrue(
                file.nameWithoutExtension == skill.id,
                "${file.name} declares id '${skill.id}'; rename the file to '${skill.id}.json'",
            )
        }
    }

    @Test
    fun `the pack loads as a library with unique ids`() {
        val library = SkillLibrary.fromJson(files.map { it.readText() })

        assertTrue(library.skills.size == files.size)
    }

    @Test
    fun `every skill is reachable by at least one of its own utterances`() {
        // A skill nobody can trigger is dead weight. This catches utterances made
        // of stopwords, or phrasings that another skill outscores.
        val library = SkillLibrary.fromJson(files.map { it.readText() })

        library.skills.forEach { skill ->
            skill.utterances.forEach { utterance ->
                val match = library.match(utterance)
                    ?: fail("'$utterance' matches nothing, but is registered for ${skill.id}")
                assertTrue(
                    match.skill.id == skill.id,
                    "'$utterance' belongs to ${skill.id} but resolved to ${match.skill.id}",
                )
            }
        }
    }

    private companion object {
        /** Walks up from the module directory so the test works from any working dir. */
        fun findSkillsDir(): File {
            var dir: File? = File(".").absoluteFile
            while (dir != null) {
                val candidate = File(dir, "skills")
                if (candidate.isDirectory) return candidate
                dir = dir.parentFile
            }
            error("Could not locate a 'skills' directory above ${File(".").absolutePath}")
        }
    }
}
