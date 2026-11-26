# Check CV

**Trạng thái: Dự kiến.** Trang này ghi phạm vi sản phẩm đã mô tả trong kế hoạch Check CV v4. Các nhánh dưới đây cần xác nhận khi triển khai.

## UC-CV-01 — Đánh giá CV theo mô tả công việc

Ứng viên cung cấp một CV và một mô tả công việc (JD) mỗi lượt. Kết quả dự kiến gồm đánh giá độ phù hợp và đề xuất sửa CV để ứng viên duyệt. Gợi ý việc làm mặc định bật và có thể tắt trên biểu mẫu. Các màn hình cần mô tả khi triển khai gồm biểu mẫu khởi chạy, tiến độ, kết quả đánh giá và duyệt chỉnh sửa.

```mermaid
flowchart TD
    Input["Nộp một CV và một JD"] --> Parse["Trích xuất và kiểm tra nội dung"]
    Parse --> Valid{"Đủ dữ liệu để đánh giá?"}
    Valid -->|Không| Error["Báo vấn đề với đầu vào"]
    Valid -->|Có| Score["Đánh giá độ phù hợp"]
    Score --> Rewrite["Đề xuất sửa CV"]
    Rewrite --> Approval["Chờ ứng viên duyệt"]
    Approval --> Recommend{"Bật gợi ý việc làm?"}
    Recommend -->|Có| Search["Truy hồi việc làm phù hợp"]
    Recommend -->|Không| Result["Trả kết quả"]
    Search --> Result
```

Xem [thiết kế](design.md), [ca kiểm thử](testing.md), [huấn luyện](ml/training.md) và [đánh giá mô hình](ml/evaluation.md).
