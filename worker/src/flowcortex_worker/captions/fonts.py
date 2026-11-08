"""Font names the caption burn may use, and the check of what libass actually drew with."""

from __future__ import annotations

import re
from collections.abc import Callable
from pathlib import Path

ASS_FONT_NAME = "DejaVu Sans"
FONT_LIBRARY_DIR = Path("/usr/share/fonts/truetype/flowcortex")

# Closed list: a family outside the image is silently replaced by libass.
ALLOWED_FONTS: tuple[str, ...] = (
    "DejaVu Sans",
    "Montserrat",
    "Open Sans",
    "Quicksand",
    "Be Vietnam Pro",
    "Roboto",
    "Inter",
    "Nunito",
    "Mulish",
    "Barlow",
    "Oswald",
    "Anton",
    "Bungee",
    "Paytone One",
    "Chakra Petch",
    "Pacifico",
    "Lobster",
    "Charm",
    "Patrick Hand",
    "Itim",
    "Mali",
    "Pangolin",
    "Sriracha",
    "Baloo 2",
    "Tilt Neon",
    "Neonderthaw",
    "Grenze Gotisch",
    "Sedgwick Ave Display",
    "Bangers",
    "Bungee Inline",
    "Bungee Shade",
    "Kablammo",
)

# libass prints its font choice only at -loglevel verbose. It names the PostScript face, not the
# family, so the family is looked up again from the file. The "failed to find any fallback" line
# has no "(" after "fontselect:" and does not match.
_FONTSELECT_RE = re.compile(r"fontselect:\s*\(([^,]+),[^)]*\)\s*->\s*([^,]+),")
_GLYPH_MISS_RE = re.compile(r"Glyph 0x([0-9A-Fa-f]+) not found")

# Latin Extended-A/B, Latin Extended Additional and the combining marks of NFD input. A missing
# glyph in these ranges renders a box in Vietnamese text and fails the job; elsewhere (emoji, CJK)
# it is only reported.
_VN_RANGES = ((0x00C0, 0x017F), (0x01A0, 0x01B0), (0x0300, 0x0323), (0x1EA0, 0x1EF9))


def audit_libass(stderr: str, font: str, family_of: Callable[[str], str]) -> list[str]:
    """Return non-fatal warnings from libass's log; raise `ValueError` on substitution or a
    missing Vietnamese glyph. `family_of` maps a font file path to its family name."""
    # The fontselect line that follows a missing-glyph line names the fallback libass picked for
    # that glyph, with the declared family still as the requested one: it is not a substitution.
    after_glyph_miss = False
    for line in stderr.splitlines():
        if _GLYPH_MISS_RE.search(line):
            after_glyph_miss = True
            continue
        selected = _FONTSELECT_RE.search(line)
        if selected is None:
            continue
        if after_glyph_miss:
            after_glyph_miss = False
            continue
        wanted, path = selected.group(1).strip(), selected.group(2).strip()
        got = family_of(path)
        if got and got.lower() != wanted.lower():
            raise ValueError(
                f"font substituted: the ASS asked for '{wanted}' but libass drew with "
                f"'{got}' ({path}); the image lacks the declared family"
            )

    missing = sorted({int(cp, 16) for cp in _GLYPH_MISS_RE.findall(stderr)})
    vietnamese = [cp for cp in missing if any(lo <= cp <= hi for lo, hi in _VN_RANGES)]
    if vietnamese:
        detail = " ".join(f"{chr(cp)}(U+{cp:04X})" for cp in vietnamese)
        raise ValueError(
            f"font '{font}' lacks {len(vietnamese)} Vietnamese characters: {detail}; "
            "the caption would render boxes"
        )
    if missing:
        return [
            "glyphs outside the Vietnamese alphabet are missing (emoji or rare symbols) and "
            "libass draws them blank: " + " ".join(f"U+{cp:04X}" for cp in missing)
        ]
    return []
