#!/usr/bin/env python3
"""
RUM (Runner-Up Hard-Negative Mining) for ConFit v2 (ACL 2025).
Mines challenging hard-negatives from the 'runner-up' percentile range (Top 3% - 4%)
of cosine similarity scores to prevent false negatives from unlabeled positive pairs.
Outputs hard_negatives.json formatted for ConFit v2 dual-contrastive training.
"""

import os
import sys
import json
import argparse
import numpy as np
import pandas as pd
import torch
try:
    from tqdm import tqdm
except ImportError:
    def tqdm(iterable, *args, **kwargs):
        return iterable
from typing import Dict, List, Tuple

def load_data_dict(data_dir: str, use_hyre_jobs: bool = True) -> Tuple[Dict[str, str], Dict[str, str], pd.DataFrame]:
    res_path = os.path.join(data_dir, "all_resume_full_desensitized_recruiting_data.csv")
    
    hyre_jd_path = os.path.join(data_dir, "all_jd_hyre_augmented.csv")
    raw_jd_path = os.path.join(data_dir, "all_jd_full_from_text_recruiting_data.csv")
    jd_path = hyre_jd_path if (use_hyre_jobs and os.path.exists(hyre_jd_path)) else raw_jd_path
    
    label_path = os.path.join(data_dir, "train_labeled_data.jsonl")

    df_res = pd.read_csv(res_path)
    df_jd = pd.read_csv(jd_path)

    resume_dict = dict(zip(df_res["user_id"].astype(str), df_res["text_resume"].astype(str)))
    job_dict = dict(zip(df_jd["jd_no"].astype(str), df_jd["text_job"].astype(str)))

    labels = []
    with open(label_path, "r", encoding="utf-8") as f:
        for line in f:
            if line.strip():
                labels.append(json.loads(line))
    df_labels = pd.DataFrame(labels)

    return resume_dict, job_dict, df_labels

def encode_texts(model, tokenizer, texts: List[str], batch_size: int = 32, max_length: int = 512, device: str = "cpu") -> np.ndarray:
    """Encode list of texts into L2-normalized embeddings."""
    embeddings = []
    model.eval()
    
    for i in tqdm(range(0, len(texts), batch_size), desc="Encoding"):
        batch_texts = texts[i:i + batch_size]
        encoded = tokenizer(
            batch_texts,
            padding=True,
            truncation=True,
            max_length=max_length,
            return_tensors="pt"
        ).to(device)

        with torch.no_grad():
            if hasattr(model, "encode"):
                emb = model.encode(encoded["input_ids"], encoded["attention_mask"])
            else:
                outputs = model(**encoded)
                # Mean pooling
                token_embeddings = outputs.last_hidden_state
                attention_mask = encoded["attention_mask"].unsqueeze(-1)
                emb = torch.sum(token_embeddings * attention_mask, dim=1) / torch.clamp(attention_mask.sum(dim=1), min=1e-9)
            
            # L2 normalize
            emb = torch.nn.functional.normalize(emb, p=2, dim=1)
            embeddings.append(emb.cpu().numpy())

    return np.vstack(embeddings)

