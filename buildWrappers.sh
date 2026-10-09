#!/usr/bin/env bash
set -Eeuo pipefail

TARGET="All"
NO_BUILD=0
PUBLIC_APK=""

usage() {
    cat <<'EOF'
Usage: ./buildWrappers.sh [OPTIONS]

Options:
  --target Android|IOS|All   Target to build (default: All)
  --no-build                 Stage assets without building
  --public-apk PATH          Encrypted public APK for iOS asset staging
  -h, --help                 Show this help message
EOF
}

die() {
    printf 'Error: %s\n' "$*" >&2
    exit 1
}

log() {
    printf '%s\n' "$*"
}

while (($#)); do
    case "$1" in
        --target)
            (($# >= 2)) || die "--target requires a value"
            TARGET="$2"
            shift 2
            ;;
        --no-build)
            NO_BUILD=1
            shift
            ;;
        --public-apk)
            (($# >= 2)) || die "--public-apk requires a path"
            PUBLIC_APK="$2"
            shift 2
            ;;
        -h|--help)
            usage
            exit 0
            ;;
        *)
            die "Unknown argument: $1 (use --help)"
            ;;
    esac
done

case "$TARGET" in
    Android|IOS|All) ;;
    *) die "Invalid target '$TARGET'. Choose Android, IOS, or All." ;;
esac

# Resolve the repository root from this script's location.
SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
REPO_ROOT="$SCRIPT_DIR"

RUNTIME_SOURCE="$REPO_ROOT/CelesteRuntime"
ANDROID_RUNTIME_DEST="$REPO_ROOT/AndroidWrapper/app/src/main/assets/CelesteRuntime"
IOS_RUNTIME_DEST="$REPO_ROOT/IOSWrapper/assets/CelesteRuntime"

# Refuse to modify anything outside the two approved staging paths.
assert_staging_path() {
    local destination="$1"
    local resolved

    resolved="$(realpath -m -- "$destination")"

    case "$resolved" in
        "$ANDROID_RUNTIME_DEST"|"$IOS_RUNTIME_DEST") ;;
        *)
            die "Refusing to change a path outside the wrapper staging directories: $resolved"
            ;;
    esac
}

copy_runtime() {
    local destination="$1"
    local file relative target_file

    assert_staging_path "$destination"

    [[ -d "$RUNTIME_SOURCE" ]] ||
        die "Missing runtime folder: $RUNTIME_SOURCE"

    mkdir -p -- "$(dirname -- "$destination")"

    if [[ -e "$destination" || -L "$destination" ]]; then
        rm -rf -- "$destination"
    fi

    mkdir -p -- "$destination"

    # Exclude .bak* files, temporary files, logs, PDBs,
    # and all files inside bin/ or obj/ directories.
    while IFS= read -r -d '' file; do
        relative="${file#"$RUNTIME_SOURCE"/}"
        target_file="$destination/$relative"

        mkdir -p -- "$(dirname -- "$target_file")"
        cp -p -- "$file" "$target_file"
    done < <(
        find "$RUNTIME_SOURCE" -type f \
            ! -name '*.tmp' \
            ! -name '*.log' \
            ! -name '*.pdb' \
            ! -name '*.bak*' \
            ! -path '*/bin/*' \
            ! -path '*/obj/*' \
            -print0
    )

    log "Staged CelesteRuntime -> $destination"
}

remove_staged_runtime() {
    local destination="$1"

    assert_staging_path "$destination"

    if [[ -e "$destination" || -L "$destination" ]]; then
        rm -rf -- "$destination"
        log "Removed staged CelesteRuntime from $destination"
    fi
}

build_android() {
    if ((NO_BUILD)); then
        copy_runtime "$ANDROID_RUNTIME_DEST"
        return
    fi

    local android_root="$REPO_ROOT/AndroidWrapper"
    local gradle="$android_root/gradlew"

    [[ -f "$gradle" ]] ||
        die "Missing Android Gradle wrapper: $gradle"

    # Always attempt cleanup after the build, including on failure.
    (
        cleanup() {
            remove_staged_runtime "$ANDROID_RUNTIME_DEST"
        }
        trap cleanup EXIT

        cd -- "$android_root"
        chmod +x "$gradle" 2>/dev/null || true
        "$gradle" --no-daemon :app:assembleDebug
    )
}

build_ios() {
    local ios_root="$REPO_ROOT/IOSWrapper"

    if [[ -n "$PUBLIC_APK" ]]; then
        [[ -f "$PUBLIC_APK" ]] ||
            die "Public APK not found: $PUBLIC_APK"

        command -v python3 >/dev/null 2>&1 ||
            die "python3 is required for iOS asset staging"

        python3 "$ios_root/stage-assets.py" --apk "$PUBLIC_APK" ||
            die "Public iOS asset staging failed"
    fi

    if ((NO_BUILD)); then
        [[ -n "$PUBLIC_APK" ]] ||
            die "iOS staging requires --public-apk pointing to the encrypted public APK"
        return
    fi

    if ! command -v xcodebuild >/dev/null 2>&1; then
        die "iOS source is in IOSWrapper. No IPA was built: macOS and Xcode are required. On a Mac run: bash IOSWrapper/build.sh simulator (or device with signing configured)."
    fi

    bash "$ios_root/build.sh" simulator ||
        die "iOS build failed"
}

case "$TARGET" in
    Android)
        build_android
        ;;
    IOS)
        build_ios
        ;;
    All)
        build_android
        build_ios
        ;;
esac
