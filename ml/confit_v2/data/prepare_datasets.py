#!/usr/bin/env python3
"""
Data Preparation Script for ConFit v2 (ACL 2025).
Converts HuggingFace benchmark dataset (cnamuangtoun/resume-job-description-fit)
and Vietnamese-English bilingual jobs into the exact schema expected by ConFit v2:
  - all_jd_full_from_text_recruiting_data.csv (columns: jd_no, text_job)
  - all_resume_full_desensitized_recruiting_data.csv (columns: user_id, text_resume)
  - train_labeled_data.jsonl
  - valid_classification_data.jsonl
  - test_classification_data.jsonl
  - rank_resume.json
  - rank_job.json
  - templates.json (for HYRE few-shot prompts)

Group Split by Resume ID: 80% Train / 10% Valid / 10% Test (0% Data Leakage).
"""

import os
import sys
import json
import hashlib
import argparse
import random
import pandas as pd
from typing import Dict, List, Tuple

def generate_hash_id(prefix: str, text: str) -> str:
    """Generate deterministic 32-char hex ID from text content."""
    h = hashlib.sha256(text.encode("utf-8")).hexdigest()[:32]
    return f"{prefix}_{h}"

def load_huggingface_data(max_samples: int = 8000) -> List[Dict]:
    """Load cnamuangtoun/resume-job-description-fit or fallback to synthetic data if offline."""
    print("[-] Đang nạp dữ liệu từ HuggingFace (cnamuangtoun/resume-job-description-fit)...")
    records = []
    try:
        from datasets import load_dataset
        ds = load_dataset("cnamuangtoun/resume-job-description-fit", split="train")
        print(f"[*] Đã tải thành công {len(ds)} bản ghi từ HuggingFace.")
        count = 0
        for item in ds:
            # Handle dataset column naming variations
            resume = item.get("resume_text") or item.get("resume") or item.get("text_resume") or ""
            jd = item.get("job_description_text") or item.get("job_description") or item.get("text_job") or ""
            label_raw = item.get("label") or item.get("fit") or item.get("rating")
            
            if not resume or not jd:
                continue
                
            # Normalize label: 1 = Good Fit (positive), 0 = No Fit (negative)
            if isinstance(label_raw, str):
                label = 1 if "good" in label_raw.lower() or "high" in label_raw.lower() else 0
            elif isinstance(label_raw, (int, float)):
                label = 1 if label_raw >= 3 or label_raw == 1 else 0
            else:
                label = 1

            records.append({
                "resume_text": resume.strip(),
                "job_text": jd.strip(),
                "label": label
            })
            count += 1
            if count >= max_samples:
                break
    except Exception as e:
        print(f"[!] Không thể tải trực tiếp từ HuggingFace (hoặc chế độ offline): {e}")
        print("[*] Tự động sinh tập dữ liệu mẫu chuẩn hóa để đảm bảo pipeline chạy độc lập...")
        records = generate_fallback_samples(min(max_samples, 200))

    return records

