#!/usr/bin/env bash
#
# rpm: builds the phone and TV app bundles for Google Play.
#
# What this does: builds :phone:bundleRelease and :tv:bundleRelease with the
# official API key, the FFmpeg decoders and the upload key, then checks each
# bundle and stops when one of these is missing from it:
#   - the signature of the upload key,
#   - the official API key,
#   - the FFmpeg library for armeabi-v7a and arm64-v8a.
# A bundle without them still builds and installs, and the release build must
# also work from source with none of them. So the checks are here, not in Gradle.
#
# Google Play signs the apps it delivers with the app signing key that it keeps.
# The upload key only shows Google Play who sent the bundle.
#
# Upload the two .aab files in Play Console, under Test and release. Google Play
# refuses a versionCode it already has for that app, so raise versionCode in
# phone/build.gradle.kts or tv/build.gradle.kts before the next upload.
#
# Usage:
#   ./scripts/bundle-play.sh --beta|--live [--no-ffmpeg]
#
#   --beta       A bundle for the closed beta testers on internal testing. Its
#                feedback and crash reports carry the tester's log, screenshot
#                and account, and its versionName ends in "-beta".
#   --live       A bundle for everyone. Its crash reports are anonymous and it
#                has no Send feedback. One of the two must be given: a beta
#                bundle sent to everyone would send their logs.
#   --no-ffmpeg  Build without the FFmpeg decoders. ALAC, AC-3 and E-AC-3 tracks
#                then play from the cloud's AAC copy, never from a computer on
#                the home network. For a test build on your own device only.
#
# What the build needs besides this checkout:
#   RPM_APP_KEY           the official API key. When it is not set, it is read from
#                         Secrets Manager (RPM/AppKey, Current) in eu-west-1 with
#                         the AWS credentials of the environment.
#   RPM_FFMPEG_ROOT       the out folder of scripts/build-android-ffmpeg.sh.
#                         Required unless --no-ffmpeg.
#   RPM_UPLOAD_KEYSTORE   the upload keystore (PKCS12). Default: ~/Keys/rpm-upload.jks.
#   RPM_UPLOAD_KEY_ALIAS  the key in it. Default: rpm-upload.
#   RPM_UPLOAD_PASSWORD   its password. When it is not set on macOS, it is read from
#                         the login keychain: the generic password with the service
#                         rpm-android-upload and the key alias as the account.

set -euo pipefail

FFMPEG=true
BETA=""
for arg in "$@"; do
    case "$arg" in
        --no-ffmpeg) FFMPEG=false ;;
        --beta) BETA=true ;;
        --live) BETA=false ;;
        *) echo "Unknown argument: $arg"; exit 1 ;;
    esac
done
[ -n "$BETA" ] || { echo "FATAL: say --beta (closed beta testers) or --live (everyone)."; exit 1; }

cd "$(dirname "$0")/../RPM.Android"

RPM_UPLOAD_KEYSTORE="${RPM_UPLOAD_KEYSTORE:-$HOME/Keys/rpm-upload.jks}"
RPM_UPLOAD_KEY_ALIAS="${RPM_UPLOAD_KEY_ALIAS:-rpm-upload}"
[ -f "$RPM_UPLOAD_KEYSTORE" ] || { echo "FATAL: there is no upload keystore at $RPM_UPLOAD_KEYSTORE."; exit 1; }
if [ -z "${RPM_UPLOAD_PASSWORD:-}" ] && [ "$(uname -s)" = Darwin ]; then
    RPM_UPLOAD_PASSWORD="$(security find-generic-password -s rpm-android-upload -a "$RPM_UPLOAD_KEY_ALIAS" -w)" \
        || { echo "FATAL: RPM_UPLOAD_PASSWORD is not set and the keychain has no rpm-android-upload password."; exit 1; }
fi
[ -n "${RPM_UPLOAD_PASSWORD:-}" ] || { echo "FATAL: RPM_UPLOAD_PASSWORD is not set."; exit 1; }
export RPM_UPLOAD_KEYSTORE RPM_UPLOAD_KEY_ALIAS RPM_UPLOAD_PASSWORD

