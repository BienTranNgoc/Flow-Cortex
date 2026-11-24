# Kiểm thử tài khoản và xác thực

- **AUTH-IT-01 · UC-AUTH-01 · Integration.** Đăng ký bằng email mới. Kỳ vọng `201`, có token, vai trò `MEMBER` và đăng nhập được bằng tài khoản vừa tạo. [registerNewEmailReturns201WithTokenAndTheAccountCanLogIn](../../../backend/boot/app/src/test/java/com/flowcortex/app/user/UserAuthEndpointsIT.java#L235)
- **AUTH-IT-02 · UC-AUTH-02 · Integration.** Đăng nhập bằng mật khẩu sai. Kỳ vọng `401` với `application/problem+json`. [loginWrongPasswordReturns401ProblemDetail](../../../backend/boot/app/src/test/java/com/flowcortex/app/user/UserAuthEndpointsIT.java#L126)
- **AUTH-CT-01 · UC-AUTH-03 · Component.** Khi tắt khôi phục mật khẩu, phần “Forgot password?” hiển thị dưới dạng văn bản thay vì liên kết. [Ca khôi phục bị tắt](../../../frontend/src/features/auth/LoginPage.test.tsx#L89)
- **AUTH-IT-03 · UC-AUTH-04 · Integration.** Gọi API lấy người dùng hiện tại khi chưa xác thực. Kỳ vọng `401` với `application/problem+json`. [unauthenticatedMeReturns401ProblemDetail](../../../backend/boot/app/src/test/java/com/flowcortex/app/user/UserAuthEndpointsIT.java#L202)

Xem [cách chạy và ghi kết quả](../../TESTING.md).
