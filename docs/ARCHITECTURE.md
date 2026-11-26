# Kiến trúc Flow-Cortex

## Ngữ cảnh

```mermaid
flowchart LR
    Member["Thành viên"] --> System["Flow-Cortex"]
    Admin["Quản trị viên"] --> System
    System --> Provider["Nhà cung cấp AI và media"]
    System --> External["Dịch vụ email và thanh toán"]
```

## Thành phần và triển khai

```mermaid
flowchart LR
    Web["Frontend React"] -->|HTTP| API["API Spring Boot"]
    Admin["Admin React"] -->|HTTP| API
    API --> Data[("PostgreSQL")]
    API --> Queue["RabbitMQ"]
    Queue --> Worker["Worker Python"]
    Worker --> Queue
    API --> Storage[("Kho đối tượng tương thích S3")]
    Worker --> Storage
    Worker --> Provider["Nhà cung cấp bên ngoài"]
```

Backend tổ chức theo mô-đun nghiệp vụ. Các mô-đun trao đổi qua cổng công khai `api`. Frontend và admin dùng hợp đồng [OpenAPI](openapi.yaml). Các thông điệp giữa backend và worker có [schema trong contracts](../contracts/README.md). Cách chạy dịch vụ và cấu hình phát triển nằm trong [LOCAL_SETUP.md](../LOCAL_SETUP.md), còn cấu hình ghép dịch vụ nằm trong [Docker Compose](../docker-compose.yml). Các quyết định về Spring Modulith, RabbitMQ và SSE được ghi trong [ADR](ADR.md).

## Cơ chế thực thi workflow

```mermaid
sequenceDiagram
    actor U as Người dùng
    participant A as Backend
    participant B as RabbitMQ
    participant W as Worker
    U->>A: Khởi chạy workflow
    A-->>U: Định danh lượt chạy
    A->>B: Gửi công việc của bước cần worker
    B->>W: Chuyển công việc
    W->>B: Báo tiến độ hoặc kết quả
    B->>A: Chuyển thông điệp
    A-->>U: Cập nhật trạng thái qua SSE
```

Backend quản lý lượt chạy và từng bước. Các bước có thể xử lý tại backend, gửi công việc cho worker hoặc chờ đầu vào tùy định nghĩa. Các quan hệ sau là mô hình khái niệm. Tra khóa vật lý và dữ liệu lưu trữ trong [ERD](ERD.md).

```mermaid
classDiagram
    Workflow "1" --> "*" WorkflowStep : định nghĩa
    Workflow "1" --> "*" WorkflowRun : thực thi
    WorkflowRun "1" --> "*" RunStep : theo dõi
```

Xem [Tạo video quảng cáo](features/product-video/README.md) để theo dõi một ví dụ có các bước tạo clip song song.
