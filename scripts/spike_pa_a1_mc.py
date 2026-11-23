"""Slice 0 Spike (PA-A1): All-Replicate Hybrid 5s MC + 5s Product Ad (480P, 9:16).

Pipeline:
1. Edge TTS -> mc_hook.wav (<= 5s) + product_close.wav (<= 5s) -> aligned speech.wav (<= 10s)
2. bytedance/seedream-4 -> 9:16 keyframe (MC holding product, face front-facing, mouth clear)
3. minimax/h3-lite (480P, 5s) -> scene1_h3_480p.mp4
4. ffmpeg local upscale -> scene1_720p.mp4 (720x1280 for Kling Lip-Sync >= 720p requirement)
5. kwaivgi/kling-lip-sync -> scene1_lipsynced.mp4 (synced to mc_hook.wav)
6. minimax/h3-lite (480P, 5s) -> scene2_sp_480p.mp4
7. ffmpeg render -> final_mc_ad_480p.mp4 (480x848, 30fps, H.264 + AAC)

Run:
    uv run --with replicate,edge-tts python scripts/spike_pa_a1_mc.py
"""

from __future__ import annotations

import argparse
import asyncio
import json
import os
import re
import subprocess
import time
import urllib.request
import wave
from pathlib import Path
from typing import Any

import edge_tts
import httpx
import replicate

DEFAULT_VOICE = "vi-VN-NamMinhNeural"
DEFAULT_MC_SCRIPT = "Bình giữ nhiệt này giữ lạnh hai mươi bốn tiếng cực đã luôn mọi người."
DEFAULT_SP_SCRIPT = "Inox chống rò rỉ siêu gọn nhẹ, mua ngay hôm nay."

SAMPLE_PRODUCT_PROMPT = (
    "Studio product photography of a sleek matte navy blue insulated stainless steel tumbler "
    "with a brushed silver lid, standing upright on a clean light beige surface, soft diffused "
    "commercial studio lighting, crisp focus, vertical 9:16 composition, no text or watermark."
)
SAMPLE_MC_PROMPT = (
    "Photorealistic vertical 9:16 head-and-shoulders portrait of a friendly young Vietnamese "
    "man in his mid-20s wearing a clean off-white casual polo shirt, looking straight into the "
    "camera with a natural warm smile, bright modern minimalist indoor room with soft daylight "
    "bokeh, sharp focus on face, eyes and mouth clearly visible."
)
KEYFRAME_PROMPT = (
    "Photorealistic 9:16 vertical medium close-up shot (chest-up) of the exact person from "
    "Image 2 looking straight into the camera with a bright friendly expression, naturally "
    "holding up the exact product from Image 1 in his hand at chest level to his right side "
    "with the product front facing the camera. Mouth, chin, and entire face completely "
    "unobstructed and well-lit, bright modern indoor room with soft bokeh, matching natural "
    "shadows on fingers, completely clean frame with no logos, watermarks, or overlaid text."
)
SCENE2_KEYFRAME_PROMPT = (
    "Photorealistic vertical 9:16 commercial studio close-up photo of the exact product from "
    "Image 1 resting firmly on a warm light oak wood surface in a sunlit modern room, soft "
    "morning light highlighting its exact color, material, and details, shallow depth of field, "
    "completely clean background with no text, logos, or watermarks."
)
SCENE1_MC_MOTION_PROMPT = (
    "Locked-off eye-level vertical shot, the presenter speaks warmly and naturally straight "
    "to the camera with subtle eye contact, natural blinks, gentle head nods, and slight steady "
    "hand emphasis showcasing the product held below shoulder height. Face stays front-facing "
    "throughout, hands never cross near the mouth, stable soft indoor daylight."
)
SCENE2_SP_MOTION_PROMPT = (
    "Vertical 9:16 commercial close-up hero shot of the exact product resting firmly on a "
    "light oak desk in a sunlit modern room, soft morning light highlighting its refined "
    "materials and finish, smooth slow macro dolly push-in settling on a crisp centered frame."
)


