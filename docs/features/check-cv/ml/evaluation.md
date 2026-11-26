# Đánh giá mô hình Check CV

**Dự kiến.** Ghi kết quả thực nghiệm sau khi chạy trên tập cố định. Kế hoạch v4 đề xuất tập tiếng Anh và tập Việt–Anh.

- Matching so sánh mô hình chưa fine-tune với các cấu hình `InfoNCE`, `+HYRE` và `+RUM`. Ghi Accuracy, Macro-F1 và Spearman khi nhãn tương ứng hỗ trợ phép đo.
- Retrieval so sánh baseline từ khóa, encoder gốc, các biến thể huấn luyện và truy hồi kết hợp. Ghi NDCG@5, NDCG@10, MRR và Recall@15.
- Mỗi hàng kết quả cần phiên bản dữ liệu, phiên bản mô hình, cấu hình, commit, seed, kích thước tập, phép đo và liên kết đến đầu ra thực nghiệm. Dùng cùng tập và cách đo khi so sánh ablation.

Xem [huấn luyện](training.md) và [ca kiểm thử sản phẩm](../testing.md).
