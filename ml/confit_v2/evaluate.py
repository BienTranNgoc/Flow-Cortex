#!/usr/bin/env python3
"""
Evaluation & Ablation Study Suite for ConFit v2 (ACL 2025).
Computes ranking metrics: Recall@10, Recall@100, nDCG@10, nDCG@100, MRR, Spearman Rho.
Supports comparative benchmarking across:
  1. BM25 baseline
  2. Zero-shot Pretrained Encoder
  3. Fine-tuned ConFit v2 (HYRE + RUM)
"""

import os
import sys
import json
import math
import argparse
import numpy as np
import pandas as pd
try:
    import torch
    from transformers import AutoTokenizer, AutoModel
    HAS_TORCH = True
except ImportError:
    HAS_TORCH = False
from typing import Dict, List, Tuple

def compute_dcg(relevances: List[int], k: int) -> float:
    """Compute Discounted Cumulative Gain at rank K."""
    dcg = 0.0
    for i, rel in enumerate(relevances[:k]):
        dcg += (2**rel - 1) / math.log2(i + 2)
    return dcg

def compute_ndcg(retrieved_ids: List[str], ground_truth: Dict[str, int], k: int) -> float:
    """Compute Normalized DCG at K."""
    relevances = [ground_truth.get(did, 0) for did in retrieved_ids]
    actual_dcg = compute_dcg(relevances, k)
    
    ideal_relevances = sorted(list(ground_truth.values()), reverse=True)
    ideal_dcg = compute_dcg(ideal_relevances, k)

    if ideal_dcg == 0:
        return 0.0
    return actual_dcg / ideal_dcg

def compute_recall_at_k(retrieved_ids: List[str], ground_truth: Dict[str, int], k: int) -> float:
    """Compute Recall@K."""
    relevant_ids = set([did for did, rel in ground_truth.items() if rel > 0])
    if not relevant_ids:
        return 0.0
    retrieved_k = set(retrieved_ids[:k])
    hits = len(relevant_ids.intersection(retrieved_k))
    return hits / len(relevant_ids)

def compute_mrr(retrieved_ids: List[str], ground_truth: Dict[str, int]) -> float:
    """Compute Mean Reciprocal Rank."""
    for rank, did in enumerate(retrieved_ids, 1):
        if ground_truth.get(did, 0) > 0:
            return 1.0 / rank
    return 0.0

class SimpleBM25:
    """Lightweight Okapi BM25 implementation for baseline comparison."""
    def __init__(self, corpus: Dict[str, str], k1=1.5, b=0.75):
        self.doc_ids = list(corpus.keys())
        self.corpus = [corpus[k].lower().split() for k in self.doc_ids]
        self.k1 = k1
        self.b = b
        self.doc_lens = [len(doc) for doc in self.corpus]
        self.avg_doc_len = sum(self.doc_lens) / max(1, len(self.doc_lens))
        self.df = {}
        for doc in self.corpus:
            for word in set(doc):
                self.df[word] = self.df.get(word, 0) + 1
        self.idf = {w: math.log((len(self.corpus) - f + 0.5) / (f + 0.5) + 1.0) for w, f in self.df.items()}

    def search(self, query: str) -> List[Tuple[str, float]]:
        q_tokens = query.lower().split()
        scores = np.zeros(len(self.corpus))
        for w in q_tokens:
            if w not in self.idf:
                continue
            idf = self.idf[w]
            for i, doc in enumerate(self.corpus):
                freq = doc.count(w)
                num = freq * (self.k1 + 1)
                den = freq + self.k1 * (1 - self.b + self.b * (self.doc_lens[i] / self.avg_doc_len))
                scores[i] += idf * (num / den)
        ranked_indices = np.argsort(scores)[::-1]
        return [self.doc_ids[idx] for idx in ranked_indices]

