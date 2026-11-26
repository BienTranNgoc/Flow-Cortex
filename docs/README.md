# Tài liệu Flow-Cortex

Xem [Phân tích hệ thống](SYSTEM-ANALYSIS.md) để hiểu tác nhân và phạm vi, [Kiến trúc](ARCHITECTURE.md) để theo dõi cách các thành phần phối hợp, [ERD](ERD.md) để tra cứu dữ liệu và [ADR](ADR.md) để đọc lý do của các quyết định thiết kế. Hợp đồng HTTP nằm trong [OpenAPI](openapi.yaml). Xem [Kiểm thử](TESTING.md) để chạy test và lấy bằng chứng cho báo cáo.

## Đặc tả trong đợt đầu

- [Tài khoản và xác thực](features/accounts/README.md) — có mã triển khai và test. Đặc tả gồm luồng người dùng và admin.
- [Tạo video quảng cáo](features/product-video/README.md) — có định nghĩa workflow và test tích hợp.
- [Dịch video tiếng Trung](features/video-translation/README.md) — có các bước dịch và lồng tiếng trong mã nguồn. Phạm vi ghép bước và cấu hình chạy được ghi trong đặc tả.
- [Check CV](features/check-cv/README.md) — dự kiến. Tài liệu gồm đặc tả, thiết kế, kiểm thử và thực nghiệm NLP.

## Danh mục mở rộng

Các nhóm dưới đây có dấu vết triển khai trong [frontend](../frontend/src/features), [admin](../admin/src/features) hoặc [backend](../backend/modules). Xác nhận phạm vi phát hành từ môi trường đích trước khi dùng danh mục này làm danh sách chức năng đang mở cho người dùng. Tạo trang theo [quy ước đóng góp](../AGENTS.md) khi có nội dung cần duy trì.

- `access-management` — người dùng, vai trò và quyền quản trị.
- `workflow-management` — tạo, sửa, chia sẻ workflow và lịch sử chạy.
- `workflow-operations` — điều tra lượt chạy, media jobs và cấu hình nhà cung cấp.
- `drive` — quản lý tệp.
- `billing` — credit, thanh toán và cấu hình thanh toán.
- `communications` — chat và thông báo.
- `onboarding` — hướng dẫn người dùng mới.
- `support` — yêu cầu hỗ trợ và xử lý phía quản trị.
- `content-management` — quản trị nội dung.
