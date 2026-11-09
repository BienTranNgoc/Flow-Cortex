#!/usr/bin/env python3
"""Build-time font gate for the media-runtime image.

Standard library only: it runs before any Python dependency exists in the stage. It fails the
build (exit 1, every failing family named on stderr) when:

  * a declared family does not resolve to itself through fontconfig (libass would substitute
    another face silently),
  * the bold or regular face of a family lacks a Vietnamese letter,
  * a face's em-per-line differs from the manifest value (or the pinned value of the four
    Debian-packaged families),
  * the Debian families' glyph advances differ from glyph-advances.json,
  * ffmpeg has no `ass` filter,
  * libass renders no filled box for a BorderStyle 3 backdrop line.

Usage: check_fonts.py <path to manifest.json>
"""

import json
import os
import struct
import subprocess
import sys
import tempfile
import unicodedata
from typing import Any

DEBIAN_FAMILIES = ["DejaVu Sans", "Montserrat", "Open Sans", "Quicksand"]

# libass sizes a line at usWinAscent + usWinDescent, so a family's em is
# Fontsize * upem / (winAscent + winDescent). The Debian values are pinned here because the
# packages ship the files; the vendored families carry theirs in the manifest.
DEBIAN_EM_PER_LINE = {
    "DejaVu Sans": 0.8591,
    "Montserrat": 0.7252,
    "Open Sans": 0.7343,
    "Quicksand": 0.6729,
}

_WEIGHT_STYLE = {400: "regular", 700: "bold"}


def required_families(manifest_path: str) -> list[str]:
    with open(manifest_path, encoding="utf-8") as f:
        library = [m["family"] for m in json.load(f)["families"]]
    return DEBIAN_FAMILIES + library


def expected_em_per_line(manifest_path: str) -> dict[str, float]:
    with open(manifest_path, encoding="utf-8") as f:
        library = {m["family"]: m["emPerLine"] for m in json.load(f)["families"]}
    return {**DEBIAN_EM_PER_LINE, **library}


def _sfnt_table_offsets(data: bytes) -> dict[bytes, int]:
    count = struct.unpack(">H", data[4:6])[0]
    offsets: dict[bytes, int] = {}
    for i in range(count):
        tag, _checksum, offset, _length = struct.unpack(">4sIII", data[12 + 16 * i : 28 + 16 * i])
        offsets[tag] = offset
    return offsets


def em_per_line(path: str) -> float:
    """upem / (usWinAscent + usWinDescent) of an sfnt file, read from the head and OS/2 tables."""
    with open(path, "rb") as f:
        data = f.read()
    tables = _sfnt_table_offsets(data)
    head, os2 = tables[b"head"], tables[b"OS/2"]
    upem = struct.unpack(">H", data[head + 18 : head + 20])[0]
    win_ascent, win_descent = struct.unpack(">HH", data[os2 + 74 : os2 + 78])
    return round(upem / (win_ascent + win_descent), 4)


def _cmap_31_offset(data: bytes, cmap_off: int) -> int:
    # Every code point measured (ASCII, Latin-1, general punctuation, Vietnamese) is in the BMP,
    # so the (3, 1) subtable is enough; a font without one cannot be measured.
    _version, num_tables = struct.unpack(">HH", data[cmap_off : cmap_off + 4])
    found = None
    for i in range(num_tables):
        rec = cmap_off + 4 + 8 * i
        platform_id, encoding_id, sub_off = struct.unpack(">HHI", data[rec : rec + 8])
        if (platform_id, encoding_id) == (3, 1):
            found = cmap_off + sub_off
    if found is None:
        raise ValueError("no cmap (3, 1) subtable, glyph advances cannot be measured")
    return found


def _cmap_format4(data: bytes, off: int) -> dict[int, int]:
    """Code point to glyph id from a format 4 cmap subtable."""
    fmt = struct.unpack(">H", data[off : off + 2])[0]
    if fmt != 4:
        raise ValueError(f"cmap (3, 1) is format {fmt}, not 4; glyph advances cannot be measured")
    seg_x2 = struct.unpack(">H", data[off + 6 : off + 8])[0]
    seg_count = seg_x2 // 2
    p = off + 14
    end_codes = struct.unpack(f">{seg_count}H", data[p : p + seg_x2])
    p += seg_x2 + 2  # reservedPad
    start_codes = struct.unpack(f">{seg_count}H", data[p : p + seg_x2])
    p += seg_x2
    id_deltas = struct.unpack(f">{seg_count}h", data[p : p + seg_x2])
    p += seg_x2
    id_range_off_pos = p
    id_range_offsets = struct.unpack(f">{seg_count}H", data[p : p + seg_x2])
    cmap: dict[int, int] = {}
    for i in range(seg_count):
        start, end = start_codes[i], end_codes[i]
        delta, range_off = id_deltas[i], id_range_offsets[i]
        if start == 0xFFFF and end == 0xFFFF:
            continue
        for cp in range(start, end + 1):
            if range_off == 0:
                gid = (cp + delta) & 0xFFFF
            else:
                addr = id_range_off_pos + i * 2 + range_off + (cp - start) * 2
                if addr + 2 > len(data):
                    continue
                gid = struct.unpack(">H", data[addr : addr + 2])[0]
                if gid != 0:
                    gid = (gid + delta) & 0xFFFF
            if gid != 0:
                cmap[cp] = gid
    return cmap


