"""Timeline planning: frame grid, handles, chunks, output size and caption cue selection."""

from __future__ import annotations

from collections.abc import Callable
from typing import Any

import pytest
from test_render_document import project, scene

from flowcortex_worker.render.document import Document, parse_document
from flowcortex_worker.render.timeline import (
    MAX_CHAIN_SCENES,
    TimelinePlan,
    karaoke_spans,
    output_size,
    plan_timeline,
)

PROBES: dict[str, dict[str, Any]] = {
    "a1": {
        "durationMs": 10000,
        "width": 1920,
        "height": 1080,
        "fps": 30,
        "hasAudio": True,
        "rotation": 0,
        "pixFmt": "yuv420p",
        "colorTransfer": "bt709",
    },
    "a2": {"width": 1080, "height": 1080, "pixFmt": "rgb24"},
}


def doc_of(
    scenes: list[dict[str, Any]], mutate: Callable[[dict[str, Any]], None] | None = None
) -> Document:
    raw = project()
    raw["timeline"]["scenes"] = scenes
    if mutate is not None:
        mutate(raw)
    return parse_document(raw)


def plan(
    scenes: list[dict[str, Any]],
    *,
    probes: dict[str, dict[str, Any]] | None = None,
    profile: str = "final",
    mutate: Callable[[dict[str, Any]], None] | None = None,
) -> TimelinePlan:
    return plan_timeline(doc_of(scenes, mutate), PROBES if probes is None else probes, profile)


def refused(
    scenes: list[dict[str, Any]],
    *needles: str,
    probes: dict[str, dict[str, Any]] | None = None,
    mutate: Callable[[dict[str, Any]], None] | None = None,
) -> None:
    with pytest.raises(ValueError) as caught:
        plan(scenes, probes=probes, mutate=mutate)
    for needle in needles:
        assert needle in str(caught.value)


def transition(mode: str, ms: int = 400, kind: str = "fade") -> dict[str, Any]:
    return {"kind": kind, "durMs": ms, "mode": mode}


def test_off_grid_take_snaps_the_scene_end_and_the_next_start() -> None:
    planned = plan(
        [
            scene(0, srcOutMs=5042, durMs=5042),
            scene(1, clipId="a2", atMs=5042),
        ]
    )
    assert planned.scenes[0].dur_ms == pytest.approx(5033.333, abs=1e-3)
    assert planned.scenes[1].at_ms == pytest.approx(5033.333, abs=1e-3)
    entry = next(s for s in planned.snapped if s["path"] == "timeline.scenes[0].durMs")
    assert entry["fromMs"] == 5042
    assert entry["toMs"] == pytest.approx(5033.333, abs=1e-3)


def test_gap_between_scenes_is_refused_with_the_expected_start() -> None:
    refused(
        [scene(0, srcOutMs=5000, durMs=5000), scene(1, clipId="a2", atMs=6000)],
        "timeline.scenes[1].atMs",
        "5000.0 ms",
    )


def test_first_scene_must_start_at_zero() -> None:
    refused([scene(0, atMs=100)], "timeline.scenes[0].atMs")


def test_extend_without_a_tail_handle_is_refused_and_suggests_overlap() -> None:
    probes = {**PROBES, "a1": {**PROBES["a1"], "durationMs": 3000}}
    refused(
        [scene(0, transitionOut=transition("extend")), scene(1, clipId="a2")],
        "overlap",
        "timeline.scenes[0].transitionOut",
        probes=probes,
    )


def test_extend_with_a_tail_handle_is_accepted_and_listed() -> None:
    probes = {**PROBES, "a1": {**PROBES["a1"], "durationMs": 3200}}
    planned = plan(
        [scene(0, transitionOut=transition("extend")), scene(1, clipId="a2")], probes=probes
    )
    assert planned.handles[0]["mode"] == "extend"
    assert planned.handles[0]["sceneId"] == "sc0"


def test_extend_into_a_clip_without_a_head_handle_is_refused() -> None:
    refused(
        [
            scene(0, clipId="a2", transitionOut=transition("extend")),
            scene(1, srcInMs=100, srcOutMs=3100, atMs=3000),
        ],
        "overlap",
    )


def test_overlap_shortens_the_timeline() -> None:
    planned = plan(
        [scene(0, transitionOut=transition("overlap", 500)), scene(1, clipId="a2", atMs=2500)]
    )
    assert planned.scenes[1].at_ms == 2500
    assert planned.total_ms == 5500
    assert planned.handles[0]["mode"] == "overlap"


