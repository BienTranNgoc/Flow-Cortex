#!/usr/bin/env python3
"""
ConFit* Bi-Encoder Architecture for ConFit v2 (ACL 2025).
Treats resume and job as single sequences of text, applies Transformer Encoder with
Mean Pooling and L2 Normalization, trained with InfoNCE Contrastive Loss.
Supports PEFT LoRA for efficient fine-tuning on a single GPU.
"""

import torch
import torch.nn as nn
import torch.nn.functional as F
from transformers import AutoModel, AutoTokenizer
from typing import Optional, Dict, Tuple, List

class ConFitBiEncoder(nn.Module):
    def __init__(
        self,
        pretrained_model_name: str = "intfloat/multilingual-e5-base",
        temperature: float = 0.05,
        use_lora: bool = True,
        lora_r: int = 16,
        lora_alpha: int = 32,
        lora_dropout: float = 0.05
    ):
        super().__init__()
        self.pretrained_model_name = pretrained_model_name
        self.temperature = temperature
        self.use_lora = use_lora

        print(f"[-] Nạp Transformer Backbone: {pretrained_model_name}...")
        self.encoder = AutoModel.from_pretrained(pretrained_model_name, trust_remote_code=True)

        if use_lora:
            try:
                from peft import LoraConfig, get_peft_model, TaskType
                # Adapt targets for different transformer backbones (BERT/RoBERTa/E5/Jina)
                target_modules = ["query", "key", "value", "dense"]
                if "jina" in pretrained_model_name.lower():
                    target_modules = ["q_proj", "k_proj", "v_proj", "out_proj"]

                peft_config = LoraConfig(
                    task_type=TaskType.FEATURE_EXTRACTION,
                    r=lora_r,
                    lora_alpha=lora_alpha,
                    lora_dropout=lora_dropout,
                    target_modules=target_modules,
                    bias="none"
                )
                self.encoder = get_peft_model(self.encoder, peft_config)
                print("[*] Đã gắn PEFT LoRA thành công vào Transformer Backbone:")
                self.encoder.print_trainable_parameters()
            except ImportError:
                print("[!] Thư viện 'peft' chưa được cài đặt. Tiếp tục với full fine-tuning.")
                self.use_lora = False

    def mean_pooling(self, token_embeddings: torch.Tensor, attention_mask: torch.Tensor) -> torch.Tensor:
        """Mean Pooling taking attention mask into account."""
        input_mask_expanded = attention_mask.unsqueeze(-1).expand(token_embeddings.size()).float()
        sum_embeddings = torch.sum(token_embeddings * input_mask_expanded, dim=1)
        sum_mask = torch.clamp(input_mask_expanded.sum(dim=1), min=1e-9)
        return sum_embeddings / sum_mask

    def encode(self, input_ids: torch.Tensor, attention_mask: torch.Tensor) -> torch.Tensor:
        """Encode tokenized input into L2-normalized document representation."""
        outputs = self.encoder(input_ids=input_ids, attention_mask=attention_mask)
        token_embeddings = outputs.last_hidden_state if hasattr(outputs, "last_hidden_state") else outputs[0]
        pooled = self.mean_pooling(token_embeddings, attention_mask)
        return F.normalize(pooled, p=2, dim=1)

    def compute_infonce_loss(
        self,
        resume_vecs: torch.Tensor,        # (B, D)
        job_pos_vecs: torch.Tensor,       # (B, D)
        job_hard_neg_vecs: Optional[torch.Tensor] = None # (B, K, D)
    ) -> torch.Tensor:
        """
        InfoNCE Loss with in-batch negatives and optional RUM hard negatives.
        Loss = L_{R -> J} + L_{J -> R}
        """
        batch_size = resume_vecs.size(0)

        # In-batch similarity matrix: (B, B)
        # sim[i, j] = cos(r_i, j_j) / tau
        in_batch_sim = torch.matmul(resume_vecs, job_pos_vecs.T) / self.temperature

        # Ground truth labels: diagonal elements (i, i) are positive pairs
        labels = torch.arange(batch_size, device=resume_vecs.device)

        if job_hard_neg_vecs is not None and job_hard_neg_vecs.numel() > 0:
            # job_hard_neg_vecs: (B, K, D)
            # Compute similarity with hard negatives: (B, K)
            # r_i * j_{i, k}^-
            hard_sim = torch.sum(resume_vecs.unsqueeze(1) * job_hard_neg_vecs, dim=-1) / self.temperature # (B, K)
            # Concatenate along candidate dimension: (B, B + K)
            extended_r2j_sim = torch.cat([in_batch_sim, hard_sim], dim=1)
            loss_r2j = F.cross_entropy(extended_r2j_sim, labels)
        else:
            loss_r2j = F.cross_entropy(in_batch_sim, labels)

        # Reverse direction: J -> R
        loss_j2r = F.cross_entropy(in_batch_sim.T, labels)

        return 0.5 * (loss_r2j + loss_j2r)

    def forward(
        self,
        resume_inputs: Dict[str, torch.Tensor],
        job_inputs: Dict[str, torch.Tensor],
        hard_neg_inputs: Optional[Dict[str, torch.Tensor]] = None
    ) -> Dict[str, torch.Tensor]:
        """Forward pass computing document embeddings and loss."""
        resume_vecs = self.encode(resume_inputs["input_ids"], resume_inputs["attention_mask"])
        job_pos_vecs = self.encode(job_inputs["input_ids"], job_inputs["attention_mask"])

        hard_neg_vecs = None
        if hard_neg_inputs is not None and "input_ids" in hard_neg_inputs:
            # Flatten (B, K, L) -> (B*K, L) for encoding
            b, k, l = hard_neg_inputs["input_ids"].shape
            flat_ids = hard_neg_inputs["input_ids"].view(b * k, l)
            flat_mask = hard_neg_inputs["attention_mask"].view(b * k, l)
            flat_vecs = self.encode(flat_ids, flat_mask)
            hard_neg_vecs = flat_vecs.view(b, k, -1)

        loss = self.compute_infonce_loss(resume_vecs, job_pos_vecs, hard_neg_vecs)

        return {
            "loss": loss,
            "resume_embeddings": resume_vecs,
            "job_embeddings": job_pos_vecs
        }