def generate_fallback_samples(n: int = 150) -> List[Dict]:
    """Generate realistic fallback resume-job pairs for standalone testing."""
    roles = [
        ("Python Backend Developer", "FastAPI, PostgreSQL, Redis, Docker, Celery, REST API", "Bachelor CS. 3 years building scalable backend services with Python, FastAPI and Postgres."),
        ("React Frontend Developer", "React, TypeScript, Redux, Tailwind CSS, Vite, RESTful API", "BS in IT. 2 years experience building responsive web interfaces with React and TypeScript."),
        ("DevOps / Cloud Engineer", "Kubernetes, AWS, Terraform, Docker, CI/CD GitHub Actions, Linux", "B.Tech Computer Science. 4 years managing cloud infrastructure on AWS with Terraform and K8s."),
        ("Data Scientist", "Python, PyTorch, Scikit-learn, SQL, Pandas, Machine Learning, NLP", "Master in Data Science. Built recommendation systems and NLP pipelines using Transformers."),
        ("Digital Marketing Lead", "SEO, SEM, Google Ads, Content Marketing, Social Media Analytics", "BA in Marketing. 4 years driving user acquisition campaigns across digital channels.")
    ]
    records = []
    for i in range(n):
        role_pos, skills_pos, exp_pos = random.choice(roles)
        role_neg, skills_neg, exp_neg = random.choice([r for r in roles if r[0] != role_pos])
        
        jd_text = f"Job Title: {role_pos} #{i}\nRequirements: Required skills include {skills_pos}. Experience in agile teams, git workflow and high-availability systems."
        
        # Positive pair
        res_pos = f"## Profile: Candidate for {role_pos} #{i}\n## Skills: {skills_pos}, Problem Solving, Teamwork.\n## Experience: {exp_pos}"
        records.append({"resume_text": res_pos, "job_text": jd_text, "label": 1})
        
        # Negative pair
        res_neg = f"## Profile: Candidate #{i+1000}\n## Skills: {skills_neg}, Communication.\n## Experience: {exp_neg}"
        records.append({"resume_text": res_neg, "job_text": jd_text, "label": 0})
        
    return records

def load_vietnamese_bilingual_data(sample_file: str) -> List[Dict]:
    """Load Vietnamese-English bilingual IT jobs and resumes."""
    if not os.path.exists(sample_file):
        return []
    print(f"[-] Đang nạp dữ liệu IT song ngữ Việt - Anh từ {sample_file}...")
    records = []
    with open(sample_file, "r", encoding="utf-8") as f:
        vn_data = json.load(f)
        for job in vn_data:
            jd_text = f"Tiêu đề: {job['title']}\nCông ty: {job['company']}\nCấp bậc: {job['seniority']}\n{job['description']}"
            for res in job.get("matched_resumes", []):
                records.append({
                    "resume_text": res["text"],
                    "job_text": jd_text,
                    "label": int(res["label"])
                })
    print(f"[*] Đã bổ sung {len(records)} cặp dữ liệu song ngữ Việt - Anh.")
    return records

