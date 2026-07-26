#!/usr/bin/env bash
#
# device_test.sh - run Fingertip's accessibility tests on a real Android phone.
#
# This is the only way to verify the things an emulator cannot: TalkBack
# coexistence, real gesture dispatch, and genuine OEM layouts.
#
# SAFETY: this script changes accessibility settings on a real device, which for
# some users is how they operate the phone at all. Two rules follow:
#
#   1. Fingertip is APPENDED to the enabled-services list, never substituted for
#      it. Writing the list wholesale would switch TalkBack off, which on a blind
#      user's phone is the difference between a test and a lockout.
#   2. The original values are captured up front and restored on exit, including
#      on failure or Ctrl-C.
#
# Usage:
#   tools/device_test.sh                 # build, install, test, restore
#   tools/device_test.sh --keep-enabled  # leave Fingertip enabled for manual use
#   ANDROID_SERIAL=<serial> tools/device_test.sh
#
# Requires: adb on PATH or ANDROID_HOME set, USB debugging enabled, one device.

set -uo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
APP_ID="dev.fingertip"
SERVICE="${APP_ID}/dev.fingertip.android.FingertipAccessibilityService"
TEST_RUNNER="${APP_ID}.test/androidx.test.runner.AndroidJUnitRunner"
TEST_CLASS="dev.fingertip.android.RealSettingsNodeTreeTest"
TALKBACK_PACKAGE="com.google.android.marvin.talkback"
REPORT="${REPO_ROOT}/device-test-report.txt"

KEEP_ENABLED=0
[ "${1:-}" = "--keep-enabled" ] && KEEP_ENABLED=1

ADB="$(command -v adb || true)"
if [ -z "$ADB" ] && [ -n "${ANDROID_HOME:-}" ]; then
  ADB="${ANDROID_HOME}/platform-tools/adb"
fi
if [ ! -x "${ADB:-}" ]; then
  echo "adb not found. Install platform-tools or set ANDROID_HOME." >&2
  exit 2
fi

log() { printf '\n=== %s ===\n' "$1"; }
say() { printf '%s\n' "$1"; }

# --- device selection ---------------------------------------------------------

mapfile -t DEVICES < <("$ADB" devices | awk '$2=="device" {print $1}')
if [ "${#DEVICES[@]}" -eq 0 ]; then
  echo "No authorised device. Enable USB debugging and accept the RSA prompt." >&2
  "$ADB" devices -l >&2
  exit 2
fi
SERIAL="${ANDROID_SERIAL:-${DEVICES[0]}}"
if [ "${#DEVICES[@]}" -gt 1 ] && [ -z "${ANDROID_SERIAL:-}" ]; then
  echo "Several devices attached; set ANDROID_SERIAL to choose one:" >&2
  printf '  %s\n' "${DEVICES[@]}" >&2
  exit 2
fi
sh_adb() { "$ADB" -s "$SERIAL" "$@"; }

MODEL="$(sh_adb shell getprop ro.product.model | tr -d '\r')"
RELEASE="$(sh_adb shell getprop ro.build.version.release | tr -d '\r')"
SDK="$(sh_adb shell getprop ro.build.version.sdk | tr -d '\r')"
BRAND="$(sh_adb shell getprop ro.product.brand | tr -d '\r')"

log "Device"
say "  $BRAND $MODEL - Android $RELEASE (API $SDK), serial $SERIAL"

# --- capture and restore accessibility state ----------------------------------

read_setting() {
  local value
  value="$(sh_adb shell settings get secure "$1" | tr -d '\r')"
  [ "$value" = "null" ] && value=""
  printf '%s' "$value"
}

ORIGINAL_SERVICES="$(read_setting enabled_accessibility_services)"
ORIGINAL_ENABLED="$(read_setting accessibility_enabled)"
RESTORED=0

restore() {
  [ "$RESTORED" -eq 1 ] && return
  RESTORED=1
  if [ "$KEEP_ENABLED" -eq 1 ]; then
    say ""
    say "Leaving Fingertip enabled as requested (--keep-enabled)."
    say "To undo: adb -s $SERIAL shell settings put secure enabled_accessibility_services '${ORIGINAL_SERVICES}'"
    return
  fi
  log "Restoring original accessibility settings"
  if [ -z "$ORIGINAL_SERVICES" ]; then
    sh_adb shell settings delete secure enabled_accessibility_services >/dev/null 2>&1
  else
    sh_adb shell settings put secure enabled_accessibility_services "$ORIGINAL_SERVICES" >/dev/null 2>&1
  fi
  if [ -z "$ORIGINAL_ENABLED" ]; then
    sh_adb shell settings delete secure accessibility_enabled >/dev/null 2>&1
  else
    sh_adb shell settings put secure accessibility_enabled "$ORIGINAL_ENABLED" >/dev/null 2>&1
  fi
  local now
  now="$(read_setting enabled_accessibility_services)"
  say "  enabled services now: ${now:-<none>}"
  if [ -n "$ORIGINAL_SERVICES" ] && [ "$now" != "$ORIGINAL_SERVICES" ]; then
    say "  WARNING: could not restore exactly. Original value was:"
    say "    $ORIGINAL_SERVICES"
  fi
}
trap restore EXIT INT TERM

