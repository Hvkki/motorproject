#!/usr/bin/env bash
#
# privacy_guard.sh - blocks the class of bug that would sink this project.
#
# Fingertip can read every screen the user visits: messages, balances,
# passwords. A single stray log statement turns that into a permanent plaintext
# record in logcat, readable by any app with log access on older Android
# versions and captured by crash reporters everywhere.
#
# That bug is trivial to introduce (one debug line, left in) and nearly
# invisible in review, which is exactly the profile of a check that should be
# mechanical rather than human.
#
# Usage:
#   tools/privacy_guard.sh              scan src/main sources
#   tools/privacy_guard.sh --self-test  prove the checks actually fire
#
# Exit codes: 0 clean, 1 violations found, 2 usage/internal error.
#
# Escape hatch: append  // privacy-guard: allow <reason>  to a line that has
# been deliberately reviewed. Reviewers should treat that comment as requiring
# justification in the pull request.

set -uo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ALLOW_MARKER='privacy-guard: allow'
violations=0

# Only production sources. Tests legitimately print and assert on screen content.
scan_roots() {
  find "$1" \
    -type d -name build -prune -o \
    -type d -name test -prune -o \
    -type f \( -name '*.kt' -o -name '*.java' \) -path '*/src/main/*' -print 2>/dev/null
}

report() {
  local rule="$1" file="$2" line="$3" text="$4"
  printf '%s:%s\n  [%s] %s\n' \
    "${file#"$REPO_ROOT"/}" "$line" "$rule" "$(printf '%s' "$text" | sed 's/^[[:space:]]*//')" >&2
  violations=$((violations + 1))
}

# Runs a grep pattern across production sources and reports every hit that is
# not explicitly allow-listed on the same line.
check_pattern() {
  local rule="$1" pattern="$2" root="$3"
  local file line text
  while IFS= read -r file; do
    [ -n "$file" ] || continue
    while IFS=: read -r line text; do
      [ -n "$line" ] || continue
      case "$text" in
        *"$ALLOW_MARKER"*) continue ;;
      esac
      report "$rule" "$file" "$line" "$text"
    done < <(grep -nE "$pattern" "$file" 2>/dev/null)
  done < <(scan_roots "$root")
}

run_checks() {
  local root="$1"

  # 1. No logging at all in production code.
  #
  # Deliberately blunt. A rule like "no logging of screen content" needs taint
  # analysis to enforce; "no logging" is checkable with grep and costs almost
  # nothing, because anything genuinely worth logging can go through a wrapper
  # that redacts first.
  check_pattern 'no-logging' \
    '(^|[^A-Za-z0-9_.])(Log\.[vdiwea]|Timber\.[vdiwea]|println|print)[[:space:]]*\(|System\.(out|err)\.' \
    "$root"

  # 2. Only the privacy package may mint a RedactedSnapshot.
  #
  # Its constructor is `internal`, so the compiler already stops other Gradle
  # modules. This catches the in-module case: a helper inside :core fabricating
  # one and bypassing redaction entirely.
  local file line text
  while IFS= read -r file; do
    [ -n "$file" ] || continue
    case "$file" in
      */core/privacy/*) continue ;;
    esac
    while IFS=: read -r line text; do
      [ -n "$line" ] || continue
      case "$text" in
        *"$ALLOW_MARKER"*) continue ;;
      esac
      report 'redaction-bypass' "$file" "$line" "$text"
    done < <(grep -nE 'RedactedSnapshot[[:space:]]*\(' "$file" 2>/dev/null)
  done < <(scan_roots "$root")

  # 3. Password redaction must never be switched off in production code.
  check_pattern 'password-redaction-disabled' \
    'redactPasswordFields[[:space:]]*=[[:space:]]*false' \
    "$root"

  # 4. Screen content must not be written to disk outside an explicit,
  #    reviewed cache. Persisted screen text is a durable breach.
  check_pattern 'screen-content-persisted' \
    '(SharedPreferences|openFileOutput|FileOutputStream|FileWriter)[^\n]*([Ss]napshot|[Ss]creen|[Nn]ode)' \
    "$root"
}

self_test() {
  # Proves the checks fire, so a silently-broken guard cannot pass CI forever.
  local tmp
  tmp="$(mktemp -d)" || exit 2
  # shellcheck disable=SC2064
  trap "rm -rf '$tmp'" EXIT

  mkdir -p "$tmp/core/src/main/kotlin/bad" "$tmp/core/src/main/kotlin/core/privacy"
  cat > "$tmp/core/src/main/kotlin/bad/Leaky.kt" <<'KOTLIN'
package bad
class Leaky {
    fun oops(snapshot: Any) {
        Log.d("tag", "screen was $snapshot")
        println("node text: $snapshot")
        val forged = RedactedSnapshot(snapshot, 0)
        val policy = RedactionPolicy(redactPasswordFields = false)
    }
}
KOTLIN
  cat > "$tmp/core/src/main/kotlin/bad/Allowed.kt" <<'KOTLIN'
package bad
class Allowed {
    fun fine() {
        Log.d("tag", "startup complete") // privacy-guard: allow no screen data involved
    }
}
KOTLIN

  local before_violations=0
  violations=0
  run_checks "$tmp" 2>/dev/null
  before_violations=$violations

  # Expected: 2 logging + 1 bypass + 1 disabled-redaction = 4. The allow-listed
  # line must not count.
  if [ "$before_violations" -ne 4 ]; then
    printf 'SELF-TEST FAILED: expected 4 violations in the fixture, detected %s\n' "$before_violations" >&2
    exit 1
  fi

  # And a clean tree must produce nothing.
  rm -rf "$tmp/core/src/main/kotlin/bad"
  violations=0
  run_checks "$tmp" 2>/dev/null
  if [ "$violations" -ne 0 ]; then
    printf 'SELF-TEST FAILED: clean fixture reported %s violations\n' "$violations" >&2
    exit 1
  fi

  printf 'privacy guard self-test passed (4 violations detected, 0 false positives)\n'
  exit 0
}

case "${1:-}" in
  --self-test) self_test ;;
  '') ;;
  *)
    printf 'usage: %s [--self-test]\n' "$0" >&2
    exit 2
    ;;
esac

run_checks "$REPO_ROOT"

if [ "$violations" -gt 0 ]; then
  cat >&2 <<EOF

privacy guard: $violations violation(s).

Screen content must never reach a log, a file, or the network without passing
through Redactor first. If a line here is genuinely safe, append:

    // $ALLOW_MARKER <why this is safe>

and justify it in the pull request.
EOF
  exit 1
fi

printf 'privacy guard: clean\n'