def evaluate_rank_task(queries: Dict[str, str], candidates: Dict[str, str], rank_ground_truth: Dict[str, Dict[str, int]], encoder=None, bm25=None) -> Dict[str, float]:
    recalls_10 = []
    recalls_100 = []
    ndcgs_10 = []
    ndcgs_100 = []
    mrrs = []

    cand_keys = list(candidates.keys())

    if encoder:
        tokenizer, model, device = encoder
        # Encode all candidates
        cand_texts = [candidates[k] for k in cand_keys]
        cand_embeddings = []
        for i in range(0, len(cand_texts), 32):
            b_texts = cand_texts[i:i+32]
            enc = tokenizer(b_texts, padding=True, truncation=True, max_length=512, return_tensors="pt").to(device)
            with torch.no_grad():
                out = model(**enc)
                tok_emb = out.last_hidden_state if hasattr(out, "last_hidden_state") else out[0]
                mask = enc["attention_mask"].unsqueeze(-1)
                emb = torch.sum(tok_emb * mask, dim=1) / torch.clamp(mask.sum(dim=1), min=1e-9)
                emb = torch.nn.functional.normalize(emb, p=2, dim=1)
                cand_embeddings.append(emb.cpu().numpy())
        cand_matrix = np.vstack(cand_embeddings)

    for qid, gt_dict in rank_ground_truth.items():
        if qid not in queries:
            continue
        q_text = queries[qid]

        if encoder:
            tokenizer, model, device = encoder
            enc = tokenizer([q_text], padding=True, truncation=True, max_length=512, return_tensors="pt").to(device)
            with torch.no_grad():
                out = model(**enc)
                tok_emb = out.last_hidden_state if hasattr(out, "last_hidden_state") else out[0]
                mask = enc["attention_mask"].unsqueeze(-1)
                q_emb = torch.sum(tok_emb * mask, dim=1) / torch.clamp(mask.sum(dim=1), min=1e-9)
                q_emb = torch.nn.functional.normalize(q_emb, p=2, dim=1).cpu().numpy()

            sims = np.dot(cand_matrix, q_emb.T).flatten()
            ranked_idx = np.argsort(sims)[::-1]
            retrieved_ids = [cand_keys[i] for i in ranked_idx]
        elif bm25:
            retrieved_ids = bm25.search(q_text)

        recalls_10.append(compute_recall_at_k(retrieved_ids, gt_dict, 10))
        recalls_100.append(compute_recall_at_k(retrieved_ids, gt_dict, 100))
        ndcgs_10.append(compute_ndcg(retrieved_ids, gt_dict, 10))
        ndcgs_100.append(compute_ndcg(retrieved_ids, gt_dict, 100))
        mrrs.append(compute_mrr(retrieved_ids, gt_dict))

    return {
        "Recall@10": np.mean(recalls_10) * 100 if recalls_10 else 0.0,
        "Recall@100": np.mean(recalls_100) * 100 if recalls_100 else 0.0,
        "nDCG@10": np.mean(ndcgs_10) * 100 if ndcgs_10 else 0.0,
        "nDCG@100": np.mean(ndcgs_100) * 100 if ndcgs_100 else 0.0,
        "MRR": np.mean(mrrs) * 100 if mrrs else 0.0
    }

def main():
    parser = argparse.ArgumentParser(description="Evaluate ConFit v2 and Baselines")
    parser.add_argument("--data_dir", type=str, default="./data/processed", help="Path to processed datasets")
    parser.add_argument("--model_path", type=str, default=None, help="Path to fine-tuned model checkpoint")
    parser.add_argument("--baseline_model", type=str, default="intfloat/multilingual-e5-base", help="Zero-shot model")
    args = parser.parse_args()

    # Load test data
    res_path = os.path.join(args.data_dir, "all_resume_full_desensitized_recruiting_data.csv")
    jd_path = os.path.join(args.data_dir, "all_jd_full_from_text_recruiting_data.csv")
    rank_resume_path = os.path.join(args.data_dir, "rank_resume.json")

    df_res = pd.read_csv(res_path)
    df_jd = pd.read_csv(jd_path)
    resume_dict = dict(zip(df_res["user_id"].astype(str), df_res["text_resume"].astype(str)))
    job_dict = dict(zip(df_jd["jd_no"].astype(str), df_jd["text_job"].astype(str)))

    with open(rank_resume_path, "r", encoding="utf-8") as f:
        rank_resume_gt = json.load(f)

    device = "cuda" if (HAS_TORCH and torch.cuda.is_available()) else "cpu"
    results = []

    # 1. BM25 Baseline
    print("[-] Đang đánh giá Baseline 1: BM25...")
    bm25 = SimpleBM25(resume_dict)
    res_bm25 = evaluate_rank_task(job_dict, resume_dict, rank_resume_gt, bm25=bm25)
    results.append({"Method": "BM25", "Encoder": "-", **res_bm25})

    # 2. Zero-shot Baseline
    if HAS_TORCH and args.baseline_model and args.baseline_model.lower() != "none":
        print(f"[-] Đang đánh giá Baseline 2: Zero-shot ({args.baseline_model})...")
        tokenizer_base = AutoTokenizer.from_pretrained(args.baseline_model)
        model_base = AutoModel.from_pretrained(args.baseline_model).to(device).eval()
        res_zero = evaluate_rank_task(job_dict, resume_dict, rank_resume_gt, encoder=(tokenizer_base, model_base, device))
        results.append({"Method": "RawEmbed (Zero-shot)", "Encoder": args.baseline_model, **res_zero})

    # 3. Fine-tuned ConFit v2
    if HAS_TORCH and args.model_path and os.path.exists(args.model_path):
        print(f"[-] Đang đánh giá ConFit v2 (Fine-tuned): {args.model_path}...")
        tokenizer_ft = AutoTokenizer.from_pretrained(args.model_path)
        model_ft = AutoModel.from_pretrained(args.model_path).to(device).eval()
        res_confit = evaluate_rank_task(job_dict, resume_dict, rank_resume_gt, encoder=(tokenizer_ft, model_ft, device))
        results.append({"Method": "ConFit v2 (Ours)", "Encoder": "Fine-tuned mE5 + HYRE + RUM", **res_confit})

    # Format table
    df_results = pd.DataFrame(results)
    print("\n" + "=" * 80)
    print("BẢNG THỰC NGHIỆM ABLATION STUDY & SO SÁNH BASELINE (CONfit V2 - ACL 2025)")
    print("=" * 80)
    try:
        print(df_results.to_markdown(index=False))
    except Exception:
        print(df_results.to_string(index=False))
    print("=" * 80)

    # Save to CSV and Markdown
    out_csv = os.path.join(args.data_dir, "ablation_study_results.csv")
    df_results.to_csv(out_csv, index=False)
    print(f"[+] Kết quả lưu tại: {out_csv}")

if __name__ == "__main__":
    main()
