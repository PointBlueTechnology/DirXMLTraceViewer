#!/bin/sh
# Double-click in Finder to start the DirXML Trace Viewer (macOS).
# The Terminal window that opens shows any startup errors; it can be closed once the viewer is up.
exec "$(dirname "$0")/dirxml-trace-viewer.sh" "$@"
