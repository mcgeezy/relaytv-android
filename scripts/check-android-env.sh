#!/usr/bin/env bash
set -euo pipefail

MODE="${1:-all}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"

failures=0
warnings=0

say() { printf '%s\n' "$*"; }
ok() { say "[ok] $*"; }
warn() { say "[warn] $*"; warnings=$((warnings + 1)); }
fail() { say "[fail] $*"; failures=$((failures + 1)); }

need_cmd() {
    local name="$1"
    if command -v "${name}" >/dev/null 2>&1; then
        ok "Found command: ${name}"
    else
        fail "Missing command: ${name}"
    fi
}

sdk_dir_from_local_properties() {
    local lp="${REPO_ROOT}/local.properties"
    [[ -f "${lp}" ]] || return 1
    local raw
    raw="$(grep -E '^sdk\.dir=' "${lp}" | tail -n1 | cut -d'=' -f2- || true)"
    [[ -n "${raw}" ]] || return 1

    # Gradle escaping uses backslashes. This normalizes common Linux/macOS forms.
    local unescaped="${raw//\\:/:}"
    unescaped="${unescaped//\\\\/\\}"
    printf '%s\n' "${unescaped}"
    return 0
}

detect_sdk_root() {
    if [[ -n "${ANDROID_SDK_ROOT:-}" ]]; then
        printf '%s\n' "${ANDROID_SDK_ROOT}"
        return 0
    fi
    if [[ -n "${ANDROID_HOME:-}" ]]; then
        printf '%s\n' "${ANDROID_HOME}"
        return 0
    fi
    if sdk_dir_from_local_properties >/dev/null 2>&1; then
        sdk_dir_from_local_properties
        return 0
    fi
    return 1
}

validate_mode() {
    case "${MODE}" in
        build|connected|all) ;;
        *)
            say "Usage: $0 [build|connected|all]"
            exit 2
            ;;
    esac
}

check_base_tools() {
    need_cmd java
    need_cmd javac
}

check_connected_tools() {
    need_cmd adb
}

check_sdk() {
    local sdk_root=""
    if sdk_root="$(detect_sdk_root)"; then
        if [[ -d "${sdk_root}" ]]; then
            ok "Android SDK root: ${sdk_root}"
        else
            fail "Android SDK path does not exist: ${sdk_root}"
        fi
    else
        fail "Android SDK root not found. Set ANDROID_SDK_ROOT (or ANDROID_HOME)."
    fi

    if command -v sdkmanager >/dev/null 2>&1; then
        ok "Found command: sdkmanager"
    elif [[ -x "${sdk_root}/cmdline-tools/latest/bin/sdkmanager" ]]; then
        ok "Found sdkmanager at ${sdk_root}/cmdline-tools/latest/bin/sdkmanager"
    else
        warn "sdkmanager not found in PATH or ${sdk_root}/cmdline-tools/latest/bin."
    fi
}

check_gradle_wrapper() {
    if [[ -f "${REPO_ROOT}/gradlew" ]]; then
        ok "Found gradle wrapper"
    else
        fail "Missing gradlew in repo root"
    fi
}

validate_mode

say "Checking Android environment (${MODE}) for ${REPO_ROOT}"
check_base_tools
check_gradle_wrapper
check_sdk

if [[ "${MODE}" == "connected" || "${MODE}" == "all" ]]; then
    check_connected_tools
fi

if [[ "${failures}" -gt 0 ]]; then
    say
    fail "Environment check failed with ${failures} issue(s) and ${warnings} warning(s)."
    exit 1
fi

say
ok "Environment check passed with ${warnings} warning(s)."
