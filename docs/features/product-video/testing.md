# Kiểm thử tạo video quảng cáo

Danh mục ca tiêu biểu cho [UC-PV-01](README.md#uc-pv-01--tạo-video-từ-thông-tin-sản-phẩm). Liên kết dưới đây trỏ tới mã test dùng để đối chiếu hành vi. Ghi kết quả đạt, thất bại hoặc bỏ qua từ lần chạy cụ thể theo [hướng dẫn kiểm thử](../../TESTING.md).

## Unit test

### PV-UT-01

Đọc thời lượng từ hợp đồng dùng chung. Kỳ vọng khớp hằng số của workflow. [Kiểm tra thời lượng](../../../backend/modules/workflow/src/test/java/com/flowcortex/workflow/api/ProductVideoDurationLimitTest.java#L18)

### PV-UT-02

Gọi dịch vụ khi tính năng bị tắt hoặc điều kiện khởi chạy từ chối. Kỳ vọng không tạo lượt chạy. Khi điều kiện khởi chạy từ chối, dịch vụ dừng trước khi tra cứu upload. [Tính năng bị tắt](../../../backend/modules/workflow/src/test/java/com/flowcortex/workflow/application/ProductVideoServiceTest.java#L61) · [Điều kiện khởi chạy từ chối](../../../backend/modules/workflow/src/test/java/com/flowcortex/workflow/application/ProductVideoServiceTest.java#L72)

### PV-UT-03

Giả lập thiếu credit ở clip đầu hoặc clip thứ hai. Kỳ vọng clip thứ hai không được điều phối nếu clip đầu bị chặn. Nếu clip thứ hai bị chặn, hệ thống yêu cầu hủy clip đầu. [Thiếu credit ở clip 1](../../../backend/modules/workflow/src/test/java/com/flowcortex/workflow/application/ProductVideoParallelDispatchTest.java#L432) · [Thiếu credit ở clip 2](../../../backend/modules/workflow/src/test/java/com/flowcortex/workflow/application/ProductVideoParallelDispatchTest.java#L444)

### PV-UT-04

**UC-PV-01 · Unit.** Khởi chạy với hai ảnh đã xác nhận. Dịch vụ sao chép ảnh trước khi lưu lượt chạy, gắn ảnh sau khi lưu và giữ thứ tự `image_1`, `image_2` trong đầu vào các bước cần ảnh. [snapshotsConfirmedMetadataAndOrderedAssets](../../../backend/modules/workflow/src/test/java/com/flowcortex/workflow/application/ProductVideoServiceTest.java#L96)

### PV-UT-05

**UC-PV-01 · Unit.** Cho bốn luồng cùng yêu cầu điều phối một lượt chạy. Hệ thống phát đúng hai tác vụ, mỗi bước tạo clip nhận đúng một tác vụ. [concurrentAdvancesDispatchEachClipExactlyOnce](../../../backend/modules/workflow/src/test/java/com/flowcortex/workflow/application/ProductVideoParallelDispatchTest.java#L374)

### PV-UT-06

**UC-PV-01 · Unit.** Cho clip 2 thất bại rồi trả lỗi hủy của clip 1. Hệ thống giữ lỗi gốc, ghi `sibling_failed` cho clip 1, hoàn credit cho clip đó và không gửi tác vụ kết xuất. [firstErrorWinsSiblingCancelsWithRefundAndCompletedSiblingIsKept](../../../backend/modules/workflow/src/test/java/com/flowcortex/workflow/application/ProductVideoParallelDispatchTest.java#L396)

## Component test

### PV-CT-01

Gửi biểu mẫu thiếu ảnh hoặc thiếu tên sản phẩm. Kỳ vọng hiển thị lỗi tương ứng và không gửi yêu cầu. [Thiếu ảnh](../../../frontend/src/features/workflow/ProductVideoStartForm.test.tsx#L155) · [Thiếu tên](../../../frontend/src/features/workflow/ProductVideoStartForm.test.tsx#L168)

### PV-CT-02

Chọn tệp AVI hoặc ảnh PNG 11 MiB rồi bấm khởi chạy. Kỳ vọng báo lỗi định dạng hoặc dung lượng và không gửi yêu cầu. [Định dạng](../../../frontend/src/features/workflow/ProductVideoStartForm.test.tsx#L201) · [Dung lượng](../../../frontend/src/features/workflow/ProductVideoStartForm.test.tsx#L212)

### PV-CT-03

Điền dữ liệu hợp lệ và mô phỏng tải ảnh thành công. Kỳ vọng tạo upload trước, gửi yêu cầu khởi chạy với mã upload và tỉ lệ 9:16 rồi mở trang lượt chạy. [Khởi chạy](../../../frontend/src/features/workflow/ProductVideoStartForm.test.tsx#L229)

## Integration test

### PV-IT-01

Cho clip 2 hoàn tất trước. Kỳ vọng chờ clip 1 trước khi gửi render, giữ thứ tự clip 1 rồi clip 2 và hoàn tất mọi bước khi có kết quả render. [Thứ tự clip](../../../backend/boot/app/src/test/java/com/flowcortex/app/workflow/ProductVideoParallelRoundTripIT.java#L73)

### PV-IT-02

Cho clip 2 thất bại. Kỳ vọng giữ lỗi gốc, ghi `sibling_failed` cho clip còn lại và không phát tác vụ render. [Lỗi clip](../../../backend/boot/app/src/test/java/com/flowcortex/app/workflow/ProductVideoParallelRoundTripIT.java#L120)

### PV-IT-03

Khởi chạy với dữ liệu mẫu, duyệt kịch bản và đưa kết quả hai clip vào bước kết xuất. [Luồng thành công](../../../backend/boot/app/src/test/java/com/flowcortex/app/workflow/ProductVideoRoundTripIT.java#L61)

### PV-IT-04

Sửa kịch bản đang chờ duyệt. Kỳ vọng thêm bản sửa số 2, giữ cả hai bản và trỏ tới bản mới. Duyệt bản cũ dù gửi phiên bản tác vụ mới nhất nhận HTTP `409`. [Sửa kịch bản](../../../backend/boot/app/src/test/java/com/flowcortex/app/workflow/ScriptRevisionApprovalIT.java#L83) · [Duyệt bản cũ](../../../backend/boot/app/src/test/java/com/flowcortex/app/workflow/ScriptRevisionApprovalIT.java#L111)

### PV-IT-05

Trước khi duyệt không có tác vụ tạo lời đọc. Sau khi duyệt bản sửa và storyboard hoàn tất, tác vụ tạo lời đọc phải dùng đúng nội dung đã duyệt. [Chờ duyệt](../../../backend/boot/app/src/test/java/com/flowcortex/app/workflow/ScriptRevisionApprovalIT.java#L151) · [Dùng bản đã duyệt](../../../backend/boot/app/src/test/java/com/flowcortex/app/workflow/ScriptRevisionApprovalIT.java#L162)

### PV-IT-06

Yêu cầu dừng khi hai clip đang chạy. Kỳ vọng cả hai được phép hoàn tất, render không được phát và lượt chạy chỉ chuyển sang hủy sau khi cả hai nhánh kết thúc. [Dừng lượt chạy](../../../backend/boot/app/src/test/java/com/flowcortex/app/workflow/ProductVideoParallelRoundTripIT.java#L156)

## Nghiệm thu thủ công

### PV-AC-01

**Chưa tự động hóa trong danh mục này.** Dùng tài khoản đủ credit và ảnh mẫu trên môi trường đã bật tính năng. Duyệt kịch bản, chờ hoàn tất và mở video. Kỳ vọng video phát được, có hình và lời đọc, đúng tỉ lệ 9:16 và dài 10 giây. Lưu mã lượt chạy, phiên bản triển khai và ảnh hoặc video minh chứng.
