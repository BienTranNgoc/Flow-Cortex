# Kế Hoạch & Checklist Triển Khai: Video Quảng Cáo Showroom & MC 480P (≤ 10 giây)

> **Trạng thái**: Hoàn tất nền tảng Slices 0–6 (Preset Library, Contracts/Migration, Worker Core + AI MC Adapters, Backend Preset Catalog & Templates API, Frontend Template/MC/Background Studio UI)  
> **Phiên bản**: v3.2  
> **Cập nhật gần nhất**: 2026-11-23  

---

## 1. Các Quy Ước Thiết Kế Đã Chốt

- [x] **Độ phân giải**: 480P dọc 9:16 (`480x848`, chuẩn H.264 chia hết cho 16).
- [x] **Hai mẫu chính**:
  - `showroom-v1`: 2 cảnh sản phẩm × 5s (toàn cảnh + cận cảnh chi tiết).
  - `mc-v1`: Hybrid **5s MC nói trực diện cầm sản phẩm + 5s cận cảnh sản phẩm**.
- [x] **Chi phí gốc H3 Lite 480P**: Ưu đãi **$0.0125/s** đến 31/12/2026; neo giá credit trong `rate_card` theo giá thường **$0.025/s** (từ 01/01/2027) để bảo toàn biên lợi nhuận.
- [x] **Cơ chế tính phí tạo lại**: Có tính phí credit cho mọi lượt Tạo lại ảnh keyframe, Làm lại từng cảnh, và Đồng bộ lại khẩu hình. Hoàn credit tự động khi lỗi hệ thống/vendor theo `RefundPolicy`.
- [x] **Xử lý ngôn ngữ**: Lời thoại & UI người dùng bằng **Tiếng Việt 100%**. Prompt góc máy và chuyển động sinh bằng LLM chuyển tự động sang **Tiếng Anh điện ảnh (40–75 từ/cảnh)**.
- [x] **Lộ trình làm lại từng cảnh**:
  - **Đợt 1 (Mức 1)**: Duyệt & tạo lại ảnh keyframe 9:16 có tính phí trước khi sinh video + retry bước lỗi.
  - **Đợt 2 (Mức 2A)**: Làm lại riêng 1 cảnh đã thành công (`redoSteps`) bằng cách clone run, tái sử dụng clip cảnh còn lại và WAV.
- [x] **Phạm vi Slice 0**: Làm nhanh duy nhất nhánh **PA-A1 (All-Replicate)**: H3 Lite 480P + upscale 720p + `kwaivgi/kling-lip-sync` 5s, chi phí test ~**$0.255 (~6.500đ)** (gồm 2 keyframe `seedream-4`).
- [x] **Tinh chỉnh bối cảnh & Thư viện Preset ($0 chi phí gốc thêm mỗi lượt chạy)**:
  - Hỗ trợ **1–3 ảnh sản phẩm** (`product_1..3`) cho cả 2 template: AI tự phân nhánh nhiều góc chụp vs nhiều màu sắc, truyền vào `seedream-4` `image_input` và `h3-lite` `reference_image_urls`.
  - **Thư viện Mẫu MC có sẵn** (2 MC Nữ + 1 MC Nam) và **Thư viện 4 Ảnh Bối cảnh 9:16 có sẵn** lưu trong `backend/boot/app/src/main/resources/product-video-presets/`; người dùng có thể chọn từ thư viện hoặc tự tải ảnh lên.
  - **Checkpoint 2 (Duyệt ảnh cảnh 9:16)** áp dụng cho cả 2 mẫu, cho phép đổi bối cảnh / gõ ghi chú tiếng Việt và tạo lại ảnh (~$0.03/lượt, có tính phí credit).

---

## 2. Checklist Tiến Độ Theo Slice