def test_transition_shorter_than_a_frame_lasts_one_frame() -> None:
    planned = plan(
        [scene(0, transitionOut=transition("overlap", 1)), scene(1, clipId="a2", atMs=2967)]
    )
    transition_out = planned.scenes[0].transition_out
    assert transition_out is not None
    assert transition_out.dur_ms == pytest.approx(1000 / 30)


def test_transition_on_the_last_scene_is_refused() -> None:
    refused([scene(0), scene(1, clipId="a2", transitionOut=transition("overlap"))], "scenes[1]")


def test_chunks_cut_after_every_scene_without_a_transition() -> None:
    planned = plan(
        [
            scene(0, transitionOut=transition("overlap")),
            scene(1, clipId="a2", atMs=2600, transitionOut=None),
            scene(2, clipId="a2", atMs=5600),
        ]
    )
    assert [c.scene_indexes for c in planned.chunks] == [(0, 1), (2,)]
    assert planned.chunks[0].dur_ms == pytest.approx(5600)
    assert planned.chunks[1].start_ms == pytest.approx(5600)


def fills(count: int, chained: int) -> list[dict[str, Any]]:
    return [
        scene(
            i,
            clipId=None,
            srcOutMs=1000,
            durMs=1000,
            atMs=1000 * i,
            transitionOut=transition("extend") if i < chained else None,
        )
        for i in range(count)
    ]


def test_chain_of_thirteen_scenes_is_refused() -> None:
    refused(fills(13, chained=12), "13 scenes", f"{MAX_CHAIN_SCENES}")


def test_chain_of_twelve_scenes_is_accepted() -> None:
    planned = plan(fills(12, chained=11))
    assert len(planned.chunks) == 1


def test_source_one_millisecond_short_is_clamped_with_a_warning() -> None:
    probes = {**PROBES, "a1": {**PROBES["a1"], "durationMs": 2999}}
    planned = plan([scene(0)], probes=probes)
    clamp = planned.scenes[0].clamp
    assert clamp is not None
    assert clamp.src_out_ms == 2999
    assert clamp.dur_ms == pytest.approx(2999)
    assert any("clamped" in w for w in planned.warnings)
    assert planned.total_ms == 3000


def test_source_far_short_is_refused_naming_both_durations() -> None:
    probes = {**PROBES, "a1": {**PROBES["a1"], "durationMs": 100}}
    refused([scene(0)], "3000", "100", probes=probes)


def test_a_clip_that_was_not_measured_is_refused() -> None:
    refused([scene(0)], "timeline.scenes[0].clipId", probes={})


@pytest.mark.parametrize(
    ("width", "height", "profile", "expected"),
    [
        (1080, 1920, "draft", (480, 854)),
        (1920, 1080, "draft", (854, 480)),
        (360, 640, "draft", (360, 640)),
        (1080, 1920, "final", (1080, 1920)),
    ],
)
def test_output_size(width: int, height: int, profile: str, expected: tuple[int, int]) -> None:
    assert output_size(width, height, profile) == expected


def test_plan_carries_the_output_size() -> None:
    planned = plan([scene(0)], profile="draft")
    assert (planned.out_width, planned.out_height) == (480, 854)


def test_unknown_profile_is_refused() -> None:
    with pytest.raises(ValueError, match=r"params\.profile"):
        plan([scene(0)], profile="vertical_ad_v1")


def test_kept_audio_on_a_clip_without_audio_is_a_warning() -> None:
    probes = {**PROBES, "a1": {**PROBES["a1"], "hasAudio": False}}
    planned = plan([scene(0)], probes=probes)
    assert any("no audio stream" in w for w in planned.warnings)


def test_source_frame_rate_far_from_the_canvas_is_a_warning() -> None:
    probes = {**PROBES, "a1": {**PROBES["a1"], "fps": 25}}
    assert any("25 fps" in w for w in plan([scene(0)], probes=probes).warnings)


def test_hdr_source_is_a_warning() -> None:
    probes = {**PROBES, "a1": {**PROBES["a1"], "pixFmt": "yuv420p10le"}}
    assert any("HDR" in w for w in plan([scene(0)], probes=probes).warnings)


