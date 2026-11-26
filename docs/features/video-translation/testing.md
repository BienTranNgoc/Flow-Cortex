# Kiểm thử dịch video tiếng Trung

- **VT-UT-01 · UC-VT-01 · Unit.** Nhà cung cấp trả sai số dòng trong cả hai lần. Kỳ vọng bước thất bại với `translation_invalid`. [aWrongLineCountIsAskedForOnceMoreThenFailsTranslationInvalid](../../../backend/modules/workflow/src/test/java/com/flowcortex/workflow/application/TranslateStepExecutorTest.java#L122)
- **VT-UT-02 · UC-VT-01 · Unit.** Nhà cung cấp từ chối do quota. Kỳ vọng lỗi bước giữ mã `provider_quota_exceeded`. [aProviderRefusalFailsTheStepWithItsCode](../../../backend/modules/workflow/src/test/java/com/flowcortex/workflow/application/TranslateStepExecutorTest.java#L156)
- **VT-AC-01 · UC-VT-01 · Nghiệm thu.** Chạy từ đầu vào video tiếng Trung theo cấu hình đích. Kỳ vọng đầu ra tiếng Việt theo định nghĩa đã triển khai. Còn thiếu bằng chứng xuyên luồng trong danh mục này. Xác nhận đầu vào, đầu ra và môi trường trước khi ghi kết quả.

Xem [cách chạy và ghi kết quả](../../TESTING.md).