### Slice 0 — Spike Nhanh PA-A1 (All-Replicate)
- [x] Viết script độc lập `scripts/spike_pa_a1_mc.py`.
- [x] Chạy Edge TTS sinh 2 đoạn lời tiếng Việt: `mc_hook.wav` (4.008s) + `product_close.wav` (4.416s) → `speech.wav` (9.416s).
- [x] Ghép keyframe 9:16 qua `bytedance/seedream-4` (MC cầm sản phẩm + keyframe SP 9:16).
- [x] Gọi Replicate `minimax/h3-lite` 480P sinh Cảnh 1 (MC 5s, 22.8s) và Cảnh 2 (SP 5s, 36.0s).
- [x] Dùng `ffmpeg` cục bộ upscale Cảnh 1 lên `720x1280`.
- [x] Gọi Replicate `kwaivgi/kling-lip-sync` ghép `mc_hook.wav` vào Cảnh 1 720p (203.1s).
- [x] Dùng `ffmpeg` cục bộ ghép Cảnh 1 (lip-synced) + Cảnh 2 (SP) + `speech.wav` xuất `final_mc_ad_480p.mp4` (`480x848`, 9.416s, 620 KB).
- [x] **Slice Gate 0**: Master đã xem `final_mc_ad_480p.mp4` (`9.416s`, `480x848`, `620 KB`) từ ảnh đồng hồ thật `tmp/sp.jpg` và xác nhận đạt yêu cầu để tiếp tục.

---

### Slice 1 — Contracts & Database Template / Rate Card
- [x] Mở rộng `contracts/provider-job.schema.json` hỗ trợ chọn giọng, bối cảnh (`scenePreset`, `sceneNote`), `vertical_ad_clip_v2` (4–10s, 480p, tối đa 4 inputs), `generateKeyframeJob` (tối đa 5 inputs), `lipsyncVideoJob`.
- [x] Mở rộng `contracts/provider-video-result.schema.json` (2–10s, 480p/720p/768p, `kling_lipsync`).
- [x] Thêm schema `contracts/provider-keyframe-result.schema.json` + Java record `ProviderKeyframeData`.
- [x] Viết Flyway migration `V2026.11.23.001__product_video_templates_and_pricing.sql` (`template_id`, `template_version`, `voice_gender`, `mc_preset_id`, `background_preset_id`, `scene_note`, `rate_card` cho `generate_keyframe` & `lipsync_video`).
- [x] **Slice Gate 1**: `check-migration-version.sh`, Pydantic contract codegen và `:modules:media:test` xanh 100%.

---

### Slice 2 — Worker Core 480P & Render V2
- [x] Mở rộng `minimax_adapter.py` (Replicate `minimax/h3-lite`, 480P/768P, duration động 4–10s, hỗ trợ `first_frame_image` + `reference_image_urls`).
- [x] Mở rộng `edge_adapter.py` nhận chọn giọng (`vi-VN-NamMinhNeural` / `vi-VN-HoaiMyNeural`) và tách ghép 2 đoạn WAV căn mốc 5.0s cho MC.
- [x] Sửa `_normalize_video()` trong `capabilities.py` hỗ trợ `vertical_ad_clip_v2`, duration động và tối đa 4 inputs.
- [x] Bổ sung profile `vertical_ad_v2` trong `handlers/render_video.py` (`480x854`, hỗ trợ 1 hoặc 2 clip, khớp độ dài WAV ≤ 10s).
- [x] **Slice Gate 2**: `pytest` worker xanh 83/83 tests (`test_minimax_adapter`, `test_edge_adapter`, `test_render_video`, `test_provider_capabilities`).

---

### Slice 3 — Worker AI MC (System Prompts, Keyframe & LipSync)
- [x] Tách bộ System Prompt theo template (`provider/prompts/templates.py`: `showroom-v1`, `mc-v1`, `SCENE_PRESET_DIRECTIVES`).
- [x] Xây dựng `keyframe_adapter.py` ghép ảnh SP + MC + bối cảnh 9:16 bằng `bytedance/seedream-4`.
- [x] Xây dựng `lipsync_adapter.py` (`kwaivgi/kling-lip-sync`, tự upscale cục bộ 720p trước khi submit) kèm `probe_lipsync`.
- [x] **Slice Gate 3**: `pytest` (86/86 passed) & `ruff check` xanh 100%.

---