def process_and_split(all_records: List[Dict], output_dir: str):
    """Process records into ConFit v2 files with Group Split by Resume ID."""
    os.makedirs(output_dir, exist_ok=True)
    
    unique_resumes: Dict[str, str] = {} # user_id -> resume_text
    unique_jobs: Dict[str, str] = {}    # jd_no -> job_text
    labeled_pairs = []

    for r in all_records:
        r_text = r["resume_text"]
        j_text = r["job_text"]
        label = r["label"]

        user_id = generate_hash_id("user", r_text)
        jd_no = generate_hash_id("jd", j_text)

        unique_resumes[user_id] = r_text
        unique_jobs[jd_no] = j_text

        labeled_pairs.append({
            "user_id": user_id,
            "jd_no": jd_no,
            "satisfied": label
        })

    print(f"[*] Tổng số CV duy nhất: {len(unique_resumes)}")
    print(f"[*] Tổng số JD duy nhất: {len(unique_jobs)}")
    print(f"[*] Tổng số cặp tương tác gán nhãn: {len(labeled_pairs)}")

    # 1. Export CSV files
    df_jd = pd.DataFrame([{"jd_no": k, "text_job": v} for k, v in unique_jobs.items()])
    jd_csv_path = os.path.join(output_dir, "all_jd_full_from_text_recruiting_data.csv")
    df_jd.to_csv(jd_csv_path, index=False, encoding="utf-8")

    df_res = pd.DataFrame([{"user_id": k, "text_resume": v} for k, v in unique_resumes.items()])
    res_csv_path = os.path.join(output_dir, "all_resume_full_desensitized_recruiting_data.csv")
    df_res.to_csv(res_csv_path, index=False, encoding="utf-8")

    # 2. Group Split by Resume ID (80% Train, 10% Valid, 10% Test)
    all_user_ids = list(unique_resumes.keys())
    random.seed(42)
    random.shuffle(all_user_ids)

    n_users = len(all_user_ids)
    n_train = int(0.80 * n_users)
    n_valid = int(0.10 * n_users)

    train_user_set = set(all_user_ids[:n_train])
    valid_user_set = set(all_user_ids[n_train:n_train + n_valid])
    test_user_set = set(all_user_ids[n_train + n_valid:])

    train_pairs = [p for p in labeled_pairs if p["user_id"] in train_user_set]
    valid_pairs = [p for p in labeled_pairs if p["user_id"] in valid_user_set]
    test_pairs = [p for p in labeled_pairs if p["user_id"] in test_user_set]

    # Save JSONL files
    for split_name, split_data in [
        ("train_labeled_data.jsonl", train_pairs),
        ("valid_classification_data.jsonl", valid_pairs),
        ("test_classification_data.jsonl", test_pairs)
    ]:
        path = os.path.join(output_dir, split_name)
        with open(path, "w", encoding="utf-8") as f:
            for item in split_data:
                f.write(json.dumps(item, ensure_ascii=False) + "\n")

    # 3. Build rank_resume.json and rank_job.json for ranking evaluation
    # rank_resume: query is job, targets are candidate resumes
    rank_resume = {}
    for p in test_pairs:
        jid = p["jd_no"]
        uid = p["user_id"]
        val = p["satisfied"]
        if jid not in rank_resume:
            rank_resume[jid] = {}
        rank_resume[jid][uid] = val

    with open(os.path.join(output_dir, "rank_resume.json"), "w", encoding="utf-8") as f:
        json.dump(rank_resume, f, indent=2, ensure_ascii=False)

    # rank_job: query is resume, targets are candidate jobs
    rank_job = {}
    for p in test_pairs:
        jid = p["jd_no"]
        uid = p["user_id"]
        val = p["satisfied"]
        if uid not in rank_job:
            rank_job[uid] = {}
        rank_job[uid][jid] = val

    with open(os.path.join(output_dir, "rank_job.json"), "w", encoding="utf-8") as f:
        json.dump(rank_job, f, indent=2, ensure_ascii=False)

    # 4. Generate templates.json for HYRE few-shot prompt
    templates = []
    positive_train = [p for p in train_pairs if p["satisfied"] == 1]
    sample_templates = random.sample(positive_train, min(5, len(positive_train)))
    for p in sample_templates:
        templates.append({
            "job": unique_jobs[p["jd_no"]],
            "resume": unique_resumes[p["user_id"]]
        })
    with open(os.path.join(output_dir, "templates.json"), "w", encoding="utf-8") as f:
        json.dump(templates, f, indent=2, ensure_ascii=False)

    print(f"[+] Hoàn tất xuất dữ liệu tại: {output_dir}")
    print(f"    - Train pairs: {len(train_pairs)}")
    print(f"    - Valid pairs: {len(valid_pairs)}")
    print(f"    - Test pairs : {len(test_pairs)}")

def main():
    parser = argparse.ArgumentParser(description="Prepare dataset for ConFit v2")
    parser.add_argument("--output_dir", type=str, default="./data/processed", help="Directory to save processed files")
    parser.add_argument("--vn_sample_file", type=str, default="./data/sample_vn_jobs.json", help="Path to Vietnamese bilingual sample file")
    parser.add_argument("--max_samples", type=int, default=8000, help="Max samples from HuggingFace")
    args = parser.parse_args()

    # Locate relative to script directory if needed
    script_dir = os.path.dirname(os.path.abspath(__file__))
    vn_file = os.path.join(script_dir, "sample_vn_jobs.json") if not os.path.isabs(args.vn_sample_file) else args.vn_sample_file

    hf_records = load_huggingface_data(max_samples=args.max_samples)
    vn_records = load_vietnamese_bilingual_data(vn_file)

    all_records = hf_records + vn_records
    random.seed(42)
    random.shuffle(all_records)

    out_dir = os.path.join(script_dir, "processed") if args.output_dir == "./data/processed" else args.output_dir
    process_and_split(all_records, out_dir)

if __name__ == "__main__":
    main()
