"""The font names the code accepts match the font files the image carries."""

from __future__ import annotations

import json
from pathlib import Path
from typing import Any

from flowcortex_worker.captions.fonts import ALLOWED_FONTS

FONTS = Path(__file__).parents[1] / "fonts"
DEBIAN_FAMILIES = ("DejaVu Sans", "Montserrat", "Open Sans", "Quicksand")


def _manifest_families() -> list[dict[str, Any]]:
    manifest = json.loads((FONTS / "manifest.json").read_text(encoding="utf-8"))
    families: list[dict[str, Any]] = manifest["families"]
    return families


def test_allowed_fonts_are_the_debian_families_plus_the_manifest() -> None:
    vendored = [str(entry["family"]) for entry in _manifest_families()]

    missing = sorted(set(vendored) - set(ALLOWED_FONTS))
    extra = sorted(set(ALLOWED_FONTS) - set(vendored) - set(DEBIAN_FAMILIES))

    assert not missing, f"manifest families missing from ALLOWED_FONTS: {missing}"
    assert not extra, f"ALLOWED_FONTS names without a font: {extra}"
    assert len(ALLOWED_FONTS) == len(set(ALLOWED_FONTS))


def test_every_manifest_face_has_a_glyph_advance_table() -> None:
    table = json.loads((FONTS / "glyph-advances.json").read_text(encoding="utf-8"))["fonts"]
    keys = {
        f"{entry['family']}:{weight}"
        for entry in _manifest_families()
        for weight in entry["weights"]
    }

    assert sorted(keys - set(table)) == []
