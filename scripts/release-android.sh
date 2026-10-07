#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_root"

required_vars=(
  CHINCHILLACAM_KEYSTORE
  CHINCHILLACAM_KEYSTORE_PASSWORD
  CHINCHILLACAM_KEY_ALIAS
  CHINCHILLACAM_KEY_PASSWORD
)
for name in "${required_vars[@]}"; do
  if [[ -z "${!name:-}" ]]; then
    printf 'Falta %s. Cargue las variables de firma antes de crear el release:\n' "$name" >&2
    printf 'set -a; . ~/.chinchillacam/release-signing.env; set +a\n' >&2
    exit 1
  fi
done

android_home="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
if [[ ! -d "$android_home/build-tools" ]]; then
  printf 'No se encontró Android SDK build-tools en %s (configure ANDROID_HOME).\n' "$android_home" >&2
  exit 1
fi

# Order numeric version components, not lexically (35.0.0 is newer than 9.0.0).
apksigner=''
aapt2=''
while IFS= read -r version; do
  tools="$android_home/build-tools/$version"
  if [[ -x "$tools/apksigner" && -x "$tools/aapt2" ]]; then
    apksigner="$tools/apksigner"
    aapt2="$tools/aapt2"
    break
  fi
done < <(for dir in "$android_home"/build-tools/*; do
  [[ -d "$dir" ]] && printf '%s\n' "${dir##*/}"
done | sort -t. -k1,1nr -k2,2nr -k3,3nr)
if [[ -z "$apksigner" ]]; then
  printf 'No se encontraron apksigner y aapt2 ejecutables en Android SDK build-tools.\n' >&2
  exit 1
fi

version_name="$(sed -nE 's/^[[:space:]]*versionName[[:space:]]*=[[:space:]]*"([^"]+)"[[:space:]]*$/\1/p' android/usb-probe/build.gradle.kts)"
if [[ ! "$version_name" =~ ^[0-9A-Za-z][0-9A-Za-z._-]*$ ]]; then
  printf 'No se pudo leer un versionName literal válido de android/usb-probe/build.gradle.kts.\n' >&2
  exit 1
fi

gradle="${GRADLE:-$HOME/.gradle/wrapper/dists/gradle-8.0.1-all/aro4hu1c3oeioove7l0i4i14o/gradle-8.0.1/bin/gradle}"
if [[ ! -x "$gradle" ]]; then
  printf 'No se encontró Gradle ejecutable en %s (configure GRADLE).\n' "$gradle" >&2
  exit 1
fi

apk='android/usb-probe/build/outputs/apk/release/usb-probe-release.apk'
dist_dir="$repo_root/dist"
artifact_name="ChinchillaCam-${version_name}-android.apk"
checksum_name='SHA256SUMS-android.txt'
mkdir -p "$dist_dir"
rm -f -- "$dist_dir/$artifact_name" "$dist_dir/$checksum_name"

env -u CHINCHILLA_PAIRING_PROOF_HELPER "$gradle" :android:usb-probe:assembleRelease --offline
if [[ ! -f "$apk" ]]; then
  printf 'No se generó el APK de release: %s\n' "$apk" >&2
  exit 1
fi

certs="$("$apksigner" verify --print-certs "$apk")"
signer_digest="$(printf '%s\n' "$certs" | sed -nE 's/^Signer #[0-9]+ certificate SHA-256 digest: ([[:xdigit:]]{64})$/\1/p')"
if [[ ! "$signer_digest" =~ ^[[:xdigit:]]{64}$ ]]; then
  printf 'No se pudo identificar un único certificado SHA-256 en el APK.\n' >&2
  exit 1
fi
signer_digest="$(printf '%s' "$signer_digest" | tr '[:upper:]' '[:lower:]')"

badging="$("$aapt2" dump badging "$apk")"
package_line="$(printf '%s\n' "$badging" | grep '^package: ')"
if [[ "$package_line" != *"name='io.github.oyhamburo.chinchillacam'"* ||
      "$package_line" != *"versionName='$version_name'"* ]]; then
  printf 'El APK no contiene el paquete público y versionName esperados (%s).\n' "$version_name" >&2
  exit 1
fi

if [[ -n "${EXPECTED_CERT_SHA256:-}" ]]; then
  expected="$(printf '%s' "$EXPECTED_CERT_SHA256" | tr '[:upper:]' '[:lower:]')"
  if [[ "$signer_digest" != "$expected" ]]; then
    printf 'El certificado SHA-256 del APK no coincide con EXPECTED_CERT_SHA256.\n' >&2
    exit 1
  fi
fi

cp "$apk" "$dist_dir/$artifact_name"
(cd "$dist_dir" && shasum -a 256 "$artifact_name" > "$checksum_name")
printf 'APK: %s\nChecksums: %s\nCertificado SHA-256: %s\n' \
  "$dist_dir/$artifact_name" "$dist_dir/$checksum_name" "$signer_digest"
