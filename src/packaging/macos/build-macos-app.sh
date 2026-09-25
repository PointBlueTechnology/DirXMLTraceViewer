#!/bin/sh
# Builds a signed, notarized macOS app and DMG with its own Java runtime, so users need neither
# Java nor admin rights: they can copy the app anywhere (e.g. ~/Applications) and open it.
#
# Run from the project root after "mvn package". Environment:
#   SIGN_IDENTITY   Developer ID Application identity, e.g.
#                   "Developer ID Application: Jane Doe (TEAMID1234)"   (required)
#   NOTARY_PROFILE  notarytool keychain profile (xcrun notarytool store-credentials); if unset the
#                   app and DMG are signed but not notarized
#   JAVA21_HOME     JDK 21 used for jlink/jpackage and bundled as the runtime
#                   (default: /usr/libexec/java_home -v 21)
#
# Output: target/macos/DirXML Trace Viewer.app and target/macos/DirXML-Trace-Viewer-<version>-<arch>.dmg
# The build is for the architecture of the JDK used (arm64 on Apple silicon, x86_64 on Intel).

set -eu

APP_NAME="DirXML Trace Viewer"
BUNDLE_ID="com.pointbluetech.dirxmltraceviewer"
JAR=target/dirxml-trace-viewer.jar
OUT=target/macos
MODULES=java.base,java.desktop,java.logging,java.prefs,jdk.crypto.ec

: "${SIGN_IDENTITY:?Set SIGN_IDENTITY to your Developer ID Application identity}"
JAVA21_HOME=${JAVA21_HOME:-$(/usr/libexec/java_home -v 21)}
[ -f "$JAR" ] || { echo "Run mvn package first ($JAR not found)" >&2; exit 1; }

# jpackage needs a plain numeric version: 1.1.0-SNAPSHOT -> 1.1.0
VERSION=$(unzip -p "$JAR" META-INF/MANIFEST.MF | sed -n 's/^Implementation-Version: *\([0-9.]*\).*/\1/p' | tr -d '\r')
ARCH=$(file "$JAVA21_HOME/bin/java" | grep -q arm64 && echo arm64 || echo x86_64)
DMG="$OUT/DirXML-Trace-Viewer-$VERSION-$ARCH.dmg"
echo "Building $APP_NAME $VERSION for $ARCH with $JAVA21_HOME"

rm -rf "$OUT"
mkdir -p "$OUT/input" "$OUT/work"

# FlatLaf ships its macOS libraries signed with its own (non-Apple) certificate, which notarization
# rejects, even inside a jar. Re-sign them with our Developer ID in the app's copy of the jar.
cp "$JAR" "$OUT/input/"
(
    cd "$OUT/work"
    unzip -q "../input/$(basename "$JAR")" 'com/formdev/flatlaf/natives/*macos*'
    for lib in com/formdev/flatlaf/natives/*macos*.dylib; do
        codesign --force --timestamp --options runtime --sign "$SIGN_IDENTITY" "$lib"
    done
    zip -q "../input/$(basename "$JAR")" com/formdev/flatlaf/natives/*macos*.dylib
)

"$JAVA21_HOME/bin/jlink" --add-modules "$MODULES" --strip-debug --no-header-files --no-man-pages \
    --output "$OUT/runtime"

"$JAVA21_HOME/bin/jpackage" --type app-image --dest "$OUT" \
    --name "$APP_NAME" --app-version "$VERSION" \
    --vendor "Point Blue Technology" --copyright "Copyright (c) 2026 Point Blue Technology" \
    --description "Viewer for NetIQ / OpenText Identity Manager driver trace" \
    --input "$OUT/input" --main-jar "$(basename "$JAR")" \
    --runtime-image "$OUT/runtime" \
    --java-options "-Xmx2g" --java-options "--enable-native-access=ALL-UNNAMED" \
    --mac-package-identifier "$BUNDLE_ID" --mac-package-name "DirXML Trace" \
    --mac-sign --mac-signing-key-user-name "${SIGN_IDENTITY#Developer ID Application: }"

APP="$OUT/$APP_NAME.app"
codesign --verify --deep --strict --verbose=2 "$APP"

notarize() {
    if [ -n "${NOTARY_PROFILE:-}" ]; then
        xcrun notarytool submit "$1" --keychain-profile "$NOTARY_PROFILE" --wait
    else
        echo "NOTARY_PROFILE not set: skipping notarization of $1"
        return 1
    fi
}

# Notarize and staple the app itself, so it also passes Gatekeeper offline once copied out of the DMG.
ditto -c -k --keepParent "$APP" "$OUT/app.zip"
if notarize "$OUT/app.zip"; then
    xcrun stapler staple "$APP"
    NOTARIZED=1
else
    NOTARIZED=0
fi
rm -f "$OUT/app.zip"

# DMG with the app and a shortcut to Applications. Users without admin rights can drag the app to
# ~/Applications, the Desktop, or anywhere else instead.
mkdir -p "$OUT/dmg"
ditto "$APP" "$OUT/dmg/$APP_NAME.app"
ln -s /Applications "$OUT/dmg/Applications"
hdiutil create -volname "$APP_NAME" -srcfolder "$OUT/dmg" -ov -format UDZO "$DMG" >/dev/null
codesign --timestamp --sign "$SIGN_IDENTITY" "$DMG"
if [ "$NOTARIZED" = 1 ] && notarize "$DMG"; then
    xcrun stapler staple "$DMG"
fi
rm -rf "$OUT/dmg" "$OUT/work" "$OUT/input" "$OUT/runtime"

echo
spctl --assess --type execute --verbose=2 "$APP" || true
echo "Built: $APP"
echo "Built: $DMG"
