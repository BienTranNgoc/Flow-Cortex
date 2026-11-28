"""Generate one-time 9:16 preset assets (MC models & backgrounds) via Replicate seedream-4.

Saves optimized JPEG files (~150-250 KB, 9:16) under:
    backend/boot/app/src/main/resources/product-video-presets/
        mc/{mc-nu-tre,mc-nu-thanh-lich,mc-nam-nang-dong}.jpg
        backgrounds/{bg-studio-minimal,bg-warm-wood,bg-luxury-dark,bg-cafe-modern}.jpg

Run:
    uv run --with replicate,httpx python -u scripts/generate_presets.py
"""

from __future__ import annotations

import os
import re
import subprocess
import time
from pathlib import Path
from typing import Any

import httpx
import replicate

REPO_ROOT = Path(__file__).resolve().parent.parent
OUT_ROOT = REPO_ROOT / "backend" / "boot" / "app" / "src" / "main" / "resources" / "product-video-presets"

PRESETS: list[tuple[str, str, str]] = [
    (
        "mc",
        "mc-nu-tre",
        "Photorealistic vertical 9:16 head-and-shoulders portrait of a friendly young Vietnamese "
        "woman in her mid-20s wearing a clean pastel cream casual blouse, looking straight into "
        "the camera with a warm natural smile, soft even daylight, bright minimalist room with "
        "gentle bokeh, sharp focus on face, eyes and mouth completely clear, no text or watermark.",
    ),
    (
        "mc",
        "mc-nu-thanh-lich",
        "Photorealistic vertical 9:16 head-and-shoulders portrait of an elegant Vietnamese woman "
        "in her late 20s wearing a tailored ivory blazer over a neutral top, looking straight into "
        "the camera with a confident poised smile, soft studio beauty lighting, clean neutral "
        "backdrop with gentle depth of field, eyes and mouth clearly visible, no text or watermark.",
    ),
    (
        "backgrounds",
        "bg-studio-minimal",
        "Photorealistic vertical 9:16 empty commercial product photography studio setting, smooth "
        "matte light travertine stone tabletop in the lower foreground, clean soft warm-white "
        "plaster wall backdrop with gentle diffused window light shadows, minimalist luxury "
        "aesthetic, completely empty surface with NO products, NO people, NO text.",
    ),
    (
        "backgrounds",
        "bg-warm-wood",
        "Photorealistic vertical 9:16 sunlit modern living room setting, warm light oak wooden "
        "table surface in the foreground with natural morning sunlight rays and soft indoor bokeh "
        "background showing clean modern furniture and a subtle green plant, completely empty "
        "tabletop with NO products, NO people, NO text.",
    ),
    (
        "backgrounds",
        "bg-luxury-dark",
        "Photorealistic vertical 9:16 high-end luxury commercial photography setting, sleek matte "
        "dark slate stone podium surface in the foreground, subtle warm golden rim light and soft "
        "charcoal architectural bokeh backdrop, dramatic refined mood, completely empty surface "
        "with NO products, NO people, NO text.",
    ),
    (
        "backgrounds",
        "bg-cafe-modern",
        "Photorealistic vertical 9:16 cozy modern Vietnamese specialty coffee shop interior, warm "
        "walnut wood table surface in the foreground, soft afternoon daylight through glass window "
        "with dreamy bokeh lights and greenery in background, completely empty table with NO cups, "
        "NO products, NO people, NO text.",
    ),
]


def load_token() -> str:
    for key in ("REPLICATE_API_TOKEN", "MINIMAX_API_KEY", "WAN_API_KEY"):
        val = os.environ.get(key, "").strip()
        if val and not val.startswith(("change-me", "your-")):
            return val
    env_file = REPO_ROOT / ".env"
    if env_file.is_file():
        content = env_file.read_text(encoding="utf-8")
        for key in ("REPLICATE_API_TOKEN", "MINIMAX_API_KEY", "WAN_API_KEY"):
            match = re.search(rf"^{key}=(.+)$", content, re.MULTILINE)
            if match:
                val = match.group(1).strip().strip('"').strip("'")
                if val and not val.startswith(("change-me", "your-")):
                    return val
    raise RuntimeError("No valid Replicate token found")