if [ -z "${RPM_APP_KEY:-}" ]; then
    RPM_APP_KEY="$(aws secretsmanager get-secret-value --region eu-west-1 --secret-id RPM/AppKey \
        --query SecretString --output text \
        | python3 -I -c 'import json, sys; print(json.load(sys.stdin)["Current"])')" \
        || { echo "FATAL: RPM_APP_KEY is not set and RPM/AppKey could not be read from Secrets Manager."; exit 1; }
fi
[ -n "$RPM_APP_KEY" ] || { echo "FATAL: the API key is empty."; exit 1; }

# -P wins over a developer key set as rpm.appKey in the Gradle properties. Without the configuration
# cache, the passwords and the key are not written into it.
GRADLE_ARGS=(--no-configuration-cache "-Prpm.appKey=$RPM_APP_KEY" "-Prpm.beta=$BETA")
if $FFMPEG; then
    [ -n "${RPM_FFMPEG_ROOT:-}" ] \
        || { echo "FATAL: RPM_FFMPEG_ROOT is not set. Build it with scripts/build-android-ffmpeg.sh, or pass --no-ffmpeg."; exit 1; }
    for abi in armeabi-v7a arm64-v8a; do
        [ -d "$RPM_FFMPEG_ROOT/$abi/lib" ] || { echo "FATAL: $RPM_FFMPEG_ROOT has no $abi/lib."; exit 1; }
    done
    GRADLE_ARGS+=("-Prpm.ffmpegRoot=$RPM_FFMPEG_ROOT")
fi

./gradlew "${GRADLE_ARGS[@]}" :phone:bundleRelease :tv:bundleRelease

# The bundles are under rpm.buildRoot when the machine's Gradle properties set it (see build.gradle.kts).
PROPS="${GRADLE_USER_HOME:-$HOME/.gradle}/gradle.properties"
BUILD_ROOT=""
[ -f "$PROPS" ] && BUILD_ROOT="$(sed -n 's/^rpm\.buildRoot=//p' "$PROPS" | tail -n 1)"
bundle_path() {
    if [ -n "$BUILD_ROOT" ]; then
        echo "$BUILD_ROOT/$1/outputs/bundle/release/$1-release.aab"
    else
        echo "$1/build/outputs/bundle/release/$1-release.aab"
    fi
}

# The first SHA-256 fingerprint keytool prints. awk reads to the end, so keytool never writes into a
# closed pipe, which pipefail would report as a failure.
sha256_of() { awk '/^[[:space:]]*SHA256: / && !found { print $2; found = 1 }'; }
KEY_SHA256="$(keytool -J-Duser.language=en -list -v -keystore "$RPM_UPLOAD_KEYSTORE" \
    -alias "$RPM_UPLOAD_KEY_ALIAS" -storepass:env RPM_UPLOAD_PASSWORD | sha256_of)"
[ -n "$KEY_SHA256" ] || { echo "FATAL: could not read the certificate of $RPM_UPLOAD_KEY_ALIAS."; exit 1; }

for module in phone tv; do
    aab="$(bundle_path "$module")"
    [ -f "$aab" ] || { echo "FATAL: there is no bundle at $aab."; exit 1; }

    signed="$(keytool -J-Duser.language=en -printcert -jarfile "$aab" | sha256_of)"
    [ "$signed" = "$KEY_SHA256" ] || { echo "FATAL: $aab is not signed with the upload key."; exit 1; }

    # grep -c reads all of its input, for the same reason as sha256_of.
    found="$(unzip -p "$aab" 'base/dex/*' | grep -a -c -F -- "$RPM_APP_KEY" || true)"
    [ "${found:-0}" -gt 0 ] || { echo "FATAL: $aab does not hold the API key."; exit 1; }

    if $FFMPEG; then
        files="$(unzip -Z1 "$aab")"
        for abi in armeabi-v7a arm64-v8a; do
            grep -q -x -F "base/lib/$abi/libffmpegJNI.so" <<<"$files" \
                || { echo "FATAL: $aab has no FFmpeg library for $abi."; exit 1; }
        done
    fi

    version="$(sed -n 's/^ *versionCode = //p' "$module/build.gradle.kts")"
    echo "$module: versionCode $version, $(du -h "$aab" | cut -f 1), $aab"
done
echo "Signed with the upload key, SHA-256 $KEY_SHA256."
$FFMPEG || echo "Built without the FFmpeg decoders (--no-ffmpeg)."
