# Kiểm thử Check CV

Các ca sau thuộc **UC-CV-01** và đang ở trạng thái **Chưa tự động hóa**. Khi triển khai, gắn liên kết tới assertion tương ứng và dùng mẫu CV tổng hợp trong fixture.

- **CV-IT-01 · Integration.** Nộp PDF có lớp văn bản và JD hợp lệ. Kỳ vọng trích xuất đủ dữ liệu và chuyển sang đánh giá.
- **CV-IT-02 · Integration.** Nộp PDF scan với nội dung hợp lệ. Kỳ vọng đi qua nhánh fallback theo cấu hình và trả dữ liệu trích xuất hoặc lỗi có thể hiển thị nếu xử lý thất bại.
- **CV-IT-03 · Integration.** Đưa dữ liệu cá nhân giả lập vào CV. Quan sát payload tại mỗi lời gọi ngoài và xác nhận các trường cần bảo vệ đã được che theo ranh giới được chốt trong [thiết kế](design.md).
- **CV-AC-01 · Nghiệm thu.** Tạo đề xuất chỉnh sửa. Kỳ vọng lần chạy dừng để người dùng xem và quyết định trước khi tiếp tục qua bước duyệt.
- **CV-AC-02 · Nghiệm thu.** Chạy hai lần với gợi ý việc làm bật và tắt. Kỳ vọng nhánh bật truy hồi và hiển thị kết quả phù hợp, nhánh tắt bỏ qua truy hồi.

Kết quả chất lượng NLP được quản lý trong [đánh giá mô hình](ml/evaluation.md).