def extract_url(output: Any) -> str:
    if isinstance(output, list) and output:
        first = output[0]
        return str(first.url if hasattr(first, "url") else first)
    if hasattr(output, "url"):
        return str(output.url)
    return str(output)


def download_bytes(url: str) -> bytes:
    for attempt in range(1, 5):
        try:
            with httpx.Client(follow_redirects=True, timeout=120.0) as http:
                resp = http.get(url, headers={"User-Agent": "FlowCortexPresets/1.0"})
                resp.raise_for_status()
                return resp.content
        except Exception:
            if attempt == 4:
                raise
            time.sleep(float(2 ** (attempt - 1)))
    raise RuntimeError("unreachable")


def normalize_jpeg(src: Path, dst: Path) -> None:
    dst.parent.mkdir(parents=True, exist_ok=True)
    completed = subprocess.run(
        [
            "ffmpeg", "-nostdin", "-hide_banner", "-loglevel", "error", "-y",
            "-i", str(src),
            "-vf", "scale=720:1280:force_original_aspect_ratio=increase,crop=720:1280",
            "-q:v", "4",
            str(dst),
        ],
        capture_output=True,
        text=True,
        check=False,
    )
    if completed.returncode != 0:
        raise RuntimeError(f"ffmpeg failed for {src}: {completed.stderr}")


def main() -> int:
    token = load_token()
    client = replicate.Client(api_token=token)
    raw_dir = REPO_ROOT / "tmp" / "presets_raw"
    raw_dir.mkdir(parents=True, exist_ok=True)

    # Reuse Slice 0's verified male MC portrait if present.
    slice0_mc = REPO_ROOT / "tmp" / "spike_pa_a1" / "sample_mc.png"
    male_dst = OUT_ROOT / "mc" / "mc-nam-nang-dong.jpg"
    if slice0_mc.is_file() and not male_dst.is_file():
        normalize_jpeg(slice0_mc, male_dst)
        print(f"[REUSE] {male_dst.relative_to(REPO_ROOT)} ({male_dst.stat().st_size // 1024} KB)")

    for category, preset_id, prompt in PRESETS:
        dst = OUT_ROOT / category / f"{preset_id}.jpg"
        if dst.is_file() and dst.stat().st_size > 0:
            print(f"[SKIP] {dst.relative_to(REPO_ROOT)}")
            continue
        t0 = time.monotonic()
        print(f"[RUN]  {category}/{preset_id} ...")
        pred = None
        for attempt in range(1, 5):
            try:
                pred = client.models.predictions.create(
                    model="bytedance/seedream-4",
                    input={"prompt": prompt, "aspect_ratio": "9:16", "size": "1K", "max_images": 1},
                )
                break
            except Exception as exc:
                if "429" in str(exc) and attempt < 4:
                    print(f"[429]  sleeping 12s before retry {attempt + 1}/4 ...")
                    time.sleep(12.0)
                    continue
                raise
        assert pred is not None
        while pred.status in ("starting", "processing"):
            time.sleep(3.0)
            pred.reload()
        if pred.status != "succeeded":
            raise RuntimeError(f"{preset_id} failed: {pred.status} {pred.error}")
        raw_path = raw_dir / f"{preset_id}.raw"
        raw_path.write_bytes(download_bytes(extract_url(pred.output)))
        normalize_jpeg(raw_path, dst)
        elapsed = round(time.monotonic() - t0, 1)
        print(f"[OK]   {dst.relative_to(REPO_ROOT)} ({dst.stat().st_size // 1024} KB, {elapsed}s)")

    print("\n=== All preset assets ready ===")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
