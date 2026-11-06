#!/bin/bash
# Fixed Gradle invocations for the android-verify skill.
#
# usage: run_gradle.sh <ci|focused|release|instrumented> <log-dir>
#                      [--fresh] [--tests <pattern>] [--rerun] [--device <serial>] [--dry-run]
#
# This script is the allowlist: it accepts only the tiers below and validated
# arguments, so a permission rule can match one plain command instead of a
# redirect and an `echo`. It writes only into <log-dir>, which must be inside a
# temporary directory and outside the repository. It prints key=value lines (run, since, log, command, exit) and
# exits 0 after Gradle has run, whatever Gradle's exit code; it exits 2 on a
# usage or safety error, before Gradle starts.
#
# `ci --fresh` runs two invocations into the same log, each forcing only its own
# tasks with Gradle's per-task `--rerun` (never `--rerun-tasks`): the unit tests,
# then lint analysis and reporting. The lint step runs even if the tests fail.
# `--dry-run` validates and prints the command lines, then exits without running
# Gradle or writing anything.

set -u

usage() {
  echo "usage: run_gradle.sh <ci|focused|release|instrumented> <log-dir> [--fresh] [--tests <pattern>] [--rerun] [--device <serial>] [--dry-run]" >&2
  exit 2
}

refuse() {
  echo "refused: $*" >&2
  exit 2
}