def load_replicate_token(repo_root: Path) -> str:
    for env_key in ("REPLICATE_API_TOKEN", "MINIMAX_API_KEY", "WAN_API_KEY"):
        val = os.environ.get(env_key, "").strip()
        if val and not val.startswith(("change-me", "your-")):
            return val
    env_file = repo_root / ".env"
    if env_file.is_file():
        content = env_file.read_text(encoding="utf-8")
        for env_key in ("REPLICATE_API_TOKEN", "MINIMAX_API_KEY", "WAN_API_KEY"):
            match = re.search(rf"^{env_key}=(.+)$", content, re.MULTILINE)
            if match:
                val = match.group(1).strip().strip('"').strip("'")
                if val and not val.startswith(("change-me", "your-")):
                    return val
    raise RuntimeError("No valid Replicate API token found in env or .env")


def run_cmd(argv: list[str]) -> subprocess.CompletedProcess[str]:
    completed = subprocess.run(argv, capture_output=True, text=True, check=False)
    if completed.returncode != 0:
        raise RuntimeError(f"Command failed ({completed.returncode}): {' '.join(argv)}\n{completed.stderr}")
    return completed


async def synth_edge_tts_mp3(text: str, voice: str, out_mp3: Path, max_attempts: int = 10) -> None:
    last_exc: Exception | None = None
    for attempt in range(1, max_attempts + 1):
        try:
            communicate = edge_tts.Communicate(text, voice=voice, rate="+0%")
            await communicate.save(str(out_mp3))
            if out_mp3.is_file() and out_mp3.stat().st_size > 0:
                print(f"  [TTS OK] {out_mp3.name} (attempt {attempt}/{max_attempts})")
                return
        except Exception as exc:
            last_exc = exc
            print(f"  [TTS RETRY {attempt}/{max_attempts}] {type(exc).__name__}")
            await asyncio.sleep(1.5)
    raise RuntimeError(f"Edge TTS failed after {max_attempts} attempts: {last_exc}") from last_exc


def mp3_to_wav_24k(src_mp3: Path, dst_wav: Path) -> float:
    run_cmd([
        "ffmpeg", "-nostdin", "-hide_banner", "-loglevel", "error", "-y",
        "-i", str(src_mp3),
        "-ac", "1", "-ar", "24000", "-c:a", "pcm_s16le",
        str(dst_wav),
    ])
    with wave.open(str(dst_wav), "rb") as w:
        return round(w.getnframes() / w.getframerate(), 3)


def build_aligned_speech(mc_wav: Path, sp_wav: Path, out_wav: Path) -> float:
    """Pads mc_wav to 5.0s so Scene 2 voice-over starts cleanly at t=5.000s."""
    run_cmd([
        "ffmpeg", "-nostdin", "-hide_banner", "-loglevel", "error", "-y",
        "-i", str(mc_wav),
        "-i", str(sp_wav),
        "-filter_complex",
        "[0:a]apad=whole_dur=5,atrim=duration=5,asetpts=PTS-STARTPTS[a0];"
        "[1:a]atrim=duration=5,asetpts=PTS-STARTPTS[a1];"
        "[a0][a1]concat=n=2:v=0:a=1[a]",
        "-map", "[a]",
        "-ac", "1", "-ar", "24000", "-c:a", "pcm_s16le",
        str(out_wav),
    ])
    with wave.open(str(out_wav), "rb") as w:
        return round(w.getnframes() / w.getframerate(), 3)


def upload_file(client: replicate.Client, path: Path) -> str:
    uploaded = client.files.create(path)
    return str(uploaded.urls["get"])


def extract_output_url(output: Any) -> str:
    if isinstance(output, list) and output:
        first = output[0]
        return str(first.url if hasattr(first, "url") else first)
    if hasattr(output, "url"):
        return str(output.url)
    return str(output)


def download_file(url: str, dst: Path, max_attempts: int = 4) -> None:
    last_exc: Exception | None = None
    for attempt in range(1, max_attempts + 1):
        try:
            with httpx.Client(follow_redirects=True, timeout=120.0) as http:
                resp = http.get(
                    url,
                    headers={"User-Agent": "FlowCortexSpike/1.0 (compatible; Mozilla/5.0)"},
                )
                resp.raise_for_status()
                dst.write_bytes(resp.content)
                return
        except Exception as exc:
            last_exc = exc
            print(f"  [DL RETRY {attempt}/{max_attempts}] {type(exc).__name__}")
            time.sleep(float(2 ** (attempt - 1)))
    raise RuntimeError(f"Download failed after {max_attempts} attempts: {last_exc}") from last_exc


