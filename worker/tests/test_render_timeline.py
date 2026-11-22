"""Timeline planning: frame grid, handles, chunks, output size and caption cue selection."""

from __future__ import annotations

from collections.abc import Callable
from typing import Any

import pytest
from test_render_document import (
    image_overlay,
    music_clip,
    project,
    scene,
    text_overlay,
    voice_clip,
    with_music,
    with_overlays,
    with_voice,
)

from flowcortex_worker.render.document import Document, parse_document
from flowcortex_worker.render.timeline import (
    MAX_CHAIN_SCENES,
    MAX_CHUNK_SCENES,
    MIX_DEFAULTS,
    PlannedImageOverlay,
    PlannedTextOverlay,
    TimelinePlan,
    TrimPlan,
    declared_probes,
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


Mutate = Callable[[dict[str, Any]], Any]


def doc_of(scenes: list[dict[str, Any]], mutate: Mutate | None = None) -> Document:
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
    mutate: Mutate | None = None,
    measured: bool = True,
) -> TimelinePlan:
    return plan_timeline(
        doc_of(scenes, mutate),
        PROBES if probes is None else probes,
        profile,
        measured=measured,
    )


def refused(
    scenes: list[dict[str, Any]],
    *needles: str,
    probes: dict[str, dict[str, Any]] | None = None,
    mutate: Mutate | None = None,
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


def chained_fills(count: int, mode: str = "overlap", dur: int = 400) -> list[dict[str, Any]]:
    """`count` one-second fills chained by `mode` transitions; overlaps pull the next start back."""
    step = 1000 - (dur if mode == "overlap" else 0)
    return [
        scene(
            i,
            clipId=None,
            srcOutMs=1000,
            durMs=1000,
            atMs=step * i,
            transitionOut=transition(mode, dur) if i < count - 1 else None,
        )
        for i in range(count)
    ]


def real_scenes(planned: TimelinePlan) -> list[tuple[int, ...]]:
    return [c.scene_indexes for c in planned.chunks]


def test_chain_of_twelve_scenes_is_accepted_and_split_at_the_cap() -> None:
    planned = plan(fills(12, chained=11))
    assert real_scenes(planned) == [(0, 1, 2, 3), (4, 5, 6, 7), (8, 9, 10, 11)]
    assert MAX_CHUNK_SCENES == 4


def test_chain_of_one_scene_is_one_chunk_without_a_lead() -> None:
    planned = plan([scene(0)])
    assert real_scenes(planned) == [(0,)]
    assert planned.chunks[0].lead_scene is None
    assert not planned.chunks[0].cut_after


def test_chain_at_the_cap_stays_one_chunk() -> None:
    planned = plan(chained_fills(MAX_CHUNK_SCENES))
    assert real_scenes(planned) == [tuple(range(MAX_CHUNK_SCENES))]
    assert planned.chunks[0].lead_scene is None


def test_chain_one_over_the_cap_splits_into_cap_and_one() -> None:
    planned = plan(chained_fills(MAX_CHUNK_SCENES + 1))
    first, second = planned.chunks
    assert [first.scene_indexes, second.scene_indexes] == [
        tuple(range(MAX_CHUNK_SCENES)),
        (MAX_CHUNK_SCENES,),
    ]
    assert first.lead_scene is None and first.cut_after
    assert second.lead_scene == MAX_CHUNK_SCENES - 1 and not second.cut_after


@pytest.mark.parametrize("mode", ["overlap", "extend"])
@pytest.mark.parametrize("cap", [1, 2, 3, 4, 5, 12])
def test_split_chunks_tile_the_unsplit_timeline(mode: str, cap: int) -> None:
    scenes = chained_fills(12, mode)
    whole = plan(scenes)
    split = plan_timeline(doc_of(scenes), PROBES, "final", max_chunk_scenes=cap)
    assert split.total_ms == whole.total_ms
    assert split.scenes == whole.scenes
    assert split.chunks[0].start_ms == 0
    for a, b in zip(split.chunks, split.chunks[1:], strict=False):
        assert a.start_ms + a.dur_ms == pytest.approx(b.start_ms)
    last = split.chunks[-1]
    assert last.start_ms + last.dur_ms == pytest.approx(whole.total_ms)
    assert sum(len(c.scene_indexes) for c in split.chunks) == 12


@pytest.mark.parametrize("mode", ["overlap", "extend"])
def test_transition_on_a_chunk_boundary_opens_the_next_chunk_with_the_previous_scene(
    mode: str,
) -> None:
    planned = plan(chained_fills(MAX_CHUNK_SCENES + 2, mode))
    first, second = planned.chunks
    boundary = planned.scenes[MAX_CHUNK_SCENES]
    shift = 400 / 2 if mode == "extend" else 0
    # The cut falls on the frame grid at or just before where the cross-fade starts.
    assert second.start_ms <= boundary.at_ms - shift + 1e-6
    assert second.start_ms > boundary.at_ms - shift - 1000 / 30
    assert second.lead_scene == MAX_CHUNK_SCENES - 1
    assert first.cut_after


def test_a_scene_shorter_than_its_cut_transition_is_refused() -> None:
    scenes = chained_fills(3)
    scenes[1] = {**scenes[1], "durMs": 300, "srcOutMs": 300}
    scenes[2] = {**scenes[2], "atMs": 500}
    with pytest.raises(ValueError, match=r"scenes\[1\]"):
        plan_timeline(doc_of(scenes), PROBES, "final", max_chunk_scenes=1)


def test_chunk_cap_below_one_is_refused() -> None:
    with pytest.raises(ValueError, match="max_chunk_scenes"):
        plan_timeline(doc_of([scene(0)]), PROBES, "final", max_chunk_scenes=0)


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


def plan_one(mutate: Mutate | None = None, **kwargs: Any) -> TimelinePlan:
    return plan([scene(0)], mutate=mutate, **kwargs)


def first_text(planned: TimelinePlan) -> PlannedTextOverlay:
    overlay = planned.overlays[0]
    assert isinstance(overlay, PlannedTextOverlay)
    return overlay


def first_image(planned: TimelinePlan) -> PlannedImageOverlay:
    overlay = planned.overlays[0]
    assert isinstance(overlay, PlannedImageOverlay)
    return overlay


def first_trim(planned: TimelinePlan) -> TrimPlan:
    trim = planned.voice[0].trim
    assert trim is not None
    return trim


def test_plan_mode_tolerates_a_clip_without_a_declared_duration() -> None:
    planned = plan(
        [scene(0, transitionOut=transition("extend")), scene(1, clipId="a2")],
        probes={"a1": {}},
        measured=False,
    )
    assert "not measured" in planned.handles[0]["reason"]
    assert any("scene sc0" in w and "not measured" in w for w in planned.warnings)


def test_plan_mode_refuses_a_declared_duration_that_is_too_short() -> None:
    with pytest.raises(ValueError) as caught:
        plan(
            [scene(0, srcOutMs=8000, durMs=8000)],
            probes={"a1": {"durationMs": 5000}},
            measured=False,
        )
    assert "8000" in str(caught.value)
    assert "5000" in str(caught.value)


def test_measured_mode_still_wants_every_probe() -> None:
    refused([scene(0)], "timeline.scenes[0].clipId", probes={"a1": {}})


def test_declared_probes_come_from_the_document() -> None:
    def declare(raw: dict[str, Any]) -> None:
        raw["assets"]["a1"]["probe"] = {"durationMs": 7000, "hasAudio": False}

    declared = declared_probes(doc_of([scene(0)], declare))
    assert declared["a1"] == {"durationMs": 7000, "hasAudio": False}
    assert declared["a2"] == {}


@pytest.mark.parametrize(("claimed", "warned"), [(6000, True), (5030, False), (4950, False)])
def test_declared_and_measured_duration_are_compared(claimed: int, warned: bool) -> None:
    def declare(raw: dict[str, Any]) -> None:
        raw["assets"]["a1"]["probe"] = {"durationMs": claimed}

    probes = {**PROBES, "a1": {**PROBES["a1"], "durationMs": 5000}}
    planned = plan([scene(0)], probes=probes, mutate=declare)
    flagged = [w for w in planned.warnings if "assets.a1.probe.durationMs" in w]
    assert bool(flagged) is warned
    if warned:
        assert "6000" in flagged[0]
        assert "5000" in flagged[0]


def test_declared_duration_is_not_compared_in_plan_mode() -> None:
    def declare(raw: dict[str, Any]) -> None:
        raw["assets"]["a1"]["probe"] = {"durationMs": 6000}

    planned = plan_one(declare, probes={"a1": {"durationMs": 6000}}, measured=False)
    assert [w for w in planned.warnings if "assets.a1" in w] == []


def test_mix_defaults_are_fixed() -> None:
    assert MIX_DEFAULTS == {
        "targetLufs": -14.0,
        "truePeakDb": -1.0,
        "lra": 11.0,
        "duck": {"threshold": 0.02, "ratio": 6, "attack": 20, "release": 400},
    }


def test_plan_carries_the_lanes_and_the_chosen_languages() -> None:
    planned = plan_one()
    assert planned.lanes == {"captions": True, "voice": False, "music": False, "overlays": False}
    assert planned.voice_track_id is None
    assert planned.overlay_lang == "vi"
    assert (planned.overlays, planned.voice, planned.music) == ((), (), ())


def test_voice_lane_without_a_chosen_track_plays_nothing() -> None:
    planned = plan_one(lambda raw: with_voice(raw, voice_clip(), chosen=False))
    assert planned.voice == ()


def test_an_unknown_voice_track_is_refused() -> None:
    refused(
        [scene(0)],
        "output.voiceTrackId",
        mutate=lambda raw: raw["output"].update(voiceTrackId="vt_x"),
    )


def test_lanes_that_are_off_plan_no_content() -> None:
    def off(raw: dict[str, Any]) -> None:
        with_overlays(raw, text_overlay(), image_overlay(), lane=False)
        with_voice(raw, voice_clip(), lane=False)
        with_music(raw, music_clip(), lane=False)

    planned = plan_one(off)
    assert (planned.overlays, planned.voice, planned.music) == ((), (), ())
    assert planned.duck_enabled is True


def test_muted_rows_drop_voice_and_music_clips() -> None:
    def rows(raw: dict[str, Any]) -> None:
        with_voice(
            raw,
            voice_clip(row="rw1"),
            voice_clip(id="vc2", row="rw2"),
            rows=[{"id": "rw1", "muted": True}, {"id": "rw2"}],
        )
        with_music(
            raw,
            music_clip(row="rw1"),
            music_clip(id="mu2", row="rw2"),
            rows=[{"id": "rw1", "muted": True}, {"id": "rw2"}],
        )

    planned = plan_one(rows)
    assert [c.id for c in planned.voice] == ["vc2"]
    assert [c.id for c in planned.music] == ["mu2"]


def test_a_hidden_image_row_keeps_a_text_overlay_with_the_same_row_id() -> None:
    def hidden(raw: dict[str, Any]) -> None:
        with_overlays(raw, text_overlay(row="rw1"), image_overlay(row="rw1"))
        raw["timeline"]["overlayRows"] = {
            "text": [{"id": "rw1"}],
            "image": [{"id": "rw1", "hidden": True}],
        }

    assert [o.id for o in plan_one(hidden).overlays] == ["ov1"]


def test_text_overlay_is_planned_in_the_overlay_language() -> None:
    overlay = first_text(plan_one(lambda raw: with_overlays(raw, text_overlay())))
    assert overlay.text == "Xin chào"
    assert overlay.row_index is None

    def english(raw: dict[str, Any]) -> None:
        with_overlays(raw, text_overlay())
        raw["output"]["overlayLang"] = "en"

    assert first_text(plan_one(english)).text == "Hello"


def test_text_overlay_without_the_language_is_skipped_with_a_warning() -> None:
    def french(raw: dict[str, Any]) -> None:
        with_overlays(raw, text_overlay())
        raw["output"]["overlayLang"] = "fr"

    planned = plan_one(french)
    assert planned.overlays == ()
    assert any("ov1" in w and "fr" in w for w in planned.warnings)


def test_text_overlay_marks_snap_to_ten_milliseconds_and_rows_are_indexed() -> None:
    def odd(raw: dict[str, Any]) -> None:
        with_overlays(raw, text_overlay(startMs=1004, endMs=2007, row="rw2"))
        raw["timeline"]["overlayRows"] = {"text": [{"id": "rw1"}, {"id": "rw2"}], "image": []}

    planned = plan_one(odd)
    overlay = first_text(planned)
    assert (overlay.start_ms, overlay.end_ms) == (1000, 2010)
    assert overlay.row_index == 1
    paths = {s["path"]: s for s in planned.snapped}
    assert paths["timeline.overlays[0].startMs"]["toMs"] == 1000
    assert paths["timeline.overlays[0].endMs"]["fromMs"] == 2007


def test_image_overlay_marks_snap_to_the_frame_grid_and_carry_the_sheet() -> None:
    sheet = {"frameCount": 4, "cols": 2, "frameW": 64, "frameH": 64, "frameMs": 100}

    def animated(raw: dict[str, Any]) -> None:
        with_overlays(raw, image_overlay(startMs=1020, endMs=2000))
        raw["assets"]["a2"]["animation"] = sheet

    planned = plan_one(animated)
    overlay = first_image(planned)
    assert overlay.start_ms == pytest.approx(1033.333, abs=1e-3)
    assert overlay.end_ms == pytest.approx(2000, abs=1e-3)
    assert overlay.animation == sheet
    assert any(s["path"] == "timeline.overlays[0].startMs" for s in planned.snapped)
    still = first_image(plan_one(lambda raw: with_overlays(raw, image_overlay())))
    assert still.animation is None


def test_image_overlay_on_a_video_asset_is_refused() -> None:
    refused(
        [scene(0)],
        "timeline.overlays[0].assetId",
        "a1",
        "video",
        mutate=lambda raw: with_overlays(raw, image_overlay(assetId="a1")),
    )


def test_image_overlay_shorter_than_a_frame_is_not_drawn() -> None:
    planned = plan_one(lambda raw: with_overlays(raw, image_overlay(startMs=1000, endMs=1010)))
    overlay = first_image(planned)
    assert overlay.end_ms <= overlay.start_ms
    assert any("ov2" in w and "shorter than a frame" in w for w in planned.warnings)


def test_voice_speed_and_length_must_agree() -> None:
    def clip(**over: Any) -> Mutate:
        return lambda raw: with_voice(raw, voice_clip(srcOutMs=3000, **over))

    refused([scene(0)], "durMs", "2000", "2500", mutate=clip(rate=1.5, durMs=2500))
    assert plan_one(clip(rate=1.5, durMs=2000)).voice[0].rate == 1.5
    assert plan_one(clip(rate=1.5, durMs=2030)).voice[0].dur_ms == 2030


@pytest.mark.parametrize("measured", [True, False])
def test_voice_fades_longer_than_the_clip_are_clamped_with_a_warning(measured: bool) -> None:
    planned = plan_one(
        lambda raw: with_voice(
            raw, voice_clip(srcOutMs=2000, durMs=2000, fadeInMs=2500, fadeOutMs=3000)
        ),
        measured=measured,
    )
    clip = planned.voice[0]
    assert (clip.fade_in_ms, clip.fade_out_ms) == (2000, 2000)
    base = "timeline.voice.tracks.vt_one.clips[0]"
    assert any(f"{base}.fadeOutMs" in w and "3000" in w for w in planned.warnings)
    assert any(f"{base}.fadeInMs" in w for w in planned.warnings)


@pytest.mark.parametrize("measured", [True, False])
def test_music_fades_longer_than_the_clip_are_clamped_with_a_warning(measured: bool) -> None:
    planned = plan_one(
        lambda raw: with_music(raw, music_clip(durMs=3000, fadeOutMs=5000)), measured=measured
    )
    clip = planned.music[0]
    assert (clip.fade_in_ms, clip.fade_out_ms) == (0, 3000)
    assert any("timeline.music.clips[0].fadeOutMs" in w for w in planned.warnings)


def test_fades_inside_the_clip_are_untouched_and_silent() -> None:
    planned = plan_one(lambda raw: with_music(raw, music_clip(fadeInMs=500, fadeOutMs=3000)))
    assert (planned.music[0].fade_in_ms, planned.music[0].fade_out_ms) == (500, 3000)
    assert not any("clamped" in w for w in planned.warnings)


def test_voice_asset_shorter_than_its_segment_is_a_warning() -> None:
    probes = {**PROBES, "v1": {"durationMs": 2000}}
    planned = plan_one(lambda raw: with_voice(raw, voice_clip()), probes=probes)
    assert any(
        "timeline.voice.tracks.vt_one.clips[0]" in w and "2000" in w for w in planned.warnings
    )


def cue_to(end_ms: int) -> Callable[[dict[str, Any]], None]:
    def edit(raw: dict[str, Any]) -> None:
        raw["timeline"]["captions"]["tracks"]["ct_main"]["cues"][0]["endMs"] = end_ms

    return edit


def trimmed(
    *, at: int = 1000, fade_out: int = 0, policy: str = "trim_tail", cue: str = "cu1", **track: Any
) -> Callable[[dict[str, Any]], None]:
    def build(raw: dict[str, Any]) -> None:
        cue_to(3500)(raw)
        with_voice(
            raw,
            voice_clip(atMs=at, fadeOutMs=fade_out, fitPolicy=policy, cueId=cue),
            **track,
        )

    return build


def test_trim_tail_cuts_at_the_cue_end_with_a_short_fade() -> None:
    planned = plan_one(trimmed())
    trim = first_trim(planned)
    assert (trim.cue_id, trim.to_ms, trim.play_ms, trim.fade_ms) == ("cu1", 3500, 2500, 60)
    assert [w for w in planned.warnings if "vc1" in w] == []


def test_trim_tail_fade_follows_the_longer_user_fade_but_never_the_whole_play() -> None:
    assert first_trim(plan_one(trimmed(fade_out=200))).fade_ms == 200
    assert first_trim(plan_one(trimmed(at=3470))).fade_ms == 30


def test_trim_tail_clip_starting_after_the_cue_end_is_dropped() -> None:
    planned = plan_one(trimmed(at=4000))
    assert planned.voice == ()
    assert any("vc1" in w and "3500" in w for w in planned.warnings)


@pytest.mark.parametrize(
    "build",
    [trimmed(cue="ghost"), trimmed(captionTrackId=None), trimmed(captionTrackId="ct_gone")],
)
def test_trim_tail_without_its_cue_plays_whole_with_a_warning(
    build: Callable[[dict[str, Any]], None],
) -> None:
    planned = plan_one(build)
    assert planned.voice[0].trim is None
    assert any("vc1" in w for w in planned.warnings)


def test_ripple_and_a_clip_inside_its_cue_play_whole() -> None:
    assert plan_one(trimmed(policy="ripple")).voice[0].trim is None
    assert plan_one(trimmed(at=0)).voice[0].trim is None


def test_stem_assets_are_flagged() -> None:
    def stem(raw: dict[str, Any]) -> None:
        raw["assets"]["v1"]["role"] = "stem"
        with_voice(raw, voice_clip())

    assert plan_one(stem).voice[0].stem is True
    assert plan_one(lambda raw: with_voice(raw, voice_clip())).voice[0].stem is False


@pytest.mark.parametrize(("loop", "word"), [(True, "loop"), (False, "silent")])
def test_music_asset_shorter_than_its_segment_is_a_warning(loop: bool, word: str) -> None:
    probes = {**PROBES, "m1": {"durationMs": 1000}}
    planned = plan_one(lambda raw: with_music(raw, music_clip(loop=loop)), probes=probes)
    flagged = [w for w in planned.warnings if "timeline.music.clips[0]" in w]
    assert flagged
    assert word in flagged[0]


def test_music_clip_is_planned_as_written() -> None:
    planned = plan_one(lambda raw: with_music(raw, music_clip(gainDb=-9, loop=True)))
    clip = planned.music[0]
    assert (clip.id, clip.asset_id, clip.gain_db, clip.loop) == ("mu1", "m1", -9, True)
    assert planned.duck_enabled is True
