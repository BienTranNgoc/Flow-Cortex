# Kiểm thử tạo video quảng cáo

- **PV-UT-01 · UC-PV-01 · Unit.** Đọc thời lượng từ hợp đồng dùng chung và so với hằng số của workflow. Kỳ vọng hai giá trị trùng khớp. [theVideoDurationMatchesTheSharedContract](../../../backend/modules/workflow/src/test/java/com/flowcortex/workflow/api/ProductVideoDurationLimitTest.java#L18)

- **PV-IT-01 · UC-PV-01 · Integration.** Sau khi gửi cả hai tác vụ, trả kết quả clip 2 trước. Kỳ vọng chưa gửi render cho đến khi clip 1 hoàn tất, thứ tự đầu vào render vẫn là clip 1 rồi clip 2, và mọi bước hoàn tất sau kết quả render. [dispatchesBothClipsBeforeEitherCompletesAndComposesInOrderWhenSixFinishesFirst](../../../backend/boot/app/src/test/java/com/flowcortex/app/workflow/ProductVideoParallelRoundTripIT.java#L73)
- **PV-IT-02 · UC-PV-01 · Integration.** Làm clip 2 thất bại. Kỳ vọng lỗi gốc được giữ, nhánh còn lại nhận `sibling_failed`, và không phát tác vụ render. [controlProtocolReturnsCancelSiblingFailedAndDiscardStaleOverRabbitMq](../../../backend/boot/app/src/test/java/com/flowcortex/app/workflow/ProductVideoParallelRoundTripIT.java#L120)

Xem [cách chạy và ghi kết quả](../../TESTING.md).
