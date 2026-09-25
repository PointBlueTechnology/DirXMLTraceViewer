# Releasing

How a DirXML Trace Viewer release is built, signed, notarized and published. The examples release
version `1.2.0` from `1.2.0-SNAPSHOT`; substitute your versions.

Each release has four assets:

| Asset | Built by |
|---|---|
| `DirXML-Trace-Viewer-<version>-arm64.dmg` | `src/packaging/macos/build-macos-app.sh` (macOS only) |
| `dirxml-trace-viewer-<version>.zip` | `mvn package` |
| `dirxml-trace-viewer.jar` | `mvn package` |
| `SHA256SUMS` | `shasum` over the three files above |

## One-time setup (macOS)

You need these on the Mac that builds the DMG.

1. **JDK 21** for `jlink`/`jpackage`; it is also bundled into the app as its runtime. On Jerry's
   Mac this is Azul Zulu 21:
   `/Users/jcombs/Library/Java/JavaVirtualMachines/azul-21.0.8/Contents/Home`.
   The DMG is built for the JDK's architecture (arm64 here); an Intel build needs an x86_64 JDK.
   Maven itself can run on any JDK 21 or later.
2. **Developer ID Application certificate** in the login keychain. Check with:
   ```sh
   security find-identity -v -p codesigning | grep "Developer ID Application"
   ```
   The identity used for releases is `Developer ID Application: Jerry COMBS (76TU99ENEP)`
   (team `76TU99ENEP`). Create one at https://developer.apple.com/account/resources/certificates/add
   if needed.
3. **Notarization credentials** stored in the keychain as a `notarytool` profile. Releases use the
   profile `dirxml-notary`, for Apple ID `ldapman@mac.com` and team `76TU99ENEP`. It was created
   once with:
   ```sh
   xcrun notarytool store-credentials dirxml-notary --apple-id ldapman@mac.com --team-id 76TU99ENEP
   ```
   and, at the prompt, an **app-specific password** generated for that Apple ID at
   https://account.apple.com → Sign-In and Security → App-Specific Passwords (not the Apple ID
   password). Run it in Terminal: the password prompt is interactive. Check the profile works with:
   ```sh
   xcrun notarytool history --keychain-profile dirxml-notary
   ```
   Note: the App Store Connect API key used by the `asc` CLI on this Mac does not work for
   notarization ("Illegal scope /notary/v2"); use the `notarytool` profile.
4. **GitHub CLI** (`gh`) logged in with rights to create releases in
   `PointBlueTechnology/DirXMLTraceViewer`.

## Steps

Run everything from the project root, on an up-to-date `main` with a clean working tree.

### 1. Set the release version and commit it

```sh
git pull --ff-only
mvn -q versions:set -DnewVersion=1.2.0 -DgenerateBackupPoms=false
git commit -am "Release 1.2.0"
```

### 2. Build and test the jar and zip

```sh
mvn clean package
```

All tests must pass. This writes `target/dirxml-trace-viewer.jar` and
`target/dirxml-trace-viewer-1.2.0.zip`. Check the version and, optionally, that the zip starts:

```sh
unzip -p target/dirxml-trace-viewer.jar META-INF/MANIFEST.MF | grep Implementation-Version   # 1.2.0
unzip -q target/dirxml-trace-viewer-1.2.0.zip -d /tmp/rel && /tmp/rel/dirxml-trace-viewer-1.2.0/dirxml-trace-viewer.sh --demo
```

### 3. Tag and push

```sh
git tag -a v1.2.0 -m "DirXML Trace Viewer 1.2.0"
git push origin main v1.2.0
```

### 4. Build, sign and notarize the Mac app and DMG

```sh
SIGN_IDENTITY="Developer ID Application: Jerry COMBS (76TU99ENEP)" \
NOTARY_PROFILE=dirxml-notary \
JAVA21_HOME=/Users/jcombs/Library/Java/JavaVirtualMachines/azul-21.0.8/Contents/Home \
src/packaging/macos/build-macos-app.sh
```

| Variable | Required | Meaning |
|---|---|---|
| `SIGN_IDENTITY` | yes | The Developer ID Application identity above |
| `NOTARY_PROFILE` | for a release | The `notarytool` keychain profile; without it the app is signed but not notarized |
| `JAVA21_HOME` | no | JDK 21 to bundle; defaults to `/usr/libexec/java_home -v 21` |

The script uses `target/dirxml-trace-viewer.jar`, so run it after step 2. It:

