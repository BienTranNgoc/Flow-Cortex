#!/usr/bin/env python3
"""
Training Pipeline for ConFit v2 (ACL 2025).
Trains Bi-Encoder with InfoNCE loss, In-Batch Negatives, and RUM Hard Negatives.
Supports PEFT LoRA, mixed precision fp16/bf16, and checkpoint saving.
"""

import os
import sys
import json
import argparse
import random
import numpy as np
import pandas as pd
import torch
from torch.utils.data import Dataset, DataLoader
from transformers import AutoTokenizer, get_linear_schedule_with_warmup
try:
    from tqdm import tqdm
except ImportError:
    def tqdm(iterable, *args, **kwargs):
        return iterable
from typing import Dict, List, Optional

# Add models to sys.path
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from models.confit_bi_encoder import ConFitBiEncoder

class ConFitPairDataset(Dataset):
    def __init__(
        self,
        resume_dict: Dict[str, str],
        job_dict: Dict[str, str],
        labeled_pairs: List[Dict],
        hard_negatives: Optional[Dict[str, List[str]]] = None,
        k_hard_negatives: int = 2
    ):
        # We train on positive pairs (satisfied == 1) using contrastive learning
        self.pairs = [p for p in labeled_pairs if p.get("satisfied", 1) == 1]
        self.resume_dict = resume_dict
        self.job_dict = job_dict
        self.hard_negatives = hard_negatives or {}
        self.k_hard = k_hard_negatives

    def __len__(self):
        return len(self.pairs)

    def __getitem__(self, idx):
        item = self.pairs[idx]
        uid = str(item["user_id"])
        jid = str(item["jd_no"])

        r_text = f"query: {self.resume_dict.get(uid, '')}"
        j_text = f"passage: {self.job_dict.get(jid, '')}"

        # Sample hard negatives if available
        hard_j_texts = []
        candidate_neg_jids = self.hard_negatives.get(uid, [])
        if candidate_neg_jids:
            sampled_neg_jids = random.sample(candidate_neg_jids, min(self.k_hard, len(candidate_neg_jids)))
            for neg_jid in sampled_neg_jids:
                if neg_jid in self.job_dict:
                    hard_j_texts.append(f"passage: {self.job_dict[neg_jid]}")

        # If not enough hard negatives, pick random job from corpus (not jid)
        while len(hard_j_texts) < self.k_hard:
            rand_jid = random.choice(list(self.job_dict.keys()))
            if rand_jid != jid:
                hard_j_texts.append(f"passage: {self.job_dict[rand_jid]}")

        return {
            "resume_text": r_text,
            "job_text": j_text,
            "hard_neg_texts": hard_j_texts
        }

def make_collate_fn(tokenizer, max_length: int = 512):
    def collate_fn(batch):
        r_texts = [b["resume_text"] for b in batch]
        j_texts = [b["job_text"] for b in batch]

        r_inputs = tokenizer(r_texts, padding=True, truncation=True, max_length=max_length, return_tensors="pt")
        j_inputs = tokenizer(j_texts, padding=True, truncation=True, max_length=max_length, return_tensors="pt")

        # Hard negatives: (B, K)
        k = len(batch[0]["hard_neg_texts"])
        all_hard_texts = []
        for b in batch:
            all_hard_texts.extend(b["hard_neg_texts"])

        hard_inputs_flat = tokenizer(all_hard_texts, padding=True, truncation=True, max_length=max_length, return_tensors="pt")
        b_size = len(batch)
        seq_len = hard_inputs_flat["input_ids"].size(1)

        hard_inputs = {
            "input_ids": hard_inputs_flat["input_ids"].view(b_size, k, seq_len),
            "attention_mask": hard_inputs_flat["attention_mask"].view(b_size, k, seq_len)
        }

        return {
            "resume_inputs": r_inputs,
            "job_inputs": j_inputs,
            "hard_neg_inputs": hard_inputs
        }
    return collate_fn

def train_epoch(model, dataloader, optimizer, scheduler, device, scaler=None):
    model.train()
    total_loss = 0.0

    pbar = tqdm(dataloader, desc="Training")
    for batch in pbar:
        optimizer.zero_grad()

        r_inputs = {k: v.to(device) for k, v in batch["resume_inputs"].items()}
        j_inputs = {k: v.to(device) for k, v in batch["job_inputs"].items()}
        h_inputs = {k: v.to(device) for k, v in batch["hard_neg_inputs"].items()}

        if scaler:
            with torch.cuda.amp.autocast():
                out = model(r_inputs, j_inputs, h_inputs)
                loss = out["loss"]
            scaler.scale(loss).backward()
            scaler.step(optimizer)
            scaler.update()
        else:
            out = model(r_inputs, j_inputs, h_inputs)
            loss = out["loss"]
            loss.backward()
            optimizer.step()

        if scheduler:
            scheduler.step()

        total_loss += loss.item()
        pbar.set_postfix({"loss": f"{loss.item():.4f}"})

    return total_loss / len(dataloader)

