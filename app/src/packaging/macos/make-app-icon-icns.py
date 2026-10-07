#!/usr/bin/env python3
"""Pack the app PNGs into a macOS .icns for jpackage --icon.

Finder shows the icon of a jpackage .app, not the window icons embedded in
the jar. On macOS, jpackage accepts only an .icns file (it ignores a PNG and
falls back to the default Java icon). This script wraps the existing PNGs in
an Apple icon container. It does not resize, recompress, or rewrite pixels.

Source artwork (unchanged):

    src/main/resources/icons/app-<pixels>.png

Slots, using the OSTypes macOS iconutil writes for those pixel sizes:

    16  -> icp4              16x16
    32  -> icp5              32x32
    32  -> ic11              16x16@2x
    64  -> ic12              32x32@2x
    128 -> ic07              128x128
    256 -> ic08              256x256
    256 -> ic13              128x128@2x
    512 -> ic09              512x512
    512 -> ic14              256x256@2x

icp6 is 48x48, and ic10 is 1024x1024 (512x512@2x). Neither size is in the
PNG set, so those slots are left out rather than scaling the artwork.
Where one PNG fills two slots, the same bytes are stored twice.

Regenerate from the repository root (or anywhere):

    python3 src/packaging/macos/make-app-icon-icns.py

Writes src/packaging/macos/DirXMLTraceViewer.icns next to this script.
macOS iconutil is not required.
"""

from __future__ import annotations

import struct
import sys
from pathlib import Path

# (pixel size, icns OSType). Order is the order of elements in the file.
SLOTS = (
    (16, "icp4"),
    (32, "icp5"),
    (32, "ic11"),
    (64, "ic12"),
    (128, "ic07"),
    (256, "ic08"),
    (256, "ic13"),
    (512, "ic09"),
    (512, "ic14"),
)

PNG_MAGIC = b"\x89PNG\r\n\x1a\n"
SCRIPT_DIR = Path(__file__).resolve().parent
ICON_DIR = SCRIPT_DIR.parents[1] / "main" / "resources" / "icons"
OUT = SCRIPT_DIR / "DirXMLTraceViewer.icns"


def png_size(data: bytes, label: str) -> tuple[int, int]:
    if not data.startswith(PNG_MAGIC):
        raise SystemExit(f"{label} is not a PNG")
    if len(data) < 24:
        raise SystemExit(f"{label} is too short to be a PNG")
    length, chunk_type = struct.unpack(">I4s", data[8:16])
    if chunk_type != b"IHDR" or length < 8:
        raise SystemExit(f"{label} has no IHDR")
    return struct.unpack(">II", data[16:24])


def pack(icons: dict[int, bytes]) -> bytes:
    elements = bytearray()
    for size, ostype in SLOTS:
        data = icons[size]
        if len(ostype) != 4:
            raise SystemExit(f"bad OSType {ostype!r}")
        elements += ostype.encode("ascii")
        elements += struct.pack(">I", 8 + len(data))
        elements += data
    return b"icns" + struct.pack(">I", 8 + len(elements)) + elements


def parse(blob: bytes) -> list[tuple[str, bytes]]:
    if len(blob) < 8 or blob[:4] != b"icns":
        raise SystemExit("output is not an icns file")
    declared = struct.unpack(">I", blob[4:8])[0]
    if declared != len(blob):
        raise SystemExit(f"icns size header {declared} != {len(blob)}")
    offset = 8
    found = []
    while offset < len(blob):
        if offset + 8 > len(blob):
            raise SystemExit("truncated icns element header")
        ostype = blob[offset:offset + 4].decode("ascii")
        size = struct.unpack(">I", blob[offset + 4:offset + 8])[0]
        if size < 8 or offset + size > len(blob):
            raise SystemExit(f"bad element size for {ostype}")
        found.append((ostype, blob[offset + 8:offset + size]))
        offset += size
    if offset != len(blob):
        raise SystemExit("icns elements do not fill the file")
    return found


def main() -> None:
    icons: dict[int, bytes] = {}
    for size, _ostype in SLOTS:
        if size in icons:
            continue
        path = ICON_DIR / f"app-{size}.png"
        data = path.read_bytes()
        width, height = png_size(data, path.name)
        if (width, height) != (size, size):
            raise SystemExit(f"{path.name} is {width}x{height}, expected {size}x{size}")
        icons[size] = data

    blob = pack(icons)
    found = parse(blob)
    expected = [(ostype, icons[size]) for size, ostype in SLOTS]
    if found != expected:
        raise SystemExit("packed icns does not round-trip")

    OUT.write_bytes(blob)
    print(f"Wrote {OUT.relative_to(SCRIPT_DIR.parents[2])} ({len(blob)} bytes)")
    for ostype, data in found:
        width, height = png_size(data, ostype)
        print(f"  {ostype}  {width}x{height}  {len(data)} bytes")


if __name__ == "__main__":
    try:
        main()
    except BrokenPipeError:
        sys.exit(0)