def run_replicate_model(
    client: replicate.Client,
    model: str,
    inputs: dict[str, Any],
    out_path: Path,
    *,
    skip_existing: bool,
    poll_timeout_seconds: float = 480.0,
) -> dict[str, Any]:
    if skip_existing and out_path.is_file() and out_path.stat().st_size > 0:
        print(f"  [SKIP] Reusing existing {out_path.name}")
        return {"model": model, "skipped": True, "path": str(out_path)}
    t0 = time.monotonic()
    print(f"  [RUN] {model} -> {out_path.name} ...")
    prediction = client.models.predictions.create(model=model, input=inputs)
    print(f"  [ID]  {prediction.id} (status={prediction.status})")
    deadline = t0 + poll_timeout_seconds
    while prediction.status in ("starting", "processing"):
        if time.monotonic() >= deadline:
            raise TimeoutError(f"{model} prediction {prediction.id} timed out after {poll_timeout_seconds}s")
        time.sleep(4.0)
        prediction.reload()
    if prediction.status != "succeeded":
        raise RuntimeError(f"{model} prediction {prediction.id} ended with {prediction.status}: {prediction.error}")
    url = extract_output_url(prediction.output)
    download_file(url, out_path)
    elapsed = round(time.monotonic() - t0, 2)
    print(f"  [OK] {out_path.name} ({out_path.stat().st_size // 1024} KB, {elapsed}s)")
    return {
        "model": model,
        "predictionId": prediction.id,
        "skipped": False,
        "elapsedSeconds": elapsed,
        "outputUrl": url,
        "bytes": out_path.stat().st_size,
        "path": str(out_path),
    }


def probe_media(path: Path) -> dict[str, Any]:
    completed = run_cmd([
        "ffprobe", "-v", "error",
        "-show_entries", "stream=codec_type,codec_name,width,height,r_frame_rate:format=duration,size",
        "-of", "json",
        str(path),
    ])
    return json.loads(completed.stdout)


