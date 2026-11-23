#!/usr/bin/env python3
"""Build-time gate for the preview-runtime image.

Renders half a second of the smoke document's first shot through the real handler and Blender,
and exits 1 unless the clip has 12 frames at 496x864 with the subject in frame.

Usage: preview_gate.py [smoke document]
"""

import json
import sys
import tempfile
from pathlib import Path
from types import SimpleNamespace
from typing import Any, cast

from flowcortex_worker.contracts.media_job import MediaJob
from flowcortex_worker.handlers import preview_video
from flowcortex_worker.runtime import ProcessRunner

DEFAULT_DOC = Path(__file__).with_name("preview-smoke-doc.json")
SHOT_ID = "sh1"
DURATION_MS = 500
EXPECTED = {"frames": 12, "width": 496, "height": 864, "subjectInFrame": True}


def main(argv: list[str]) -> int:
    source = Path(argv[1]) if len(argv) > 1 else DEFAULT_DOC
    doc = json.loads(source.read_text(encoding="utf-8"))
    shot = next(shot for shot in doc["story"]["shots"] if shot["id"] == SHOT_ID)
    shot["durationTargetMs"] = DURATION_MS

    with tempfile.TemporaryDirectory(prefix="preview-gate-") as scratch:
        document = Path(scratch) / "doc.json"
        document.write_text(json.dumps(doc), encoding="utf-8")
        job = cast(MediaJob, SimpleNamespace(params={"shotId": SHOT_ID, "aspect": "9:16"}))
        try:
            outcome = preview_video.handle(job, {"doc": document}, ProcessRunner())
        except Exception as failed:
            print(f"preview gate: the handler failed: {failed!r}", file=sys.stderr)
            return 1
        data: dict[str, Any] = outcome.data
        wrong = {key: data.get(key) for key, want in EXPECTED.items() if data.get(key) != want}
        if wrong:
            print(f"preview gate: expected {EXPECTED}, got {wrong}", file=sys.stderr)
            return 1
        print(
            f"preview gate: {data['frames']} frames at {data['width']}x{data['height']} "
            f"on {data['renderer']} with {data['blender']} in {data['renderSec']} s"
        )
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
