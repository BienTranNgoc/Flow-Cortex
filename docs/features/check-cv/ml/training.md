# Huấn luyện mô hình Check CV

**Dự kiến theo kế hoạch Check CV v4, mục 3.** Hướng nghiên cứu dùng bi-encoder đa ngôn ngữ theo ConFit v2 cho matching và retrieval. Các biến thể dùng InfoNCE, bổ sung HYRE và khai thác mẫu âm khó RUM sẽ được đối chiếu với baseline.

Khi có lần chạy, ghi nguồn và giấy phép dữ liệu, phiên bản và mã băm, cách ẩn danh, khóa nhóm dùng để chia train/validation/test, seed, commit, cấu hình huấn luyện và phần cứng. Lưu lệnh hoặc notebook tái lập, phiên bản môi trường, thông số huấn luyện và mã băm bản ONNX đã xuất. Tập đánh giá cố định phải được giữ ngoài dữ liệu huấn luyện và các bước xây dựng mẫu âm.

Dùng [tài liệu đánh giá](evaluation.md) để đối chiếu kết quả và cấu hình được chọn.