1. re-signs FlatLaf's macOS native libraries inside the app's copy of the jar with the Developer ID
   (they carry FlatLaf's own signature, which notarization rejects);
2. builds a trimmed Java 21 runtime with `jlink` and the app with `jpackage`, signed with hardened
   runtime;
3. notarizes the app, waits for the result and staples it;
4. builds the DMG, signs it, notarizes it and staples it.

It writes `target/macos/DirXML Trace Viewer.app` and
`target/macos/DirXML-Trace-Viewer-1.2.0-arm64.dmg`. Notarization usually takes a few minutes per
submission.

To build the DMG for a release that is already tagged (e.g. from another checkout), use a separate
worktree so nothing else changes:

```sh
git worktree add /tmp/rel-1.2.0 v1.2.0
cd /tmp/rel-1.2.0 && mvn clean package && SIGN_IDENTITY=… NOTARY_PROFILE=dirxml-notary src/packaging/macos/build-macos-app.sh
```

### 5. Check the notarization result

The script output must show, for both submissions:

```
status: Accepted
The staple and validate action worked!
```

and end with:

```
target/macos/DirXML Trace Viewer.app: accepted
source=Notarized Developer ID
```

`rejected` / `source=Unnotarized Developer ID` means notarization was skipped or failed. For a
failed submission, read Apple's log (the submission ID is in the script output):

```sh
xcrun notarytool log <submission-id> --keychain-profile dirxml-notary
```

To check the DMG the way a downloaded copy is checked, mark it quarantined and assess it:

```sh
cp target/macos/DirXML-Trace-Viewer-1.2.0-arm64.dmg /tmp/check.dmg
xattr -w com.apple.quarantine "0081;$(printf %x $(date +%s));Safari;" /tmp/check.dmg
spctl --assess --type open --context context:primary-signature --verbose=2 /tmp/check.dmg
# → accepted, source=Notarized Developer ID
```

### 6. Generate SHA256SUMS

```sh
mkdir -p /tmp/release-1.2.0 && cd /tmp/release-1.2.0
cp ~/path/to/DirXMLTraceViewer/target/macos/DirXML-Trace-Viewer-1.2.0-arm64.dmg \
   ~/path/to/DirXMLTraceViewer/target/dirxml-trace-viewer-1.2.0.zip \
   ~/path/to/DirXMLTraceViewer/target/dirxml-trace-viewer.jar .
shasum -a 256 DirXML-Trace-Viewer-1.2.0-arm64.dmg dirxml-trace-viewer-1.2.0.zip dirxml-trace-viewer.jar > SHA256SUMS
```

Keep the file names without paths, so `shasum -a 256 -c SHA256SUMS` works in a download folder.

### 7. Create the GitHub release

Write the release notes (what changed, and a download table) to a file, then:

```sh
gh release create v1.2.0 \
    DirXML-Trace-Viewer-1.2.0-arm64.dmg dirxml-trace-viewer-1.2.0.zip dirxml-trace-viewer.jar SHA256SUMS \
    --repo PointBlueTechnology/DirXMLTraceViewer \
    --title "DirXML Trace Viewer 1.2.0" --notes-file notes.md --verify-tag --latest
```

If the DMG is added to an existing release later, upload it and replace `SHA256SUMS` with a copy
that has the DMG's line appended (keep the existing lines):

```sh
gh release download v1.2.0 --repo PointBlueTechnology/DirXMLTraceViewer --pattern SHA256SUMS
shasum -a 256 DirXML-Trace-Viewer-1.2.0-arm64.dmg >> SHA256SUMS
gh release upload v1.2.0 DirXML-Trace-Viewer-1.2.0-arm64.dmg SHA256SUMS --clobber --repo PointBlueTechnology/DirXMLTraceViewer
```

Verify what was published:

```sh
mkdir /tmp/dl && cd /tmp/dl
gh release download v1.2.0 --repo PointBlueTechnology/DirXMLTraceViewer
shasum -a 256 -c SHA256SUMS
```

### 8. Start the next development version

```sh
mvn -q versions:set -DnewVersion=1.3.0-SNAPSHOT -DgenerateBackupPoms=false
git commit -am "Start 1.3.0 development"
git push origin main
```

## Notes

- Never commit or paste the app-specific password or other credentials; refer only to the keychain
  profile name.
- The running app's update check compares its version with the latest GitHub release, so publish
  releases as "latest" (not draft or prerelease) and tag them `vX.Y.Z`.
- The jar and zip include `LICENSE` and `THIRD-PARTY-NOTICES.txt`; keep the notices current when
  dependencies change.
