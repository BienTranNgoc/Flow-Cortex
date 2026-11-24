# Kiểm thử Flow-Cortex

## Chạy test

- **Frontend và admin**, từ gốc repo với Node.js 22. Chạy `npm ci --ignore-scripts`, `npm run gen:api -w @flow-cortex/api-client`, sau đó `npm run test --workspaces --if-present`. [CI frontend](../.github/workflows/frontend-ci.yml)
- **Backend**, từ `backend/` với JDK 25 và Docker cho các ca dùng Testcontainers. Chạy `./gradlew --no-daemon build` hoặc `.\gradlew.bat --no-daemon build` trên Windows. Kiểm tra diff của snapshot OpenAPI nếu bộ test sinh lại tệp này. [CI backend](../.github/workflows/backend-ci.yml)
- **Worker**, từ `worker/` với Python 3.12 và uv. Chạy `uv sync --locked --no-build --extra overlay`, sau đó `uv run --frozen --no-build --extra overlay pytest -rs`. Xem CI để biết các suite chạy trong image chuyên dụng và fixture tương ứng. [CI worker](../.github/workflows/worker-ci.yml)
- **Hợp đồng và smoke test** chạy qua [CI contracts](../.github/workflows/contracts-ci.yml) và [Compose smoke](../.github/workflows/compose-smoke.yml). Xem [thiết lập cục bộ](../LOCAL_SETUP.md) khi cần dịch vụ thật.

## Quản lý ca kiểm thử

Unit test kiểm tra quy tắc riêng lẻ. Component test kiểm tra biểu mẫu và phản hồi giao diện. Integration test kiểm tra các thành phần phối hợp như API, DB và RabbitMQ. End-to-end kiểm tra hành trình hoàn chỉnh trong môi trường phù hợp. Test thực thi nằm cùng thành phần triển khai. Dùng fixture trong suite tương ứng và dữ liệu giả lập cho thông tin cá nhân.

Ghi ca tiêu biểu trong `features/<slug>/testing.md` hoặc mục **Kiểm thử** của trang tính năng. Mỗi ca có mã, yêu cầu liên quan, mức test, điều kiện và thao tác, kết quả mong đợi và liên kết đến đúng hàm test. Ghi **Chưa tự động hóa** khi chưa xác định được test chứng minh. Nếu QA cần kịch bản thủ công độc lập, đặt trong `acceptance.md` và dẫn từ danh mục theo mã ca.

## Bằng chứng cho báo cáo

Với mỗi đợt chạy, ghi commit, ngày, suite/lệnh, môi trường và phiên bản, số ca đạt/thất bại/bỏ qua, cùng liên kết CI hoặc log còn truy cập được. Dùng kết quả quan sát ở lần chạy này để báo cáo. Assertion trong mã cho biết điều test kiểm tra, còn log cho biết kết quả thực tế.

Chương kiểm thử lấy phương pháp từ trang này, ca tiêu biểu từ trang tính năng và kết quả từ đợt chạy đã chọn. Chỉ số mô hình Check CV nằm trong [đánh giá NLP](features/check-cv/ml/evaluation.md).