[ $# -ge 2 ] || usage
tier=$1
log_dir=$2
shift 2

# Variables a command prefix could use to redirect git or inject Gradle or JVM
# options are dropped. JAVA_HOME and PATH are kept: they come from the user's
# profile, and the skills forbid environment prefixes on commands.
for name in $(env | sed -n 's/^\(GIT_[A-Za-z0-9_]*\)=.*/\1/p; s/^\(ORG_GRADLE_PROJECT_[A-Za-z0-9_]*\)=.*/\1/p'); do
  unset "$name"
done
unset GRADLE_OPTS JAVA_OPTS JAVA_TOOL_OPTIONS _JAVA_OPTIONS JDK_JAVA_OPTIONS
# Same read-only git options as .claude/tools/safe_paths.py.
git_safe=(--no-pager --no-optional-locks -c core.fsmonitor=false)
set -o noclobber

root=$(cd "$(dirname "$0")/../../.." && pwd -P)
log_dir=$(cd "$log_dir" 2>/dev/null && pwd -P) || refuse "log dir does not exist"
case "$log_dir/" in
  "$root"/*) refuse "log dir is inside the repository" ;;
esac
# Logs go only to a private subfolder of a temporary directory (the session
# scratchpad), the same rule as .claude/tools/safe_paths.py. The temp roots are
# fixed; $TMPDIR is ignored.
tmp_root=$(cd /tmp && pwd -P)
user_tmp=$(/usr/bin/getconf DARWIN_USER_TEMP_DIR 2>/dev/null) && user_tmp=$(cd "$user_tmp" 2>/dev/null && pwd -P) \
  || user_tmp=$tmp_root
case "$log_dir/" in
  "$tmp_root"/|"$user_tmp"/) refuse "log dir must be a private subfolder, not $log_dir itself" ;;
  "$tmp_root"/*|"$user_tmp"/*) ;;
  *) refuse "log dir must be inside a temporary directory ($tmp_root or $user_tmp)" ;;
esac
[ -O "$log_dir" ] || refuse "log dir must be owned by you"
[ -z "$(find "$log_dir" -maxdepth 0 \( -perm -0020 -o -perm -0002 \))" ] \
  || refuse "log dir must not be writable by other users"

tests=""
rerun=0
fresh=0
dry_run=0
device=""
while [ $# -gt 0 ]; do
  case $1 in
    --tests) [ $# -ge 2 ] || usage; tests=$2; shift 2 ;;
    --rerun) rerun=1; shift ;;
    --fresh) fresh=1; shift ;;
    --dry-run) dry_run=1; shift ;;
    --device) [ $# -ge 2 ] || usage; device=$2; shift 2 ;;
    *) usage ;;
  esac
done

[ $fresh -eq 0 ] || [ "$tier" = ci ] || refuse "--fresh is only for ci"

# step2 stays empty unless a tier needs a second invocation.
step1_name=gradle
step2_name=""
step2=()
case $tier in
  ci)
    [ -z "$tests$device" ] && [ $rerun -eq 0 ] || refuse "ci takes only --fresh"
    if [ $fresh -eq 1 ]; then
      step1_name=tests
      step1=(assembleDebug :app:testDebugUnitTest --rerun)
      step2_name=lint
      step2=(lintDebug
        :app:lintAnalyzeDebug --rerun
        :app:lintAnalyzeDebugUnitTest --rerun
        :app:lintAnalyzeDebugAndroidTest --rerun
        :app:lintReportDebug --rerun)
    else
      step1=(assembleDebug lintDebug testDebugUnitTest)
    fi
    ;;
  focused)
    [ -n "$tests" ] || refuse "focused requires --tests"
    [ -z "$device" ] || refuse "focused takes no --device"
    [[ $tests =~ ^[A-Za-z0-9._*$]+$ ]] || refuse "--tests may contain only letters, digits, . _ * \$"
    step1=(:app:testDebugUnitTest --tests "$tests")
    [ $rerun -eq 1 ] && step1+=(--rerun)
    ;;
  release)
    [ -z "$tests$device" ] && [ $rerun -eq 0 ] || refuse "release takes no options"
    step1=(assembleRelease)
    ;;
  instrumented)
    [ -z "$tests" ] && [ $rerun -eq 0 ] || refuse "instrumented takes only --device"
    [[ $device =~ ^emulator-[0-9]+$ ]] || refuse "--device must be an emulator serial"
    [ "$device" != "emulator-5554" ] || refuse "emulator-5554 is protected"
    if [ $dry_run -eq 0 ]; then
      online=$(adb devices | awk 'NR > 1 && $2 == "device" { print $1 }')
      [ "$online" = "$device" ] || refuse "exactly one device must be online, and it must be $device (online: $(echo $online))"
      avd=$(adb -s "$device" emu avd name 2>/dev/null | head -n 1 | tr -d '\r')
      [ -n "$avd" ] || refuse "could not read the AVD name of $device"
      case $avd in
        Pixel_9a|Pixel_9_Pro_Goldie) refuse "AVD $avd is protected" ;;
      esac
      export ANDROID_SERIAL=$device
    fi
    step1=(connectedDebugAndroidTest)
    ;;
  *)
    usage
    ;;
esac

if [ $dry_run -eq 1 ]; then
  echo "dry-run=1"
  if [ -z "$step2_name" ]; then
    echo "command=android/gradlew -p android ${step1[*]} --stacktrace"
  else
    echo "command.$step1_name=android/gradlew -p android ${step1[*]} --stacktrace"
    echo "command.$step2_name=android/gradlew -p android ${step2[*]} --stacktrace"
  fi
  exit 0
fi

mode=$tier
[ $fresh -eq 1 ] && mode=$tier-fresh
run="$(date +%Y%m%d-%H%M%S)-$mode"
log="$log_dir/$run.log"
meta="$log_dir/$run-meta.txt"
{ [ -e "$log" ] || [ -L "$log" ]; } && refuse "$log already exists"
{ [ -e "$meta" ] || [ -L "$meta" ]; } && refuse "$meta already exists"

# The fingerprint names the code this run verifies (see fingerprint.py).
fingerprint_script="$(cd "$(dirname "$0")" && pwd -P)/fingerprint.py"
fingerprint_before=$(python3 -I "$fingerprint_script" --repo "$root") || refuse "could not compute the fingerprint"

options=""
[ $fresh -eq 1 ] && options="$options --fresh"
[ -n "$tests" ] && options="$options --tests $tests"
[ $rerun -eq 1 ] && options="$options --rerun"
[ -n "$device" ] && options="$options --device $device"

# Prints a key=value line and records it in the run's meta file.
out() {
  echo "$1"
  echo "$1" >> "$meta"
}

# noclobber makes `>` fail on any existing path, including a planted symlink;
# the log and meta files are created that way before anything appends to them.
: > "$log" 2>/dev/null || refuse "$log already exists"
: > "$meta" 2>/dev/null || refuse "$meta already exists"

if [ "$tier" = ci ] || [ "$tier" = focused ]; then
  changed="$log_dir/$run-changed.txt"
  base=$(git -C "$root" "${git_safe[@]}" merge-base main HEAD)
  git -C "$root" "${git_safe[@]}" diff --no-ext-diff --no-textconv --name-only "$base" > "$changed" \
    || refuse "$changed already exists or could not be written"
  echo "changed=$changed"
fi

since=$(date +%s)
out "source=local android-verify"
out "run=$run"
out "tier=$tier"
out "options=${options# }"
while IFS= read -r line; do out "$line"; done <<< "$fingerprint_before"
out "since=$since"
echo "log=$log"
echo "meta=$meta"

# Runs one invocation, appending to the log; sets `status`. Single-step tiers
# keep the plain `command=` line and an unmarked log.
run_step() {
  local name=$1
  shift
  if [ -z "$step2_name" ]; then
    out "command=android/gradlew -p android $* --stacktrace"
  else
    out "command.$name=android/gradlew -p android $* --stacktrace"
    echo "# run_gradle step $name: android/gradlew -p android $* --stacktrace" >> "$log"
  fi
  "$root/android/gradlew" -p "$root/android" "$@" --stacktrace >> "$log" 2>&1
  status=$?
  [ -z "$step2_name" ] || out "exit.$name=$status"
}

run_step "$step1_name" "${step1[@]}"
overall=$status
if [ -n "$step2_name" ]; then
  run_step "$step2_name" "${step2[@]}"
  [ $overall -ne 0 ] || overall=$status
fi
out "exit=$overall"

# A build that changes tracked or untracked files in scope (for example a new
# Room schema) no longer matches the fingerprint it started with.
fingerprint_after=$(python3 -I "$fingerprint_script" --repo "$root" | sed -n 's/^fingerprint=//p')
out "fingerprint_after=${fingerprint_after:-unknown}"
exit 0
