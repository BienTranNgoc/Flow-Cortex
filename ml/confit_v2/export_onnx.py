#!/usr/bin/env python3
"""
ONNX Export & Dynamic INT8 Quantization for ConFit v2.
Exports fine-tuned PyTorch / LoRA model to ONNX for CPU-first inference in Flow-Cortex.
Verifies numerical parity (Cosine Similarity >= 0.999) and benchmarks CPU latency.
"""

import os
import sys
import time
import argparse
import numpy as np
import torch
import torch.nn as nn
import torch.nn.functional as F
from transformers import AutoTokenizer, AutoModel

class ConFitOnnxWrapper(nn.Module):
    """Clean inference wrapper performing Transformer encoding + Mean Pooling + L2 Normalization."""
    def __init__(self, model):
        super().__init__()
        self.model = model

    def forward(self, input_ids: torch.Tensor, attention_mask: torch.Tensor) -> torch.Tensor:
        outputs = self.model(input_ids=input_ids, attention_mask=attention_mask)
        token_embeddings = outputs.last_hidden_state if hasattr(outputs, "last_hidden_state") else outputs[0]
        
        # Mean pooling
        input_mask_expanded = attention_mask.unsqueeze(-1).expand(token_embeddings.size()).float()
        sum_embeddings = torch.sum(token_embeddings * input_mask_expanded, dim=1)
        sum_mask = torch.clamp(input_mask_expanded.sum(dim=1), min=1e-9)
        pooled = sum_embeddings / sum_mask
        
        # L2 Normalize
        return F.normalize(pooled, p=2, dim=1)

def export_to_onnx(model_path: str, output_dir: str, quantize_int8: bool = True):
    os.makedirs(output_dir, exist_ok=True)
    print(f"[-] Đang nạp mô hình từ: {model_path}...")

    tokenizer = AutoTokenizer.from_pretrained(model_path)
    base_model = AutoModel.from_pretrained(model_path)

    # If model is a PEFT LoRA model, merge weights into base model
    try:
        from peft import PeftModel
        if isinstance(base_model, PeftModel) or os.path.exists(os.path.join(model_path, "adapter_config.json")):
            print("[*] Phát hiện PEFT LoRA adapter. Đang merge weights vào backbone...")
            base_model = PeftModel.from_pretrained(AutoModel.from_pretrained(base_model.config._name_or_path), model_path)
            base_model = base_model.merge_and_unload()
            print("[+] Đã merge LoRA hoàn tất!")
    except Exception as e:
        print(f"[*] Không cần merge LoRA ({e}), tiếp tục xuất model.")

    base_model.eval()
    wrapper = ConFitOnnxWrapper(base_model)

    onnx_fp32_path = os.path.join(output_dir, "confit_v2_fp32.onnx")
    onnx_int8_path = os.path.join(output_dir, "confit_v2_int8.onnx")
    tokenizer_dir = output_dir

    # Save tokenizer for worker runtime
    tokenizer.save_pretrained(tokenizer_dir)

    # Dummy inputs for tracing
    dummy_text = ["query: Sample candidate CV text with software engineering skills.", "passage: Job title Senior Java Developer"]
    dummy_inputs = tokenizer(dummy_text, padding=True, truncation=True, max_length=128, return_tensors="pt")

    print(f"[-] Đang xuất mô hình ONNX FP32 sang: {onnx_fp32_path}...")
    torch.onnx.export(
        wrapper,
        (dummy_inputs["input_ids"], dummy_inputs["attention_mask"]),
        onnx_fp32_path,
        export_params=True,
        opset_version=14,
        do_constant_folding=True,
        input_names=["input_ids", "attention_mask"],
        output_names=["embeddings"],
        dynamic_axes={
            "input_ids": {0: "batch_size", 1: "sequence_length"},
            "attention_mask": {0: "batch_size", 1: "sequence_length"},
            "embeddings": {0: "batch_size"}
        }
    )

    size_mb = os.path.getsize(onnx_fp32_path) / (1024 * 1024)
    print(f"[+] Xuất thành công ONNX FP32! Kích thước: {size_mb:.2f} MB")

    final_model_path = onnx_fp32_path

    # Dynamic INT8 Quantization
    if quantize_int8:
        print("[-] Đang thực hiện Dynamic INT8 Quantization...")
        try:
            from onnxruntime.quantization import quantize_dynamic, QuantType
            quantize_dynamic(
                model_input=onnx_fp32_path,
                model_output=onnx_int8_path,
                weight_type=QuantType.QInt8
            )
            int8_size_mb = os.path.getsize(onnx_int8_path) / (1024 * 1024)
            print(f"[+] Lượng hóa INT8 thành công! Kích thước nén: {int8_size_mb:.2f} MB (Giảm {(1 - int8_size_mb/size_mb)*100:.1f}%)")
            final_model_path = onnx_int8_path
        except Exception as e:
            print(f"[!] Lỗi khi lượng hóa INT8: {e}. Sử dụng FP32.")

    # Verification: Cosine Parity & Benchmark Latency
    verify_and_benchmark(wrapper, final_model_path, tokenizer)