# --- TalkBack presence --------------------------------------------------------

TALKBACK_INSTALLED=no
TALKBACK_ACTIVE=no
if sh_adb shell pm list packages | tr -d '\r' | grep -q "$TALKBACK_PACKAGE"; then
  TALKBACK_INSTALLED=yes
fi
case "$ORIGINAL_SERVICES" in
  *"$TALKBACK_PACKAGE"*) TALKBACK_ACTIVE=yes ;;
esac

log "Screen reader"
say "  TalkBack installed: $TALKBACK_INSTALLED"
say "  TalkBack currently enabled: $TALKBACK_ACTIVE"
if [ "$TALKBACK_ACTIVE" = no ]; then
  say ""
  say "  Coexistence is the single biggest design risk in this project, and it"
  say "  cannot be observed with TalkBack off. For the test that matters most:"
  say "    Settings > Accessibility > TalkBack > On, then re-run this script."
fi

# --- build and install --------------------------------------------------------

log "Building and installing"
if [ -z "${ANDROID_HOME:-}" ] && [ ! -f "${REPO_ROOT}/local.properties" ]; then
  echo "Set ANDROID_HOME or create local.properties with sdk.dir=..." >&2
  exit 2
fi
( cd "$REPO_ROOT" && ./gradlew --console=plain :android:installDebug :android:installDebugAndroidTest ) || {
  echo "Build or install failed." >&2
  exit 1
}

# --- enable Fingertip alongside whatever is already running -------------------

log "Enabling Fingertip alongside existing services"
if [ -z "$ORIGINAL_SERVICES" ]; then
  MERGED="$SERVICE"
else
  case "$ORIGINAL_SERVICES" in
    *"$SERVICE"*) MERGED="$ORIGINAL_SERVICES" ;;
    # Append, never replace: this is what keeps TalkBack running.
    *) MERGED="${ORIGINAL_SERVICES}:${SERVICE}" ;;
  esac
fi
sh_adb shell settings put secure enabled_accessibility_services "$MERGED"
sh_adb shell settings put secure accessibility_enabled 1
sleep 2

say "  requested: $MERGED"
BOUND="$(sh_adb shell dumpsys accessibility | tr -d '\r')"
if printf '%s' "$BOUND" | grep -q "$APP_ID"; then
  say "  Fingertip service bound: yes"
else
  echo "  Fingertip service did NOT bind. dumpsys excerpt:" >&2
  printf '%s\n' "$BOUND" | grep -iA3 "Services" | head -20 >&2
  exit 1
fi
if [ "$TALKBACK_ACTIVE" = yes ]; then
  if printf '%s' "$BOUND" | grep -q "$TALKBACK_PACKAGE"; then
    say "  TalkBack still bound alongside Fingertip: yes  <-- coexistence holds"
  else
    say "  TalkBack is NO LONGER BOUND. This is a coexistence failure worth reporting."
  fi
fi

# --- run the tests ------------------------------------------------------------

log "Running $TEST_CLASS"
sh_adb logcat -c >/dev/null 2>&1
INSTRUMENTATION="$(sh_adb shell am instrument -w -r -e class "$TEST_CLASS" "$TEST_RUNNER" 2>&1)"
printf '%s\n' "$INSTRUMENTATION" | grep -vE '^INSTRUMENTATION_STATUS(_CODE)?: (numtests|stream|id|current|test|class)=' | tail -25

NODE_TREE="$(sh_adb exec-out run-as "$APP_ID" cat files/node-tree.txt 2>/dev/null || true)"

# --- report -------------------------------------------------------------------

RESULT=FAILED
printf '%s' "$INSTRUMENTATION" | grep -qE 'OK \([0-9]+ test' && RESULT=PASSED

{
  echo "Fingertip device test report"
  echo "============================"
  echo "device:              $BRAND $MODEL, Android $RELEASE (API $SDK)"
  echo "result:              $RESULT"
  echo "talkback installed:  $TALKBACK_INSTALLED"
  echo "talkback enabled:    $TALKBACK_ACTIVE"
  if [ "$TALKBACK_ACTIVE" = yes ]; then
    if printf '%s' "$BOUND" | grep -q "$TALKBACK_PACKAGE"; then
      echo "coexistence:         TalkBack remained bound with Fingertip"
    else
      echo "coexistence:         FAILURE - TalkBack was unbound"
    fi
  else
    echo "coexistence:         not exercised (TalkBack was off)"
  fi
  echo
  echo "instrumentation summary:"
  printf '%s\n' "$INSTRUMENTATION" | grep -E '^(OK|FAILURES|Time|INSTRUMENTATION_CODE)' | sed 's/^/  /'
  echo
  echo "redacted Settings node tree as the agent saw it:"
  if [ -n "$NODE_TREE" ]; then
    printf '%s\n' "$NODE_TREE" | sed 's/^/  /'
  else
    echo "  <not captured>"
  fi
} > "$REPORT"

log "Report"
cat "$REPORT"
say ""
say "Saved to: $REPORT"
say "Paste that file back and it can be acted on directly."

[ "$RESULT" = PASSED ] || exit 1