def test_unknown_caption_track_is_refused() -> None:
    def pick(raw: dict[str, Any]) -> None:
        raw["output"]["captionTrackId"] = "ct_gone"

    refused([scene(0)], "output.captionTrackId", mutate=pick)


def test_unknown_cue_style_is_refused() -> None:
    def pick(raw: dict[str, Any]) -> None:
        raw["timeline"]["captions"]["tracks"]["ct_main"]["cues"][0]["styleId"] = "nope"

    refused([scene(0)], "cues[0].styleId", mutate=pick)


def test_captions_lane_off_selects_no_cues() -> None:
    def off(raw: dict[str, Any]) -> None:
        raw["output"]["lanes"]["captions"] = False

    assert plan([scene(0)], mutate=off).cues == ()


def test_no_caption_track_selects_no_cues() -> None:
    def none(raw: dict[str, Any]) -> None:
        raw["output"]["captionTrackId"] = None

    assert plan([scene(0)], mutate=none).cues == ()


def test_cue_on_a_hidden_row_is_dropped() -> None:
    def hide(raw: dict[str, Any]) -> None:
        track = raw["timeline"]["captions"]["tracks"]["ct_main"]
        track["rows"] = [{"id": "rw1"}, {"id": "rw2", "hidden": True}]
        track["cues"].append(
            {"id": "cu2", "startMs": 0, "endMs": 900, "text": "ẩn", "row": "rw2", "words": []}
        )

    planned = plan([scene(0)], mutate=hide)
    assert [c.id for c in planned.cues] == ["cu1"]
    assert planned.cues[0].row_index == 0


def test_cue_row_index_is_absent_without_rows() -> None:
    def no_rows(raw: dict[str, Any]) -> None:
        raw["timeline"]["captions"]["tracks"]["ct_main"]["rows"] = []

    assert plan([scene(0)], mutate=no_rows).cues[0].row_index is None


def test_track_style_wins_over_the_project_style() -> None:
    def own(raw: dict[str, Any]) -> None:
        raw["timeline"]["captions"]["tracks"]["ct_main"]["style"] = {"fontSize": "xlarge"}

    assert plan([scene(0)], mutate=own).caption_style == {"fontSize": "xlarge"}
    assert plan([scene(0)]).caption_style["fontSize"] == "medium"


def test_cue_times_snap_to_the_ten_millisecond_grid() -> None:
    def odd(raw: dict[str, Any]) -> None:
        raw["timeline"]["captions"]["tracks"]["ct_main"]["cues"][0]["endMs"] = 1004

    planned = plan([scene(0)], mutate=odd)
    assert planned.cues[0].end_ms == 1000
    assert any(s["path"].endswith("cues[0].endMs") for s in planned.snapped)


def karaoke_doc(text: str, mode: str | None = None) -> Callable[[dict[str, Any]], None]:
    def edit(raw: dict[str, Any]) -> None:
        cue = raw["timeline"]["captions"]["tracks"]["ct_main"]["cues"][0]
        cue["text"] = text
        cue["words"] = [
            {"t": "xin", "startMs": 0, "endMs": 400},
            {"t": "chào", "startMs": 400, "endMs": 900},
        ]
        if mode is not None:
            raw["timeline"]["captions"]["style"]["highlightMode"] = mode

    return edit


def test_karaoke_spans_follow_the_typed_text() -> None:
    planned = plan([scene(0)], mutate=karaoke_doc("Xin chào!"))
    spans = planned.cues[0].karaoke
    assert [s.text for s in spans] == ["Xin ", "chào!"]
    assert [(s.start_ms, s.end_ms) for s in spans] == [(0, 400), (400, 900)]
    assert planned.warnings == ()


def test_edited_text_prints_whole_with_a_warning() -> None:
    planned = plan([scene(0)], mutate=karaoke_doc("Xin chao"))
    assert planned.cues[0].karaoke == ()
    assert any("do not match" in w for w in planned.warnings)


def test_edited_text_with_highlight_off_has_no_warning() -> None:
    planned = plan([scene(0)], mutate=karaoke_doc("Xin chao", mode="off"))
    assert planned.warnings == ()


def test_karaoke_spans_function_on_leading_punctuation_and_case() -> None:
    doc = parse_document(project())
    words = doc.caption_tracks["ct_main"].cues[0].words
    assert karaoke_spans("(XIN", words) == ["(XIN"]
    assert karaoke_spans("anything", ()) is None