def calculate_runner_up_negatives(
    rid_matrix: np.ndarray,
    jid_matrix: np.ndarray,
    rid_keys: List[str],
    jid_keys: List[str],
    df_labels: pd.DataFrame,
    lower: float = 0.04,
    upper: float = 0.03
) -> Tuple[Dict[str, List[str]], Dict[str, List[str]]]:
    """Calculate runner-up hard negatives using FAISS or PyTorch Cosine."""
    try:
        import faiss
        has_faiss = True
    except ImportError:
        has_faiss = False

    num_jobs = len(jid_keys)
    num_resumes = len(rid_keys)

    # Dải percentile Top 3% - 4%
    idx_start_j = max(1, int(upper * num_jobs))
    idx_end_j = max(idx_start_j + 1, int(lower * num_jobs))

    idx_start_r = max(1, int(upper * num_resumes))
    idx_end_r = max(idx_start_r + 1, int(lower * num_resumes))

    r_to_j = {}
    j_to_r = {}

    if has_faiss:
        print(f"[*] Sử dụng FAISS IndexFlatIP cho ma trận (Resumes: {num_resumes}, Jobs: {num_jobs})...")
        # Resume to Job
        index_j = faiss.IndexFlatIP(jid_matrix.shape[1])
        index_j.add(jid_matrix.astype(np.float32))
        _, top_jids = index_j.search(rid_matrix.astype(np.float32), idx_end_j)
        for i, rid in enumerate(rid_keys):
            r_to_j[rid] = [jid_keys[idx] for idx in top_jids[i][idx_start_j:idx_end_j]]

        # Job to Resume
        index_r = faiss.IndexFlatIP(rid_matrix.shape[1])
        index_r.add(rid_matrix.astype(np.float32))
        _, top_rids = index_r.search(jid_matrix.astype(np.float32), idx_end_r)
        for i, jid in enumerate(jid_keys):
            j_to_r[jid] = [rid_keys[idx] for idx in top_rids[i][idx_start_r:idx_end_r]]
    else:
        print(f"[*] FAISS không khả dụng, sử dụng PyTorch Cosine Matrix...")
        rid_tensor = torch.tensor(rid_matrix)
        jid_tensor = torch.tensor(jid_matrix)
        sim_matrix = torch.matmul(rid_tensor, jid_tensor.T) # (num_resumes, num_jobs)

        for i, rid in enumerate(rid_keys):
            sorted_indices = torch.argsort(sim_matrix[i], descending=True).cpu().numpy()
            r_to_j[rid] = [jid_keys[idx] for idx in sorted_indices[idx_start_j:idx_end_j]]

        sim_matrix_t = sim_matrix.T # (num_jobs, num_resumes)
        for i, jid in enumerate(jid_keys):
            sorted_indices = torch.argsort(sim_matrix_t[i], descending=True).cpu().numpy()
            j_to_r[jid] = [rid_keys[idx] for idx in sorted_indices[idx_start_r:idx_end_r]]

    # Loại bỏ các cặp positive đã biết (satisfied == 1)
    positive_pairs = df_labels[df_labels["satisfied"] == 1]
    for _, row in positive_pairs.iterrows():
        uid = str(row["user_id"])
        jid = str(row["jd_no"])
        if uid in r_to_j and jid in r_to_j[uid]:
            r_to_j[uid].remove(jid)
        if jid in j_to_r and uid in j_to_r[jid]:
            j_to_r[jid].remove(uid)

    return r_to_j, j_to_r

def main():
    parser = argparse.ArgumentParser(description="Runner-Up Mining (RUM) for ConFit v2")
    parser.add_argument("--data_dir", type=str, default="./data/processed", help="Path to processed data directory")
    parser.add_argument("--model_name_or_path", type=str, default="intfloat/multilingual-e5-base", help="Encoder backbone or checkpoint path")
    parser.add_argument("--output_file", type=str, default=None, help="Output hard negatives JSON path")
    parser.add_argument("--batch_size", type=int, default=32, help="Encoding batch size")
    parser.add_argument("--lower", type=float, default=0.04, help="Lower percentile bound (e.g. 0.04 = Top 4%)")
    parser.add_argument("--upper", type=float, default=0.03, help="Upper percentile bound (e.g. 0.03 = Top 3%)")
    args = parser.parse_args()

    device = "cuda" if torch.cuda.is_available() else "cpu"
    print(f"[-] Khởi động RUM Mining trên thiết bị: {device}")

    resume_dict, job_dict, df_labels = load_data_dict(args.data_dir, use_hyre_jobs=True)
    rid_keys = list(resume_dict.keys())
    jid_keys = list(job_dict.keys())

    # Prepend prefixes per e5 specification
    res_texts = [f"query: {resume_dict[rid]}" for rid in rid_keys]
    job_texts = [f"passage: {job_dict[jid]}" for jid in jid_keys]

    from transformers import AutoTokenizer, AutoModel
    print(f"[-] Nạp mô hình: {args.model_name_or_path}...")
    tokenizer = AutoTokenizer.from_pretrained(args.model_name_or_path)
    model = AutoModel.from_pretrained(args.model_name_or_path).to(device)

    print("[-] Mã hóa tập Resumes...")
    rid_matrix = encode_texts(model, tokenizer, res_texts, batch_size=args.batch_size, device=device)
    print("[-] Mã hóa tập Jobs...")
    jid_matrix = encode_texts(model, tokenizer, job_texts, batch_size=args.batch_size, device=device)

    print(f"[-] Khai thác Runner-Up Negatives trong dải percentile [{args.upper * 100}% - {args.lower * 100}%]...")
    r_to_j, j_to_r = calculate_runner_up_negatives(
        rid_matrix, jid_matrix, rid_keys, jid_keys, df_labels,
        lower=args.lower, upper=args.upper
    )

    out_path = args.output_file or os.path.join(args.data_dir, "hard_negatives.json")
    with open(out_path, "w", encoding="utf-8") as f:
        json.dump({"r_to_j": r_to_j, "j_to_r": j_to_r}, f, indent=2, ensure_ascii=False)

    print(f"[+] Hoàn tất khai thác RUM! Đã lưu tập mẫu âm khó tại: {out_path}")
    sample_uid = rid_keys[0] if rid_keys else ""
    print(f"    - Mẫu hard negatives cho {sample_uid}: {r_to_j.get(sample_uid, [])[:3]}")

if __name__ == "__main__":
    main()