def glyph_advances(path: str, codepoints: list[int]) -> dict[str, Any]:
    """winAscent, winDescent and {code point: advance} read from the sfnt tables.

    Code points the font has no glyph for are left out, as the vendored table does.
    """
    with open(path, "rb") as f:
        data = f.read()
    tables = _sfnt_table_offsets(data)
    os2_off = tables[b"OS/2"]
    win_ascent, win_descent = struct.unpack(">HH", data[os2_off + 74 : os2_off + 78])
    cmap = _cmap_format4(data, _cmap_31_offset(data, tables[b"cmap"]))
    hhea = tables[b"hhea"]
    num_h_metrics = struct.unpack(">H", data[hhea + 34 : hhea + 36])[0]
    hmtx_off = tables[b"hmtx"]
    widths = [
        struct.unpack(">H", data[hmtx_off + 4 * i : hmtx_off + 4 * i + 2])[0]
        for i in range(num_h_metrics)
    ]
    advances: dict[str, int] = {}
    for cp in codepoints:
        gid = cmap.get(cp)
        if gid is not None:
            advances[str(cp)] = widths[gid] if gid < num_h_metrics else widths[-1]
    return {"winAscent": win_ascent, "winDescent": win_descent, "advances": advances}


def _vn_codepoints() -> list[int]:
    """Every precomposed (NFC) Vietnamese letter, upper and lower case: 146 code points.

    Generated, not sampled: a family can have one tone mark of a vowel and miss another.
    """
    out: set[int] = set()
    for base in "aăâeêioôơuưy":
        for tone in ("", "̀", "́", "̃", "̉", "̣"):
            for ch in (base, base.upper()):
                c = unicodedata.normalize("NFC", ch + tone)
                if len(c) == 1:
                    out.add(ord(c))
    out |= {ord("đ"), ord("Đ")}
    return sorted(out)


VN_CODEPOINTS = _vn_codepoints()

# Must be the set recorded in glyph-advances.json; a mismatch fails the build rather than
# silently measuring a narrower range.
ADVANCE_CODEPOINTS = sorted(
    set(range(0x20, 0x7F))
    | set(range(0xA0, 0xC0))
    | set(range(0x2000, 0x2070))
    | set(VN_CODEPOINTS)
)


def _run(args: list[str]) -> str:
    done = subprocess.run(args, capture_output=True, text=True, timeout=30, check=False)
    return done.stdout.strip()


def _charset_has(charset_tokens: list[str], cp: int) -> bool:
    """fc-query prints the charset as hex ranges: '20-7e a0-17f 1e00-1eff'."""
    for tok in charset_tokens:
        if "-" in tok:
            lo, hi = tok.split("-", 1)
            if int(lo, 16) <= cp <= int(hi, 16):
                return True
        elif tok and int(tok, 16) == cp:
            return True
    return False


def check_glyph_advances(manifest_path: str) -> list[str]:
    """Re-measure the Debian families' advances in the resolved files, against the table."""
    ga_path = os.path.join(os.path.dirname(manifest_path), "glyph-advances.json")
    if not os.path.exists(ga_path):
        return [f"glyph-advances.json is missing next to {manifest_path}"]
    with open(ga_path, encoding="utf-8") as f:
        committed = json.load(f)
    codepoints = committed["codepoints"]
    problems: list[str] = []
    if sorted(codepoints) != ADVANCE_CODEPOINTS:
        missing = sorted(set(ADVANCE_CODEPOINTS) - set(codepoints))
        extra = sorted(set(codepoints) - set(ADVANCE_CODEPOINTS))
        problems.append(
            f"glyph-advances.json code points ({len(codepoints)}) differ from check_fonts.py "
            f"({len(ADVANCE_CODEPOINTS)}): {len(missing)} missing, {len(extra)} extra"
        )
    for family in DEBIAN_FAMILIES:
        for weight, style in _WEIGHT_STYLE.items():
            key = f"{family}:{weight}"
            want = committed["fonts"].get(key)
            if want is None:
                problems.append(f"{key}: not in glyph-advances.json")
                continue
            path = _run(["fc-match", "-f", "%{file}", f"{family}:{style}"])
            if not path:
                problems.append(f"{key}: fc-match returned no file")
                continue
            got = glyph_advances(path, codepoints)
            if got != want:
                mismatched = sorted(
                    k for k in want["advances"] if got["advances"].get(k) != want["advances"][k]
                )
                problems.append(
                    f"{key}: advances measured in {path} differ from glyph-advances.json "
                    f"(ascent/descent {got['winAscent']}/{got['winDescent']} against "
                    f"{want['winAscent']}/{want['winDescent']}, {len(mismatched)} code points "
                    f"differ: {', '.join(mismatched[:8])})"
                )
    return problems


