#!/usr/bin/env bash
# Builds dist/Inkoma-<version>-x86_64.AppImage.
#
# The bundled runtime is jlinked from Eclipse Temurin 21, which is built against an old glibc, so the
# AppImage runs on Linux Mint 21.3 / Ubuntu 22.04. Set JLINK_JDK=/path/to/jdk to use another JDK.
# Extra Gradle arguments can be passed through GRADLE_ARGS. Set APPIMAGETOOL=/path/to/appimagetool to
# use a local copy instead of downloading it.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
WORK="$ROOT/build/appimage"
DIST="$ROOT/dist"
TEMURIN_URL="https://github.com/adoptium/temurin21-binaries/releases/download/jdk-21.0.8%2B9/OpenJDK21U-jdk_x64_linux_hotspot_21.0.8_9.tar.gz"
APPIMAGETOOL_URL="https://github.com/AppImage/appimagetool/releases/download/continuous/appimagetool-x86_64.AppImage"
# java.scripting + jdk.dynalink: Nashorn (JavaScriptEngine); jdk.zipfs: extension jar assembly;
# jdk.charsets: sources decoding Shift_JIS/GBK/EUC-KR; jdk.localedata: language names in Browse.
MODULES="java.base,java.desktop,java.logging,java.sql,java.xml,java.naming,java.prefs,java.scripting,jdk.dynalink,jdk.unsupported,jdk.crypto.ec,jdk.zipfs,jdk.charsets,jdk.localedata"
LOCALES="en,ja,zh,ko,es,pt,fr,de,it,ru,uk,pl,tr,ar,id,vi,th,ms,fil,nl,sv,cs,hu,ro,el,he,fa,hi,bn,bg,ca,da,fi,nb"

mkdir -p "$WORK" "$DIST"

echo "==> Building application jars"
(cd "$ROOT" && ./gradlew :app:assembleDist ${GRADLE_ARGS:-} --console=plain -q)
VERSION="$(grep -oP 'appVersionName = "\K[^"]+' "$ROOT/app/build.gradle.kts")"

echo "==> Preparing JDK for jlink"
if [ -z "${JLINK_JDK:-}" ]; then
  JLINK_JDK="$WORK/temurin"
  if [ ! -x "$JLINK_JDK/bin/jlink" ]; then
    mkdir -p "$JLINK_JDK"
    curl -fL --retry 4 "$TEMURIN_URL" | tar xz -C "$JLINK_JDK" --strip-components=1
  fi
fi

APPDIR="$WORK/Inkoma.AppDir"
rm -rf "$APPDIR"
mkdir -p "$APPDIR/usr/lib/app" "$APPDIR/usr/share/applications" "$APPDIR/usr/share/icons/hicolor/512x512/apps"

echo "==> jlink runtime"
"$JLINK_JDK/bin/jlink" \
  --add-modules "$MODULES" \
  --include-locales "$LOCALES" \
  --strip-debug --no-header-files --no-man-pages --generate-cds-archive \
  --output "$APPDIR/usr/lib/runtime"
find "$APPDIR/usr/lib/runtime/bin" -type f ! -name java -delete
rm -f "$APPDIR/usr/lib/runtime/lib/libjsound.so" "$APPDIR/usr/lib/runtime/lib/libsplashscreen.so"
# CDS archive for heaps without compressed oops (>32 GB); never used with -Xmx320m.
rm -f "$APPDIR/usr/lib/runtime/lib/server/classes_nocoops.jsa"

echo "==> Copying jars"
cp "$ROOT"/app/build/dist/lib/*.jar "$APPDIR/usr/lib/app/"
echo "$VERSION" > "$APPDIR/usr/lib/app/VERSION"

# sqlite-jdbc ships natives for every OS/arch; keep Linux x86_64 only.
SQLITE_JAR="$(ls "$APPDIR"/usr/lib/app/sqlite-jdbc-*.jar)"
TMP_SQL="$WORK/sqlite"
rm -rf "$TMP_SQL" && mkdir -p "$TMP_SQL"
(
  cd "$TMP_SQL"
  unzip -q "$SQLITE_JAR"
  find org/sqlite/native -mindepth 1 -maxdepth 1 ! -name Linux -exec rm -rf {} +
  find org/sqlite/native/Linux -mindepth 1 -maxdepth 1 ! -name x86_64 -exec rm -rf {} +
  rm -f "$SQLITE_JAR"
  zip -qr -X "$SQLITE_JAR" .
)

# zstd-kmp ships natives for Linux/macOS/Windows on x86_64 and aarch64; keep Linux x86_64 (amd64/*.so) only.
for ZSTD_JAR in "$APPDIR"/usr/lib/app/zstd-kmp-jvm-*.jar; do
  [ -f "$ZSTD_JAR" ] || continue
  zip -q -d "$ZSTD_JAR" 'jni/aarch64/*' 'jni/x86_64/*' 'jni/amd64/*.dll' 'jni/amd64/*.dylib' >/dev/null 2>&1 || true
done

echo "==> AppDir metadata"
cp "$ROOT/packaging/AppRun" "$APPDIR/AppRun"
chmod +x "$APPDIR/AppRun"
cp "$ROOT/packaging/inkoma.desktop" "$APPDIR/inkoma.desktop"
cp "$ROOT/packaging/inkoma.desktop" "$APPDIR/usr/share/applications/inkoma.desktop"
cp "$ROOT/app/src/main/resources/icons/inkoma.png" "$APPDIR/inkoma.png"
cp "$ROOT/app/src/main/resources/icons/inkoma.png" "$APPDIR/usr/share/icons/hicolor/512x512/apps/inkoma.png"
ln -sf inkoma.png "$APPDIR/.DirIcon"

if [ "${SKIP_APPIMAGE:-0}" = "1" ]; then
  echo "==> AppDir ready at $APPDIR (SKIP_APPIMAGE=1)"
  exit 0
fi

echo "==> appimagetool"
TOOL="${APPIMAGETOOL:-$WORK/appimagetool}"
if [ ! -x "$TOOL" ]; then
  curl -fL --retry 4 -o "$TOOL" "$APPIMAGETOOL_URL"
  chmod +x "$TOOL"
fi
OUT="$DIST/Inkoma-$VERSION-x86_64.AppImage"
ARCH=x86_64 APPIMAGE_EXTRACT_AND_RUN=1 "$TOOL" --no-appstream --comp zstd \
  --mksquashfs-opt -Xcompression-level --mksquashfs-opt 19 "$APPDIR" "$OUT"

SIZE=$(stat -c %s "$OUT")
echo "==> $OUT ($((SIZE / 1024 / 1024)) MiB)"
if [ "$SIZE" -gt $((80 * 1024 * 1024)) ]; then
  echo "WARNING: AppImage exceeds 80 MiB" >&2
fi
