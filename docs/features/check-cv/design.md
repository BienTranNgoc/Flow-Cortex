# Thiết kế Check CV

**Dự kiến theo kế hoạch Check CV v4, mục 5–7.** Cập nhật sơ đồ và liên kết tới mã, hợp đồng và migration khi triển khai.

```mermaid
sequenceDiagram
    actor C as Ứng viên
    participant A as Backend
    participant W as Worker
    participant J as Kho việc làm
    C->>A: Gửi CV và JD
    A->>W: Yêu cầu trích xuất và đánh giá
    W-->>A: Trả tín hiệu đánh giá
    A-->>C: Hiện bản sửa để duyệt
    C->>A: Xác nhận
    opt Bật gợi ý
        A->>J: Truy hồi tin phù hợp
        J-->>A: Trả ứng viên kết quả
    end
    A-->>C: Hiện kết quả
```

- Ưu tiên trích xuất PDF trong worker. Dự kiến chuyển sang xử lý ảnh nếu trích xuất văn bản không đủ. Cần xác minh ranh giới giữa fallback và yêu cầu che dữ liệu cá nhân trước khi gửi nội dung ra ngoài. Ca nghiệm thu phải kiểm tra payload tại ranh giới này.
- Mô hình dùng để tính tín hiệu phù hợp CV–JD và mã hóa tài liệu cho truy hồi. Dẫn phiên bản mô hình đang phục vụ về [quy trình huấn luyện](ml/training.md).
- Công cụ nhập kho việc làm dự kiến lưu nguồn và thời hạn của tin. Liên kết tới migration thực tế và cập nhật [ERD](../../ERD.md) khi có schema.