def check_family(family: str, style: str, em_expected: float | None) -> list[str]:
    """`style` is the face libass asks for: bold for Bold=-1, regular for captions without it."""
    path = _run(["fc-match", "-f", "%{file}", f"{family}:{style}"])
    if not path:
        return [f"{family}: fc-match returned no file (fontconfig broken?)"]

    got = _run(["fc-query", "-f", "%{family[0]}", path])
    if got != family:
        return [
            f"{family}: '{family}:{style}' resolves to family '{got}' ({path}); "
            "the image lacks the declared font and libass would substitute another"
        ]

    problems: list[str] = []
    if em_expected is not None and em_per_line(path) != em_expected:
        problems.append(
            f"{family}: {style} face {path} has em-per-line {em_per_line(path)}, "
            f"expected {em_expected}"
        )

    tokens = _run(["fc-query", "-f", "%{charset}", path]).split()
    missing = [cp for cp in VN_CODEPOINTS if not _charset_has(tokens, cp)]
    if missing:
        shown = " ".join(f"{chr(cp)}(U+{cp:04X})" for cp in missing[:12])
        more = f" and {len(missing) - 12} more" if len(missing) > 12 else ""
        problems.append(
            f"{family}: {style} face {path} lacks {len(missing)}/{len(VN_CODEPOINTS)} "
            f"Vietnamese letters: {shown}{more}"
        )
    else:
        n = len(VN_CODEPOINTS)
        print(f"OK: '{family}' {style} = {path}, {n}/{n} Vietnamese letters")
    return problems


def check_ass_filter() -> list[str]:
    out = subprocess.run(
        ["ffmpeg", "-hide_banner", "-filters"],
        capture_output=True,
        text=True,
        timeout=30,
        check=False,
    ).stdout
    if any(line.split()[1:2] == ["ass"] for line in out.splitlines()):
        print("OK: ffmpeg has the ass filter")
        return []
    return ["ffmpeg has no `ass` filter; captions cannot be burned"]


# Same Style layout as captions/ass.py emits for a backdrop: BorderStyle 3, the box drawn in
# OutlineColour (white), no shadow. The text is black and the spaces draw nothing, so a white
# pixel at the middle of the gap exists only when a filled box sits behind the line.
_BOX_W, _BOX_H = 640, 360
_BOX_GAP = "\\h" * 14
_BOX_ASS = f"""[Script Info]
ScriptType: v4.00+
PlayResX: {_BOX_W}
PlayResY: {_BOX_H}

[V4+ Styles]
Format: Name,Fontname,Fontsize,PrimaryColour,SecondaryColour,OutlineColour,BackColour,\
Bold,BorderStyle,Outline,Shadow,Alignment,MarginV
Style: K,DejaVu Sans,40,&H00000000,&H00000000,&H00FFFFFF,&HFF000000,-1,3,6,0,2,60

[Events]
Format: Layer,Start,End,Style,Text
Dialogue: 0,0:00:00.00,0:00:02.00,K,H{_BOX_GAP}H
"""


def check_backdrop_box() -> list[str]:
    with tempfile.TemporaryDirectory() as tmp:
        script = os.path.join(tmp, "box.ass")
        with open(script, "w", encoding="utf-8") as f:
            f.write(_BOX_ASS)
        run = subprocess.run(
            [
                "ffmpeg",
                "-hide_banner",
                "-loglevel",
                "error",
                "-f",
                "lavfi",
                "-i",
                f"color=c=black:s={_BOX_W}x{_BOX_H}:d=1",
                "-vf",
                f"ass={script}",
                "-frames:v",
                "1",
                "-f",
                "rawvideo",
                "-pix_fmt",
                "gray",
                "-",
            ],
            capture_output=True,
            timeout=60,
            check=False,
        )
    frame = run.stdout
    if run.returncode != 0 or len(frame) != _BOX_W * _BOX_H:
        return [f"backdrop frame did not render: {run.stderr.decode(errors='replace').strip()}"]
    column = [frame[y * _BOX_W + _BOX_W // 2] for y in range(_BOX_H)]
    lit = sum(1 for v in column if v > 200)
    # A box is at least the 40 px line plus its pad; an outline ring or no box leaves the gap dark.
    if lit < 40 or column[2] > 16:
        return [
            f"BorderStyle 3 drew no filled box ({lit} lit pixels in the gap column, "
            f"background {column[2]}); backdrops would not render"
        ]
    print(f"OK: libass draws the backdrop box ({lit} px tall at the line centre)")
    return []


def main() -> int:
    if len(sys.argv) != 2:
        print("usage: check_fonts.py <manifest.json>", file=sys.stderr)
        return 2
    manifest = sys.argv[1]
    em = expected_em_per_line(manifest)
    problems: list[str] = []
    for fam in required_families(manifest):
        problems.extend(check_family(fam, "bold", em[fam]))
        problems.extend(check_family(fam, "regular", em[fam]))
    problems.extend(check_glyph_advances(manifest))
    ass_problems = check_ass_filter()
    problems.extend(ass_problems)
    if not ass_problems:
        problems.extend(check_backdrop_box())
    if problems:
        for p in problems:
            print(f"FATAL: {p}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
