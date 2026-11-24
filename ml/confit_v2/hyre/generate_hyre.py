#!/usr/bin/env python3
"""
HYRE (Hypothetical Resume Embedding) Generator for ConFit v2 (ACL 2025).
Prompts an LLM (Gemini / OpenAI / Fallback) to generate an ideal hypothetical resume for each job post,
then concatenates them:
  augmented_job = real_job + "\n[HYPOTHETICAL RESUME]\n" + hypothetical_resume

Includes automatic disk caching and fallback heuristic generator for offline / zero-cost runs.
"""

import os
import sys
import json
import argparse
import random
import re
import pandas as pd
try:
    from tqdm import tqdm
except ImportError:
    def tqdm(iterable, *args, **kwargs):
        return iterable
from typing import Dict, List, Optional

HYRE_PROMPT_TEMPLATE = """Here is a template pair of matching resume and job:
[The start of the example job]
{example_job}
[The end of the example job]
[The start of the example resume]
{example_resume}
[The end of the example resume]
You are a helpful assistant. Following the above example pair of job and resume, construct an ideal resume for the target job shown below. You should strictly follow the format of the given pairs, make sure the resume you give perfectly matches the target job, and directly return your answer in plain text.
[The start of the target job]
{target_job}
[The end of the target job]"""

def clean_generated_text(text: str) -> str:
    """Clean markdown delimiters and tags from LLM response."""
    cleaned = re.sub(r"\[The start of .*?\]\n?", "", text)
    cleaned = re.sub(r"\[The end of .*?\]\n?", "", cleaned)
    cleaned = re.sub(r"```[a-zA-Z]*\n?", "", cleaned)
    cleaned = cleaned.replace("```", "")
    return cleaned.strip()

def generate_heuristic_resume(job_text: str) -> str:
    """Fallback generator for offline mode: extract keywords and build a synthetic ideal resume."""
    lines = [l.strip() for l in job_text.split("\n") if l.strip()]
    title = lines[0] if lines else "Software Professional"
    return (
        f"## Professional Title: Ideal Candidate for {title}\n"
        f"## Summary: Highly qualified candidate matching all core requirements.\n"
        f"## Core Competencies & Skills: " + ", ".join([w for w in job_text.replace(",", " ").split() if len(w) > 4][:15]) + ".\n"
        f"## Professional Experience: Demonstrated track record of delivering high-impact projects aligning with job specifications.\n"
        f"## Education: Bachelor of Science in Computer Science or relevant degree with honors."
    )

class HyreGenerator:
    def __init__(self, provider: str = "mock", api_key: Optional[str] = None, model_name: Optional[str] = None):
        self.provider = provider.lower()
        self.api_key = api_key or os.environ.get("GEMINI_API_KEY") or os.environ.get("OPENAI_API_KEY")
        self.model_name = model_name
        self.client = None

        if self.provider == "gemini":
            try:
                import google.generativeai as genai
                if not self.api_key:
                    raise ValueError("Thiếu GEMINI_API_KEY")
                genai.configure(api_key=self.api_key)
                self.client = genai.GenerativeModel(self.model_name or "gemini-1.5-flash")
                print(f"[*] Khởi tạo Gemini Model ({self.model_name or 'gemini-1.5-flash'}) thành công.")
            except Exception as e:
                print(f"[!] Không thể khởi tạo Gemini ({e}). Chuyển sang chế độ Heuristic Fallback.")
                self.provider = "mock"

        elif self.provider == "openai":
            try:
                from openai import OpenAI
                if not self.api_key:
                    raise ValueError("Thiếu OPENAI_API_KEY")
                self.client = OpenAI(api_key=self.api_key)
                self.model_name = self.model_name or "gpt-4o-mini"
                print(f"[*] Khởi tạo OpenAI Client ({self.model_name}) thành công.")
            except Exception as e:
                print(f"[!] Không thể khởi tạo OpenAI ({e}). Chuyển sang chế độ Heuristic Fallback.")
                self.provider = "mock"

    def generate_single(self, job_text: str, template: Dict[str, str]) -> str:
        if self.provider == "mock":
            return generate_heuristic_resume(job_text)

        prompt = HYRE_PROMPT_TEMPLATE.format(
            example_job=template.get("job", ""),
            example_resume=template.get("resume", ""),
            target_job=job_text
        )

        try:
            if self.provider == "gemini":
                resp = self.client.generate_content(prompt)
                return clean_generated_text(resp.text)
            elif self.provider == "openai":
                resp = self.client.chat.completions.create(
                    model=self.model_name,
                    messages=[{"role": "user", "content": prompt}],
                    temperature=0.3,
                    max_tokens=600
                )
                return clean_generated_text(resp.choices[0].message.content)
        except Exception as e:
            print(f"[!] Lỗi khi gọi API: {e}. Dùng heuristic fallback cho job này.")
            return generate_heuristic_resume(job_text)

