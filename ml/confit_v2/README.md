# ConFit v2 (ACL 2025) Training & Evaluation Track — Flow-Cortex CheckCV

Pipeline huấn luyện và đánh giá mô hình **ConFit v2** (Findings of ACL 2025) phục vụ bài toán **Person-Job Fit (Khớp hồ sơ CV và tin tuyển dụng JD)**, tối ưu cho:
1. **Bài tập lớn môn Xử lý Ngôn ngữ Tự nhiên (NLP):** Huấn luyện Bi-Encoder với kỹ thuật **HYRE** (Hypothetical Resume Embedding), **RUM** (Runner-Up Hard-Negative Mining), và hàm loss **InfoNCE Contrastive Learning**; xuất đầy đủ bảng thực nghiệm **Ablation Study**.
2. **Tích hợp thực chiến vào Flow-Cortex:** Xuất mô hình sang định dạng **ONNX INT8** (~280 MB) chạy trực tiếp trên CPU của container `media-worker` với độ trễ `< 150 ms`, không cần GPU trong môi trường production.

---

## 1. Cấu trúc Thư mục

```
ml/confit_v2/
├── README.md                      # Hướng dẫn chi tiết chạy end-to-end
├── requirements.txt               # Thư viện phụ thuộc (PyTorch, Transformers, PEFT, FAISS, ONNX)
├── data/
│   ├── prepare_datasets.py        # Tải cnamuangtoun từ HuggingFace + nạp mẫu song ngữ VN
│   └── sample_vn_jobs.json        # Dữ liệu mẫu tin tuyển dụng IT & CV song ngữ Việt - Anh
├── hyre/
│   └── generate_hyre.py           # Sinh Hypothetical Resume bằng LLM (Gemini / OpenAI / Mock)
├── mining/
│   └── mine_runner_up.py          # Triển khai thuật toán RUM (Runner-Up Hard-Negative Mining Top 3%-4%)
├── models/
│   └── confit_bi_encoder.py       # Kiến trúc ConFit* Bi-Encoder (Mean Pooling + L2 Normalization)
├── train.py                       # Kịch bản huấn luyện chính (PyTorch + PEFT LoRA + InfoNCE Loss)
├── evaluate.py                    # Đánh giá & xuất bảng Ablation Study (Recall@K, nDCG@K, MRR, Spearman)
├── export_onnx.py                 # Merge LoRA -> Xuất ONNX FP32/INT8 + Kiểm tra sai số Cosine + Benchmark CPU
└── notebooks/
    └── train_confit_v2_colab.ipynb # Google Colab One-Click Notebook (Chạy trực tiếp trên GPU T4 miễn phí)
```

---

## 2. Quy trình Thực thi (End-to-End Workflow)

```
[1. Chuẩn bị Dữ liệu]
    python data/prepare_datasets.py --output_dir ./data/processed
         │
         ▼
[2. Sinh CV Giả định HYRE]
    python hyre/generate_hyre.py --data_dir ./data/processed --provider gemini --api_key $GEMINI_API_KEY
         │
         ▼
[3. Huấn luyện Vòng 1 (Warm-up với In-Batch Negatives)]
    python train.py --data_dir ./data/processed --output_dir ./checkpoints/warmup --epochs 3 --lr 2e-5
         │
         ▼
[4. Khai thác Mẫu âm Khó RUM (Top 3% - 4%)]
    python mining/mine_runner_up.py --model_path ./checkpoints/warmup/best_model --data_dir ./data/processed --output ./data/processed/hard_negatives.json
         │
         ▼
[5. Huấn luyện Vòng 2 Chính thức (ConFit v2 Full: HYRE + RUM)]
    python train.py --data_dir ./data/processed --hard_negatives ./data/processed/hard_negatives.json --output_dir ./checkpoints/confit_v2_full --epochs 5
         │
         ▼
[6. Đánh giá & Xuất Bảng Ablation Study]
    python evaluate.py --model_path ./checkpoints/confit_v2_full/best_model --data_dir ./data/processed
         │
         ▼
[7. Đóng gói ONNX INT8 cho Flow-Cortex]
    python export_onnx.py --model_path ./checkpoints/confit_v2_full/best_model --output_dir ../../worker/models/confit_v2_onnx
```

---

## 3. Chạy Nhanh trên Google Colab (GPU T4 Miễn phí)

Mở notebook `notebooks/train_confit_v2_colab.ipynb` trên Google Colab:
1. Chọn Runtime: **GPU T4** (miễn phí).
2. Chạy toàn bộ các cells theo thứ tự từ trên xuống dưới.
3. Tải về file `confit_v2_int8.onnx` và bảng số liệu Ablation Study cho báo cáo BTL NLP.
