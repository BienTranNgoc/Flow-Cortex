"""preview_video: job refusals, outputs, failure mapping and registration, with a fake Blender."""

from __future__ import annotations

import json
import logging
import sys
from pathlib import Path
from types import SimpleNamespace
from typing import Any, cast

import pytest

from flowcortex_worker.errors import PermanentError, TransientError
from flowcortex_worker.handlers import preview_video, registry
from flowcortex_worker.preview import build_card, dumps, send_card
from flowcortex_worker.runtime import ProcessRunner

FIXTURES = Path(__file__).parent / "fixtures" / "preview"

FAKE_BLENDER = """#!{python}
import json, os, signal, sys
args = sys.argv[sys.argv.index("--") + 1:]
card_path, out, font = args
with open(os.environ["FAKE_BLENDER_LOG"], "a") as log:
    log.write(json.dumps(sys.argv) + "\\n")
mode = os.environ.get("FAKE_BLENDER", "ok")
if mode == "crash":
    for number in range(40):
        print("line", number)
    sys.exit(1)
if mode == "killed":
    print("about to be killed", flush=True)
    os.kill(os.getpid(), signal.SIGKILL)
card = json.load(open(card_path, encoding="utf-8"))
frame = card["frame"]
open(out, "wb").write(b"\\0" * 64)
if mode == "no_report":
    sys.exit(0)
report = {{
    "frames": frame["frameCount"],
    "width": frame["width"],
    "height": frame["height"],
    "subjectInFrame": mode != "out_of_frame",
    "checks": [],
    "blender": "4.5.14 LTS",
    "renderer": "llvmpipe (LLVM 15.0.7, 256 bits)",
    "seconds": {{"build": 0.5, "render": 2.0}},
}}
json.dump(report, open(out + ".report.json", "w"))
"""