def main() -> int:
    parser = argparse.ArgumentParser(description="Slice 0 PA-A1 Spike (All-Replicate 480P MC Ad)")
    parser.add_argument("--product-image", type=Path, default=None)
    parser.add_argument("--mc-image", type=Path, default=None)
    parser.add_argument("--voice", default=DEFAULT_VOICE)
    parser.add_argument("--mc-script", default=DEFAULT_MC_SCRIPT)
    parser.add_argument("--sp-script", default=DEFAULT_SP_SCRIPT)
    parser.add_argument("--out-dir", type=Path, default=Path("tmp/spike_pa_a1"))
    parser.add_argument("--no-skip", action="store_true", help="Force re-run all Replicate calls")
    args = parser.parse_args()

    skip_existing = not args.no_skip
    out_dir: Path = args.out_dir
    out_dir.mkdir(parents=True, exist_ok=True)

    repo_root = Path(__file__).resolve().parent.parent
    token = load_replicate_token(repo_root)
    client = replicate.Client(api_token=token)
    report: dict[str, Any] = {"voice": args.voice, "steps": {}}

    # Step 1: Edge TTS (2 Vietnamese segments)
    print("\n=== Step 1: Edge TTS (Vietnamese 2-segment audio) ===")
    mc_mp3 = out_dir / "mc_hook.mp3"
    sp_mp3 = out_dir / "product_close.mp3"
    mc_wav = out_dir / "mc_hook.wav"
    sp_wav = out_dir / "product_close.wav"
    speech_wav = out_dir / "speech.wav"

    loop_factory = asyncio.SelectorEventLoop if os.name == "nt" else None
    with asyncio.Runner(loop_factory=loop_factory) as runner:
        if not (skip_existing and mc_mp3.is_file() and mc_mp3.stat().st_size > 0):
            runner.run(synth_edge_tts_mp3(args.mc_script, args.voice, mc_mp3))
        if not (skip_existing and sp_mp3.is_file() and sp_mp3.stat().st_size > 0):
            runner.run(synth_edge_tts_mp3(args.sp_script, args.voice, sp_mp3))
    mc_dur = mp3_to_wav_24k(mc_mp3, mc_wav)
    sp_dur = mp3_to_wav_24k(sp_mp3, sp_wav)
    total_dur = build_aligned_speech(mc_wav, sp_wav, speech_wav)
    print(f"  mc_hook.wav: {mc_dur}s | product_close.wav: {sp_dur}s | speech.wav: {total_dur}s")
    if mc_dur > 5.0:
        raise RuntimeError(f"mc_hook.wav is {mc_dur}s (> 5.0s); shorten --mc-script")
    if sp_dur > 5.0:
        raise RuntimeError(f"product_close.wav is {sp_dur}s (> 5.0s); shorten --sp-script")
    report["steps"]["tts"] = {
        "mcDurationSec": mc_dur,
        "spDurationSec": sp_dur,
        "alignedDurationSec": total_dur,
    }

    # Step 2: Sample images (if not provided) + Keyframe compositing via seedream-4
    print("\n=== Step 2: Keyframe 9:16 (bytedance/seedream-4) ===")
    product_img = args.product_image or (out_dir / "sample_product.png")
    mc_img = args.mc_image or (out_dir / "sample_mc.png")
    if args.product_image is None:
        report["steps"]["sample_product"] = run_replicate_model(
            client,
            "bytedance/seedream-4",
            {"prompt": SAMPLE_PRODUCT_PROMPT, "aspect_ratio": "9:16", "size": "1K", "max_images": 1},
            product_img,
            skip_existing=skip_existing,
        )
    if args.mc_image is None:
        report["steps"]["sample_mc"] = run_replicate_model(
            client,
            "bytedance/seedream-4",
            {"prompt": SAMPLE_MC_PROMPT, "aspect_ratio": "9:16", "size": "1K", "max_images": 1},
            mc_img,
            skip_existing=skip_existing,
        )

    keyframe_img = out_dir / "keyframe_mc_9x16.png"
    if not (skip_existing and keyframe_img.is_file() and keyframe_img.stat().st_size > 0):
        prod_url = upload_file(client, product_img)
        mc_url = upload_file(client, mc_img)
        keyframe_inputs = {
            "prompt": KEYFRAME_PROMPT,
            "image_input": [prod_url, mc_url],
            "aspect_ratio": "9:16",
            "size": "1K",
            "max_images": 1,
        }
    else:
        keyframe_inputs = {}
    report["steps"]["keyframe"] = run_replicate_model(
        client,
        "bytedance/seedream-4",
        keyframe_inputs,
        keyframe_img,
        skip_existing=skip_existing,
    )

    # Step 3: Scene 1 (MC 5s via minimax/h3-lite 480P)
    print("\n=== Step 3: Scene 1 MC Video (minimax/h3-lite 480P, 5s) ===")
    scene1_480p = out_dir / "scene1_h3_480p.mp4"
    s1_inputs = (
        {}
        if skip_existing and scene1_480p.is_file() and scene1_480p.stat().st_size > 0
        else {
            "prompt": SCENE1_MC_MOTION_PROMPT,
            "first_frame_image": upload_file(client, keyframe_img),
            "duration": 5,
            "resolution": "480P",
            "ratio": "9:16",
        }
    )
    report["steps"]["scene1_h3"] = run_replicate_model(
        client, "minimax/h3-lite", s1_inputs, scene1_480p, skip_existing=skip_existing
    )

    # Step 4: Local ffmpeg upscale 480p -> 720p (free) for kwaivgi/kling-lip-sync
    print("\n=== Step 4: Local ffmpeg upscale (480p -> 720x1280) ===")
    scene1_720p = out_dir / "scene1_720p.mp4"
    run_cmd([
        "ffmpeg", "-nostdin", "-hide_banner", "-loglevel", "error", "-y",
        "-i", str(scene1_480p),
        "-vf", "scale=720:1280:flags=lanczos:force_original_aspect_ratio=decrease,"
               "pad=720:1280:(ow-iw)/2:(oh-ih)/2:black,setsar=1",
        "-an", "-c:v", "libx264", "-preset", "fast", "-pix_fmt", "yuv420p",
        str(scene1_720p),
    ])
    print(f"  [OK] {scene1_720p.name} ({scene1_720p.stat().st_size // 1024} KB)")

    # Step 5: Lip-Sync Scene 1 (kwaivgi/kling-lip-sync)
    print("\n=== Step 5: Lip-Sync Scene 1 (kwaivgi/kling-lip-sync) ===")
    scene1_lipsync = out_dir / "scene1_lipsynced.mp4"
    lip_inputs = (
        {}
        if skip_existing and scene1_lipsync.is_file() and scene1_lipsync.stat().st_size > 0
        else {
            "video_url": upload_file(client, scene1_720p),
            "audio_file": upload_file(client, mc_wav),
        }
    )
    report["steps"]["scene1_lipsync"] = run_replicate_model(
        client, "kwaivgi/kling-lip-sync", lip_inputs, scene1_lipsync, skip_existing=skip_existing
    )

    # Step 6: Scene 2 (Product Close-Up 5s via minimax/h3-lite 480P)
    print("\n=== Step 6: Scene 2 Product Video (minimax/h3-lite 480P, 5s) ===")
    scene2_keyframe = out_dir / "keyframe_sp_9x16.png"
    s2_kf_inputs = (
        {}
        if skip_existing and scene2_keyframe.is_file() and scene2_keyframe.stat().st_size > 0
        else {
            "prompt": SCENE2_KEYFRAME_PROMPT,
            "image_input": [upload_file(client, product_img)],
            "aspect_ratio": "9:16",
            "size": "1K",
            "max_images": 1,
        }
    )
    report["steps"]["scene2_keyframe"] = run_replicate_model(
        client, "bytedance/seedream-4", s2_kf_inputs, scene2_keyframe, skip_existing=skip_existing
    )
    scene2_480p = out_dir / "scene2_sp_480p.mp4"
    s2_inputs = (
        {}
        if skip_existing and scene2_480p.is_file() and scene2_480p.stat().st_size > 0
        else {
            "prompt": SCENE2_SP_MOTION_PROMPT,
            "first_frame_image": upload_file(client, scene2_keyframe),
            "duration": 5,
            "resolution": "480P",
            "ratio": "9:16",
        }
    )
    report["steps"]["scene2_h3"] = run_replicate_model(
        client, "minimax/h3-lite", s2_inputs, scene2_480p, skip_existing=skip_existing
    )

    # Step 7: Final render 480x848 MP4 (Scene 1 lip-synced + Scene 2 SP + aligned speech.wav)
    print("\n=== Step 7: Final Render 480x848 MP4 ===")
    final_mp4 = out_dir / "final_mc_ad_480p.mp4"
    target_total = min(10.0, max(5.5, total_dur))
    scene2_len = round(target_total - 5.0, 3)
    run_cmd([
        "ffmpeg", "-nostdin", "-hide_banner", "-loglevel", "error", "-y",
        "-i", str(scene1_lipsync),
        "-i", str(scene2_480p),
        "-i", str(speech_wav),
        "-filter_complex",
        "[0:v]scale=480:848:force_original_aspect_ratio=decrease,"
        "pad=480:848:(ow-iw)/2:(oh-ih)/2:black,setsar=1,"
        "fps=30,tpad=stop_mode=clone:stop_duration=5,trim=duration=5,setpts=PTS-STARTPTS[v0];"
        "[1:v]scale=480:848:force_original_aspect_ratio=decrease,"
        "pad=480:848:(ow-iw)/2:(oh-ih)/2:black,setsar=1,"
        f"fps=30,tpad=stop_mode=clone:stop_duration={scene2_len},"
        f"trim=duration={scene2_len},setpts=PTS-STARTPTS[v1];"
        "[v0][v1]concat=n=2:v=1:a=0[v];"
        f"[2:a]atrim=duration={target_total},asetpts=PTS-STARTPTS[a]",
        "-map", "[v]", "-map", "[a]",
        "-c:v", "libx264", "-preset", "fast", "-pix_fmt", "yuv420p", "-r", "30",
        "-c:a", "aac", "-b:a", "128k", "-ar", "24000", "-ac", "1",
        "-movflags", "+faststart",
        str(final_mp4),
    ])

    probe = probe_media(final_mp4)
    report["finalVideo"] = {
        "path": str(final_mp4.resolve()),
        "probe": probe,
    }
    report_path = out_dir / "report.json"
    report_path.write_text(json.dumps(report, indent=2, ensure_ascii=False), encoding="utf-8")

    print(f"\n=== DONE ===")
    print(f"Final video: {final_mp4.resolve()}")
    print(f"Duration: {probe['format']['duration']}s | Size: {int(probe['format']['size']) // 1024} KB")
    print(f"Report: {report_path.resolve()}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