def main():
    parser = argparse.ArgumentParser(description="Train ConFit v2 Bi-Encoder")
    parser.add_argument("--data_dir", type=str, default="./data/processed", help="Directory of processed datasets")
    parser.add_argument("--pretrained_model", type=str, default="intfloat/multilingual-e5-base", help="Pretrained encoder backbone")
    parser.add_argument("--output_dir", type=str, default="./checkpoints/confit_v2", help="Directory to save model checkpoints")
    parser.add_argument("--hard_negatives", type=str, default=None, help="Path to hard_negatives.json from RUM")
    parser.add_argument("--epochs", type=int, default=5, help="Number of training epochs")
    parser.add_argument("--batch_size", type=int, default=8, help="Batch size per device")
    parser.add_argument("--lr", type=float, default=2e-5, help="Learning rate")
    parser.add_argument("--temperature", type=float, default=0.05, help="InfoNCE temperature tau")
    parser.add_argument("--use_lora", action="store_true", default=True, help="Use PEFT LoRA")
    parser.add_argument("--fp16", action="store_true", default=torch.cuda.is_available(), help="Use FP16 mixed precision")
    args = parser.parse_args()

    os.makedirs(args.output_dir, exist_ok=True)
    device = "cuda" if torch.cuda.is_available() else "cpu"
    print(f"[*] Chạy huấn luyện ConFit v2 trên: {device} (FP16: {args.fp16})")

    # Load data
    res_path = os.path.join(args.data_dir, "all_resume_full_desensitized_recruiting_data.csv")
    hyre_jd_path = os.path.join(args.data_dir, "all_jd_hyre_augmented.csv")
    raw_jd_path = os.path.join(args.data_dir, "all_jd_full_from_text_recruiting_data.csv")
    jd_path = hyre_jd_path if os.path.exists(hyre_jd_path) else raw_jd_path

    print(f"[-] Nạp Jobs từ: {jd_path}")
    df_res = pd.read_csv(res_path)
    df_jd = pd.read_csv(jd_path)
    resume_dict = dict(zip(df_res["user_id"].astype(str), df_res["text_resume"].astype(str)))
    job_dict = dict(zip(df_jd["jd_no"].astype(str), df_jd["text_job"].astype(str)))

    train_labels = []
    with open(os.path.join(args.data_dir, "train_labeled_data.jsonl"), "r", encoding="utf-8") as f:
        for l in f:
            if l.strip():
                train_labels.append(json.loads(l))

    hard_neg_map = {}
    if args.hard_negatives and os.path.exists(args.hard_negatives):
        with open(args.hard_negatives, "r", encoding="utf-8") as f:
            raw_hn = json.load(f)
            hard_neg_map = raw_hn.get("r_to_j", {})
        print(f"[*] Đã nạp {len(hard_neg_map)} danh sách RUM hard negatives.")

    tokenizer = AutoTokenizer.from_pretrained(args.pretrained_model)
    model = ConFitBiEncoder(
        pretrained_model_name=args.pretrained_model,
        temperature=args.temperature,
        use_lora=args.use_lora
    ).to(device)

    dataset = ConFitPairDataset(resume_dict, job_dict, train_labels, hard_negatives=hard_neg_map, k_hard_negatives=2)
    collate_fn = make_collate_fn(tokenizer, max_length=512)
    dataloader = DataLoader(dataset, batch_size=args.batch_size, shuffle=True, collate_fn=collate_fn)

    optimizer = torch.optim.AdamW(filter(lambda p: p.requires_grad, model.parameters()), lr=args.lr, weight_decay=1e-2)
    total_steps = len(dataloader) * args.epochs
    scheduler = get_linear_schedule_with_warmup(optimizer, num_warmup_steps=int(0.1 * total_steps), num_training_steps=total_steps)
    scaler = torch.cuda.amp.GradScaler() if (args.fp16 and device == "cuda") else None

    print(f"[*] Bắt đầu huấn luyện {args.epochs} epochs ({total_steps} steps tổng cộng)...")
    best_loss = float("inf")

    for epoch in range(1, args.epochs + 1):
        print(f"\n--- Epoch {epoch}/{args.epochs} ---")
        avg_loss = train_epoch(model, dataloader, optimizer, scheduler, device, scaler)
        print(f"[+] Epoch {epoch} hoàn thành. Average Loss = {avg_loss:.4f}")

        # Save checkpoint
        ckpt_path = os.path.join(args.output_dir, f"epoch_{epoch}")
        os.makedirs(ckpt_path, exist_ok=True)
        tokenizer.save_pretrained(ckpt_path)

        # Save model
        if hasattr(model.encoder, "save_pretrained"):
            model.encoder.save_pretrained(ckpt_path)
        else:
            torch.save(model.state_dict(), os.path.join(ckpt_path, "model.pt"))

        if avg_loss < best_loss:
            best_loss = avg_loss
            best_dir = os.path.join(args.output_dir, "best_model")
            os.makedirs(best_dir, exist_ok=True)
            tokenizer.save_pretrained(best_dir)
            if hasattr(model.encoder, "save_pretrained"):
                model.encoder.save_pretrained(best_dir)
            else:
                torch.save(model.state_dict(), os.path.join(best_dir, "model.pt"))
            print(f"[*] Đã lưu Best Model tại: {best_dir} (Loss = {best_loss:.4f})")

    print(f"\n[+] Huấn luyện ConFit v2 hoàn tất! Best Checkpoint: {os.path.join(args.output_dir, 'best_model')}")

if __name__ == "__main__":
    main()