@pytest.fixture
def blender_log(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> Path:
    fake = tmp_path / "bin" / "blender"
    fake.parent.mkdir()
    fake.write_text(FAKE_BLENDER.format(python=sys.executable), encoding="utf-8")
    fake.chmod(0o755)
    log = tmp_path / "invocations.log"
    monkeypatch.setenv("BLENDER_BIN", str(fake))
    monkeypatch.setenv("PREVIEW_SCENE_SCRIPT", str(tmp_path / "scene.py"))
    monkeypatch.setenv("PREVIEW_FONT", str(tmp_path / "font.ttf"))
    monkeypatch.setenv("FAKE_BLENDER_LOG", str(log))
    monkeypatch.delenv("FAKE_BLENDER", raising=False)
    return log


def invocations(log: Path) -> list[list[str]]:
    return [json.loads(line) for line in log.read_text().splitlines()] if log.exists() else []


def document() -> dict[str, Any]:
    return cast(dict[str, Any], json.loads((FIXTURES / "minh-thu.json").read_text("utf-8")))


def stage_inputs(tmp_path: Path, doc: Any = None) -> dict[str, Path]:
    scratch = tmp_path / "scratch"
    scratch.mkdir(exist_ok=True)
    path = scratch / "doc"
    path.write_text(json.dumps(document() if doc is None else doc), encoding="utf-8")
    return {"doc": path}


def job(**params: Any) -> Any:
    merged: dict[str, Any] = {"shotId": "sh1", "aspect": "9:16"}
    merged.update(params)
    return SimpleNamespace(params={k: v for k, v in merged.items() if v is not None})


def run(tmp_path: Path, doc: Any = None, **params: Any) -> Any:
    return preview_video.handle(job(**params), stage_inputs(tmp_path, doc), ProcessRunner())


@pytest.mark.parametrize(
    ("params", "needle"),
    [
        ({"shotId": None}, "shotId"),
        ({"shotId": ""}, "shotId"),
        ({"shotId": 7}, "shotId"),
        ({"aspect": None}, "9:16"),
        ({"aspect": "4:5"}, "16:9"),
        ({"send": "yes"}, "send"),
        ({"extra": 1}, "extra"),
    ],
)
def test_job_params_are_refused_before_blender_starts(
    tmp_path: Path, blender_log: Path, params: dict[str, Any], needle: str
) -> None:
    with pytest.raises(PermanentError, match=needle) as refused:
        run(tmp_path, **params)
    assert refused.value.code == "contract_violation"
    assert invocations(blender_log) == []


def test_an_input_set_other_than_doc_is_refused(tmp_path: Path, blender_log: Path) -> None:
    inputs = stage_inputs(tmp_path)
    for bad in ({**inputs, "media": inputs["doc"]}, {"media": inputs["doc"]}, {}):
        with pytest.raises(PermanentError, match="input") as refused:
            preview_video.handle(job(), bad, ProcessRunner())
        assert refused.value.code == "contract_violation"
    assert invocations(blender_log) == []


def test_extra_input_is_named(tmp_path: Path, blender_log: Path) -> None:
    inputs = stage_inputs(tmp_path)
    with pytest.raises(PermanentError, match="media"):
        preview_video.handle(job(), {**inputs, "media": inputs["doc"]}, ProcessRunner())


def test_a_document_that_is_not_an_object_is_refused(tmp_path: Path, blender_log: Path) -> None:
    wrong: list[Any] = [[], "text", 3]
    for doc in wrong:
        with pytest.raises(PermanentError) as refused:
            run(tmp_path, doc)
        assert refused.value.code == "contract_violation"
    unreadable = stage_inputs(tmp_path)
    unreadable["doc"].write_bytes(b"\xff{not json")
    with pytest.raises(PermanentError, match="JSON") as refused:
        preview_video.handle(job(), unreadable, ProcessRunner())
    assert refused.value.code == "contract_violation"
    assert invocations(blender_log) == []


def test_an_unknown_shot_is_refused_by_name(tmp_path: Path, blender_log: Path) -> None:
    with pytest.raises(PermanentError, match="sh9") as refused:
        run(tmp_path, shotId="sh9")
    assert refused.value.code == "contract_violation"
    assert invocations(blender_log) == []


def test_a_leaf_of_the_wrong_type_is_a_contract_violation(
    tmp_path: Path, blender_log: Path
) -> None:
    doc = document()
    doc["story"]["shots"][0]["staging"]["frame"] = "not a list"
    with pytest.raises(PermanentError, match="frame") as refused:
        run(tmp_path, doc)
    assert refused.value.code == "contract_violation"
    assert invocations(blender_log) == []


def test_success_returns_the_preview_card_and_data(tmp_path: Path, blender_log: Path) -> None:
    doc = document()
    outcome = run(tmp_path, doc)
    names = {file.name: file for file in outcome.files}
    assert list(names) == ["preview.mp4", "staging-card.json"]
    assert names["preview.mp4"].mime == "video/mp4"
    assert names["staging-card.json"].mime == "application/json"
    card = build_card(document(), "sh1", "9:16")
    body = names["staging-card.json"].body
    text = body.read_text("utf-8") if isinstance(body, Path) else body.decode()
    assert text == dumps(card)
    assert outcome.data == {
        "shotId": "sh1",
        "frames": 120,
        "width": 496,
        "height": 864,
        "durationSec": 5.0,
        "subjectInFrame": True,
        "blender": "4.5.14 LTS",
        "renderer": "llvmpipe (LLVM 15.0.7, 256 bits)",
        "renderSec": 2.5,
        "warnings": card["warnings"],
    }
    [argv] = invocations(blender_log)
    assert argv[1:6] == ["-b", "--factory-startup", "--python-exit-code", "1", "-P"]
    assert argv[6] == str(tmp_path / "scene.py")
    assert argv[7] == "--"
    assert argv[10] == str(tmp_path / "font.ttf")


def test_the_input_document_is_left_unchanged(tmp_path: Path, blender_log: Path) -> None:
    inputs = stage_inputs(tmp_path)
    before = inputs["doc"].read_bytes()
    preview_video.handle(job(), inputs, ProcessRunner())
    assert inputs["doc"].read_bytes() == before


def test_duration_is_frames_over_24_rounded_to_three_decimals(
    tmp_path: Path, blender_log: Path
) -> None:
    doc: dict[str, Any] = document()
    doc["story"]["shots"][0]["durationTargetMs"] = 1000
    outcome = run(tmp_path, doc, aspect="1:1")
    assert (outcome.data["frames"], outcome.data["width"], outcome.data["height"]) == (24, 640, 640)
    doc["story"]["shots"][0]["durationTargetMs"] = 1100
    outcome = run(tmp_path, doc)
    assert outcome.data["frames"] == 26
    assert outcome.data["durationSec"] == 1.083


def test_send_adds_one_file_and_render_seconds_cover_both_runs(
    tmp_path: Path, blender_log: Path
) -> None:
    outcome = run(tmp_path, send=True)
    assert [file.name for file in outcome.files] == [
        "preview.mp4",
        "staging-card.json",
        "preview-send.mp4",
    ]
    assert outcome.files[2].mime == "video/mp4"
    assert outcome.data["renderSec"] == 5.0
    first, second = invocations(blender_log)
    sent = json.loads(Path(second[8]).read_text("utf-8"))
    assert sent == send_card(build_card(document(), "sh1", "9:16"))
    assert sent["send"] is True
    assert Path(first[8]) != Path(second[8])


def test_send_defaults_to_off(tmp_path: Path, blender_log: Path) -> None:
    run(tmp_path, send=False)
    assert len(invocations(blender_log)) == 1


def test_a_subject_out_of_frame_is_reported_not_failed(
    tmp_path: Path, blender_log: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    monkeypatch.setenv("FAKE_BLENDER", "out_of_frame")
    assert run(tmp_path).data["subjectInFrame"] is False


def test_blender_exit_one_is_permanent_and_logs_the_output_tail(
    tmp_path: Path,
    blender_log: Path,
    monkeypatch: pytest.MonkeyPatch,
    caplog: pytest.LogCaptureFixture,
) -> None:
    monkeypatch.setenv("FAKE_BLENDER", "crash")
    with (
        caplog.at_level(logging.WARNING, logger=preview_video.log.name),
        pytest.raises(PermanentError) as refused,
    ):
        run(tmp_path)
    assert refused.value.code == "contract_violation"
    [record] = [r for r in caplog.records if r.name == preview_video.log.name]
    tail = cast(str, record.output)  # type: ignore[attr-defined]
    assert tail.splitlines() == [f"line {n}" for n in range(15, 40)]
    assert record.exitCode == 1  # type: ignore[attr-defined]


def test_blender_killed_by_a_signal_is_transient_and_logs_the_tail(
    tmp_path: Path,
    blender_log: Path,
    monkeypatch: pytest.MonkeyPatch,
    caplog: pytest.LogCaptureFixture,
) -> None:
    monkeypatch.setenv("FAKE_BLENDER", "killed")
    with (
        caplog.at_level(logging.WARNING, logger=preview_video.log.name),
        pytest.raises(TransientError),
    ):
        run(tmp_path)
    [record] = [r for r in caplog.records if r.name == preview_video.log.name]
    assert "about to be killed" in record.output  # type: ignore[attr-defined]
    assert record.exitCode < 0  # type: ignore[attr-defined]


def test_missing_blender_is_a_contract_violation(
    tmp_path: Path, blender_log: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    for value in (str(tmp_path / "absent"), str(tmp_path), None):
        if value is None:
            monkeypatch.delenv("BLENDER_BIN")
            monkeypatch.setattr(preview_video, "DEFAULT_BLENDER_BIN", str(tmp_path / "absent"))
        else:
            monkeypatch.setenv("BLENDER_BIN", value)
        with pytest.raises(PermanentError, match="Blender") as refused:
            run(tmp_path)
        assert refused.value.code == "contract_violation"


def test_exit_zero_without_a_report_is_a_contract_violation(
    tmp_path: Path, blender_log: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    monkeypatch.setenv("FAKE_BLENDER", "no_report")
    with pytest.raises(PermanentError, match="report") as refused:
        run(tmp_path)
    assert refused.value.code == "contract_violation"


@pytest.mark.parametrize("damage", ["not json", {"frames": "many"}, {"frames": 0}, []])
def test_an_unreadable_report_is_a_contract_violation(
    tmp_path: Path, blender_log: Path, damage: Any
) -> None:
    class Damaging(ProcessRunner):
        def run(self, argv: list[str]) -> Any:
            completed = super().run(argv)
            report = Path(argv[-2] + ".report.json")
            report.write_text(damage if isinstance(damage, str) else json.dumps(damage))
            return completed

    with pytest.raises(PermanentError, match="report") as refused:
        preview_video.handle(job(), stage_inputs(tmp_path), Damaging())
    assert refused.value.code == "contract_violation"


def test_a_stale_report_from_an_earlier_run_is_not_read(
    tmp_path: Path, blender_log: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    inputs = stage_inputs(tmp_path)
    preview_video.handle(job(), inputs, ProcessRunner())
    monkeypatch.setenv("FAKE_BLENDER", "no_report")
    with pytest.raises(PermanentError, match="report"):
        preview_video.handle(job(), inputs, ProcessRunner())


def test_the_preview_capability_is_registered_and_imports_without_blender(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.delenv("BLENDER_BIN", raising=False)
    assert registry.HANDLER_MODULES["preview_video"] == "flowcortex_worker.handlers.preview_video"
    assert "preview_video" in registry.load_handlers(None)
    assert registry.load_handlers(frozenset({"preview_video"})) == {
        "preview_video": preview_video.handle
    }