def run_hyre(data_dir: str, output_csv: Optional[str] = None, provider: str = "mock", api_key: Optional[str] = None, limit: Optional[int] = None):
    jd_csv_path = os.path.join(data_dir, "all_jd_full_from_text_recruiting_data.csv")
    template_path = os.path.join(data_dir, "templates.json")
    cache_path = os.path.join(data_dir, "hyre_cache.json")

    if not os.path.exists(jd_csv_path):
        raise FileNotFoundError(f"Không tìm thấy {jd_csv_path}")

    df_jobs = pd.read_csv(jd_csv_path)
    if limit:
        df_jobs = df_jobs.head(limit)

    templates = []
    if os.path.exists(template_path):
        with open(template_path, "r", encoding="utf-8") as f:
            templates = json.load(f)
    if not templates:
        templates = [{"job": "Standard Job", "resume": "Standard Resume"}]

    # Load cache if exists
    cache = {}
    if os.path.exists(cache_path):
        try:
            with open(cache_path, "r", encoding="utf-8") as f:
                cache = json.load(f)
            print(f"[*] Đã nạp {len(cache)} bản ghi từ cache ({cache_path}).")
        except Exception:
            cache = {}

    generator = HyreGenerator(provider=provider, api_key=api_key)

    augmented_rows = []
    new_generated = 0

    print(f"[-] Đang sinh HYRE cho {len(df_jobs)} tin tuyển dụng (Provider: {generator.provider})...")
    for _, row in tqdm(df_jobs.iterrows(), total=len(df_jobs)):
        jd_id = str(row["jd_no"])
        real_job = str(row["text_job"])

        if jd_id in cache:
            hypo_resume = cache[jd_id]
        else:
            tpl = random.choice(templates)
            hypo_resume = generator.generate_single(real_job, tpl)
            cache[jd_id] = hypo_resume
            new_generated += 1

            if new_generated % 25 == 0:
                with open(cache_path, "w", encoding="utf-8") as f:
                    json.dump(cache, f, ensure_ascii=False, indent=2)

        augmented_job_text = f"{real_job}\n\n[HYPOTHETICAL RESUME]\n{hypo_resume}"
        augmented_rows.append({
            "jd_no": jd_id,
            "text_job": augmented_job_text
        })

    # Save final cache
    with open(cache_path, "w", encoding="utf-8") as f:
        json.dump(cache, f, ensure_ascii=False, indent=2)

    # Save augmented CSV
    out_file = output_csv or os.path.join(data_dir, "all_jd_hyre_augmented.csv")
    pd.DataFrame(augmented_rows).to_csv(out_file, index=False, encoding="utf-8")
    print(f"[+] Hoàn tất sinh HYRE! Đã lưu tại: {out_file} (Tổng số: {len(augmented_rows)}, Mới tạo: {new_generated})")

def main():
    parser = argparse.ArgumentParser(description="HYRE Generator for ConFit v2")
    parser.add_argument("--data_dir", type=str, default="./data/processed", help="Path to processed data directory")
    parser.add_argument("--output_csv", type=str, default=None, help="Output augmented JD CSV path")
    parser.add_argument("--provider", type=str, default="mock", choices=["gemini", "openai", "mock"], help="LLM Provider")
    parser.add_argument("--api_key", type=str, default=None, help="LLM API Key")
    parser.add_argument("--limit", type=int, default=None, help="Limit number of JDs to convert")
    args = parser.parse_args()

    run_hyre(args.data_dir, args.output_csv, args.provider, args.api_key, args.limit)

if __name__ == "__main__":
    main()