def verify_and_benchmark(pytorch_model, onnx_model_path: str, tokenizer, num_runs: int = 50):
    print("\n[-] Kiểm tra tính tương đương số học (Cosine Parity Check)...")
    import onnxruntime as ort

    session = ort.InferenceSession(onnx_model_path, providers=["CPUExecutionProvider"])

    test_sentences = [
        "query: Senior Java Spring Boot developer with 5 years experience in Microservices and Kubernetes.",
        "passage: Looking for a Lead Backend Engineer proficient in Java, PostgreSQL and RabbitMQ."
    ]
    inputs = tokenizer(test_sentences, padding=True, truncation=True, max_length=256, return_tensors="pt")

    # PyTorch inference
    with torch.no_grad():
        pt_out = pytorch_model(inputs["input_ids"], inputs["attention_mask"]).numpy()

    # ONNX inference
    ort_inputs = {
        "input_ids": inputs["input_ids"].numpy(),
        "attention_mask": inputs["attention_mask"].numpy()
    }
    ort_out = session.run(["embeddings"], ort_inputs)[0]

    # Compute cosine similarity between PyTorch and ONNX outputs
    cosine_sims = []
    for i in range(len(test_sentences)):
        cos = np.dot(pt_out[i], ort_out[i]) / (np.linalg.norm(pt_out[i]) * np.linalg.norm(ort_out[i]))
        cosine_sims.append(cos)
        print(f"    Sample {i+1} Cosine Similarity: {cos:.6f}")

    avg_cos = np.mean(cosine_sims)
    if avg_cos >= 0.999:
        print(f"[+] ĐẠT YÊU CẦU: Sai số cực nhỏ! Cosine Similarity trung bình: {avg_cos:.6f} >= 0.999")
    else:
        print(f"[!] CẢNH BÁO: Cosine Similarity thấp: {avg_cos:.6f}")

    # Latency benchmark on CPU
    print(f"\n[-] Đo độ trễ suy luận trên CPU ({num_runs} lượt chạy)...")
    latencies = []
    for _ in range(num_runs):
        t0 = time.perf_counter()
        session.run(["embeddings"], ort_inputs)
        t1 = time.perf_counter()
        latencies.append((t1 - t0) * 1000) # ms

    avg_lat = np.mean(latencies[5:]) # discard warmup
    p95_lat = np.percentile(latencies[5:], 95)
    print(f"[+] KẾT QUẢ ĐỘ TRỄ CPU (Batch size = {len(test_sentences)}):")
    print(f"    - Trung bình (Avg) : {avg_lat:.2f} ms")
    print(f"    - P95 Latency     : {p95_lat:.2f} ms")
    print(f"    - Đạt chuẩn Flow-Cortex (< 200ms CPU): {'✅ ĐẠT' if avg_lat < 200 else '❌ VƯỢT NGƯỠNG'}")

def main():
    parser = argparse.ArgumentParser(description="Export ConFit v2 to ONNX INT8")
    parser.add_argument("--model_path", type=str, default="intfloat/multilingual-e5-base", help="PyTorch model checkpoint or backbone name")
    parser.add_argument("--output_dir", type=str, default="../../worker/models/confit_v2_onnx", help="Directory to save ONNX model & tokenizer")
    parser.add_argument("--no_quantize", action="store_true", help="Disable INT8 dynamic quantization")
    args = parser.parse_args()

    export_to_onnx(args.model_path, args.output_dir, quantize_int8=(not args.no_quantize))

if __name__ == "__main__":
    main()
