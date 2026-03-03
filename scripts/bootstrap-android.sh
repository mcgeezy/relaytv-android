#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"

INSTALL_EMULATOR="${INSTALL_EMULATOR:-0}"
ANDROID_PLATFORM="${ANDROID_PLATFORM:-android-34}"
ANDROID_BUILD_TOOLS="${ANDROID_BUILD_TOOLS:-34.0.0}"
ANDROID_SYSTEM_IMAGE="${ANDROID_SYSTEM_IMAGE:-system-images;android-34;google_apis;x86_64}"

say() { printf '%s\n' "$*"; }

find_sdkmanager() {
    if command -v sdkmanager >/dev/null 2>&1; then
        command -v sdkmanager
        return 0
    fi

    local sdk="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
    if [[ -n "${sdk}" ]]; then
        local candidate="${sdk}/cmdline-tools/latest/bin/sdkmanager"
        if [[ -x "${candidate}" ]]; then
            printf '%s\n' "${candidate}"
            return 0
        fi
    fi
    return 1
}

ensure_sdk_root() {
    if [[ -n "${ANDROID_SDK_ROOT:-}" ]]; then
        mkdir -p "${ANDROID_SDK_ROOT}"
        return
    fi
    if [[ -n "${ANDROID_HOME:-}" ]]; then
        export ANDROID_SDK_ROOT="${ANDROID_HOME}"
        mkdir -p "${ANDROID_SDK_ROOT}"
        return
    fi
    export ANDROID_SDK_ROOT="${HOME}/Android/Sdk"
    mkdir -p "${ANDROID_SDK_ROOT}"
}

ensure_gradlew_executable() {
    if [[ ! -x "${REPO_ROOT}/gradlew" ]]; then
        chmod +x "${REPO_ROOT}/gradlew"
    fi
}

ensure_sdk_root
ensure_gradlew_executable

SDKMANAGER_BIN="$(find_sdkmanager || true)"
if [[ -z "${SDKMANAGER_BIN}" ]]; then
    say "sdkmanager not found."
    say "Install Android cmdline-tools and add sdkmanager to PATH, then rerun."
    exit 1
fi

PACKAGES=(
    "platform-tools"
    "platforms;${ANDROID_PLATFORM}"
    "build-tools;${ANDROID_BUILD_TOOLS}"
    "cmdline-tools;latest"
)

if [[ "${INSTALL_EMULATOR}" == "1" ]]; then
    PACKAGES+=("emulator" "${ANDROID_SYSTEM_IMAGE}")
fi

say "Using Android SDK root: ${ANDROID_SDK_ROOT}"
say "Installing packages:"
printf ' - %s\n' "${PACKAGES[@]}"

"${SDKMANAGER_BIN}" --sdk_root="${ANDROID_SDK_ROOT}" "${PACKAGES[@]}"
"${SDKMANAGER_BIN}" --sdk_root="${ANDROID_SDK_ROOT}" --licenses < <(yes)

say
say "Bootstrap complete."
say "Tip: run ./scripts/check-android-env.sh all"
