#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"

cd "${REPO_ROOT}"

if [[ ! -x "./scripts/check-android-env.sh" ]]; then
    chmod +x "./scripts/check-android-env.sh"
fi
./scripts/check-android-env.sh build

if [[ ! -x "./gradlew" ]]; then
    chmod +x ./gradlew
fi

if [[ "$#" -gt 0 ]]; then
    TASKS=("$@")
else
    TASKS=("clean" "bundleRelease" "lintRelease")
fi

echo "Running Gradle tasks: ${TASKS[*]}"
./gradlew --no-daemon "${TASKS[@]}"
