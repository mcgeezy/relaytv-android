#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"

cd "${REPO_ROOT}"

if [[ ! -x "./scripts/check-android-env.sh" ]]; then
    chmod +x "./scripts/check-android-env.sh"
fi
./scripts/check-android-env.sh connected

if [[ ! -x "./gradlew" ]]; then
    chmod +x ./gradlew
fi

adb start-server >/dev/null
DEVICES="$(adb devices | awk 'NR>1 && $2=="device" {print $1}')"
if [[ -z "${DEVICES}" ]]; then
    echo "No connected Android device/emulator detected."
    echo "Connect a device (adb devices) or start an emulator, then rerun."
    exit 1
fi

echo "Connected devices:"
printf ' - %s\n' ${DEVICES}

if [[ "$#" -gt 0 ]]; then
    TASKS=("$@")
else
    TASKS=("connectedDebugAndroidTest")
fi

echo "Running Gradle tasks: ${TASKS[*]}"
./gradlew --no-daemon "${TASKS[@]}"