### Slice 4 — Backend Templates, Multi-Image & Speech-First
- [x] Xây dựng `ProductVideoPresetCatalog` (3 MC + 4 Background 9:16) và endpoint `GET /api/v1/product-videos/templates` + `GET /api/v1/product-videos/presets/{category}/{id}`.
- [x] Cập nhật `ProductVideoInput`, `ProductVideoDraft`, `ProductVideoRunRequest` và `ProductVideoService` nhận `templateId`, `voiceGender`, 1–3 ảnh SP + MC/Bối cảnh (preset hoặc upload) + `sceneNote`.
- [ ] Đảo `generate_speech` lên chạy trước `generate_storyboard` / `generate_video`.
- [ ] Xử lý `speech_too_long`: giữ bản nháp, cho phép rút gọn lời và thử lại ngay bước tạo giọng.
- [ ] **Slice Gate 4**: `ProductVideoRoundTripIT`, `ProductVideoDurationLimitTest`, `ScriptRevisionApprovalIT` xanh (hiện đã xanh 26/26 unit tests trong `:modules:workflow:test`).

---

### Slice 5 — Backend Checkpoint 2 (Duyệt Keyframe) & Credit
- [x] Đăng ký step executor `generate_keyframe` và `lipsync_video` trong `StepExecutorRegistry` và `MediaJobsImpl`.
- [ ] Thêm bước `generate_keyframe` + Checkpoint 2 (`keyframe_review`) vào pipeline chạy thật của `mc-v1` & `showroom-v1`.
- [ ] Thêm API tạo lại ảnh keyframe có tính credit (`per_image`), lưu lịch sử phiên bản ảnh.
- [ ] Gắn `VendorJobLedger` cho cả `generate_keyframe` và `lipsync_video`.
- [ ] **Slice Gate 5**: Run `mc-v1` chạy qua 2 checkpoint duyệt, trừ credit chính xác từng bước, chống gửi lặp.

---

### Slice 6 — Frontend Member App (Đợt 1 — Mức 1)
- [ ] Chạy `npm run gen:api` đồng bộ OpenAPI types (hiện frontend dùng `ExtendedProductVideoRunRequest` tương thích ngược).
- [x] Tạo `productVideoPresets.ts`, `AssetPickerField.tsx` (`Có sẵn` / `Tự tải lên` / `Tự mô tả`) và cập nhật `ProductVideoStartForm.tsx` (chọn mẫu `showroom-v1` / `mc-v1`, chọn MC, chọn bối cảnh, chọn giọng đọc, ước tính credit theo mẫu).
- [x] Cập nhật `ProductVideoPreviewPanel.tsx` (live preview 9:16 hiển thị nền bối cảnh, badge MC và tóm tắt cấu hình).
- [ ] Cập nhật `ScriptEditor.tsx` (2 ô nhập lời riêng cho MC) và giữ nháp khi `speech_too_long`.
- [ ] Tạo mới `KeyframeReviewPanel.tsx` (Checkpoint 2 duyệt ảnh keyframe 9:16, nút tạo lại ảnh có báo phí).
- [ ] Cập nhật `StepDetail.tsx` (nút retry bước lỗi) và `ProductVideoShowcaseCard.tsx` (`480x848`).
- [ ] Cập nhật từ điển `locales/{vi,en}/workflow.json`.
- [ ] **Slice Gate 6**: `ProductVideoStartForm.test.tsx` và `npm run build -w frontend` xanh; E2E UI trên Docker Compose (chờ nối Checkpoint 2).

---

### Slice 7 — Đợt 2: Mức 2A (Làm Lại Từng Cảnh & Đồng Bộ Lại)
- [ ] Mở rộng `RunResumption.java` & `RunsController.java` hỗ trợ `redoSteps` + `promptOverrides` (tái sử dụng clip cảnh đẹp & WAV).
- [ ] Hỗ trợ LLM dịch ghi chú chỉnh cảnh tiếng Việt → English visual directive.
- [ ] Bổ sung nút "Làm lại riêng từng cảnh" và "Đồng bộ lại khẩu hình" có modal báo phí credit.
- [ ] **Slice Gate 7**: `RunResumptionEndpointsIT` xanh; làm lại riêng Cảnh 1 hoặc Cảnh 2 thành công và chỉ trừ phí đúng cảnh đó.

---

### Slice 8 — Nghiệm Thu Cuối & Smoke Test
- [ ] Chạy thật 1 Showroom + 3 MC tiếng Việt qua UI.
- [ ] Kiểm thử lỗi thiếu mặt, timeout Replicate, gửi lặp, đối soát credit.
- [ ] Cập nhật `scripts/smoke-probe.sh`.
- [ ] **Slice Gate 8**: Toàn bộ CI workflow xanh 100%.
