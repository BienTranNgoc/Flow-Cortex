# Tạo video quảng cáo

## UC-PV-01 — Tạo video từ thông tin sản phẩm

Thành viên mở biểu mẫu tạo video, nhập thông tin sản phẩm và cung cấp hình tham chiếu theo yêu cầu của biểu mẫu. Khi workflow khả dụng, người dùng khởi chạy và theo dõi tiến độ. Workflow tạo kịch bản, chờ duyệt, sinh storyboard và lời đọc, tạo hai clip, rồi kết xuất video. Độ dài video là 10 giây theo [ProductVideoWorkflow](../../../backend/modules/workflow/src/main/java/com/flowcortex/workflow/api/ProductVideoWorkflow.java).

```mermaid
flowchart TD
    Form["Gửi thông tin sản phẩm"] --> Script["Sinh kịch bản"]
    Script --> Review{"Duyệt kịch bản"}
    Review -->|Sửa| Review
    Review -->|Duyệt| Story["Sinh storyboard"]
    Story --> Speech["Sinh lời đọc"]
    Speech --> First["Tạo clip 1"]
    Speech --> Second["Tạo clip 2"]
    First --> Render["Kết xuất khi đủ đầu vào"]
    Second --> Render
    First -.-> Failed["Lần chạy thất bại nếu một clip lỗi"]
    Second -.-> Failed
    Render --> Result["Xem video kết quả"]
```

```mermaid
sequenceDiagram
    participant Engine as Backend
    participant Queue as RabbitMQ
    participant Worker as Worker
    Engine->>Queue: Gửi hai tác vụ tạo clip
    Queue->>Worker: Chuyển tác vụ
    Worker-->>Engine: Trả kết quả clip 2 qua RabbitMQ
    Worker-->>Engine: Trả kết quả clip 1 qua RabbitMQ
    Engine->>Queue: Gửi render với clip 1 trước clip 2
```

Biểu mẫu khởi chạy và màn hình theo dõi là hai điểm tương tác chính. Khi một clip thất bại, backend có thể yêu cầu hủy clip còn lại và loại bỏ kết quả đến muộn. Xem [migration storyboard](../../../backend/boot/app/src/main/resources/db/migration/V2026.10.17.002__product_video_visual_storyboard.sql), [ca kiểm thử](testing.md) và [kiến trúc dùng chung](../../ARCHITECTURE.md).
