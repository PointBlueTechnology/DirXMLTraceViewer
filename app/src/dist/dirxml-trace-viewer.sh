#!/bin/sh
# DirXML Trace Viewer launcher for Linux and macOS.
#
# Environment:
#   JAVA_HOME   Java 21 or later to use (otherwise "java" on the PATH)
#   JAVA_OPTS   JVM options; default -Xmx2g. Raise it to load very large trace files.
#
# Arguments are passed to the viewer, e.g. --demo.

MIN_JAVA=21

# Resolve this script's directory, following symlinks, to find the jar next to it.
script="$0"
while [ -h "$script" ]; do
    link=$(ls -ld "$script" | sed 's/.*-> //')
    case "$link" in
        /*) script="$link" ;;
        *) script="$(dirname "$script")/$link" ;;
    esac
done
dir=$(cd "$(dirname "$script")" && pwd)
jar="$dir/dirxml-trace-viewer.jar"

if [ ! -f "$jar" ]; then
    echo "Cannot find $jar" >&2
    exit 1
fi

if [ -n "$JAVA_HOME" ] && [ -x "$JAVA_HOME/bin/java" ]; then
    java="$JAVA_HOME/bin/java"
elif command -v java >/dev/null 2>&1; then
    java=java
else
    echo "Java $MIN_JAVA or later is required. Install it or set JAVA_HOME." >&2
    exit 1
fi

# "java -version" prints e.g.: openjdk version "21.0.4" 2024-07-16  (or "1.8.0_402" for Java 8)
version=$("$java" -version 2>&1 | sed -n 's/.* version "\([^"]*\)".*/\1/p' | head -n 1)
major=$(echo "$version" | sed 's/^1\.//; s/[^0-9].*//')
if [ -z "$major" ] || [ "$major" -lt "$MIN_JAVA" ]; then
    echo "Java $MIN_JAVA or later is required; found ${version:-unknown} at $java." >&2
    echo "Install a newer Java or point JAVA_HOME at one." >&2
    exit 1
fi

# shellcheck disable=SC2086  # JAVA_OPTS is intentionally word-split
exec "$java" ${JAVA_OPTS:--Xmx2g} --enable-native-access=ALL-UNNAMED -jar "$jar" "$@"
