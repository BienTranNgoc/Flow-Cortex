---
paths:
  - "**/*.tsx"
  - "**/*.vue"
  - "**/*.svelte"
  - "**/*.css"
  - "**/tailwind.config.*"
  - "**/vite.config.*"
  - "**/next.config.*"
  - "**/nuxt.config.*"
  - "**/DESIGN.md"
  - "**/FRONTEND.md"
---

# Frontend projects — design docs convention

This rule loads only inside a frontend surface, which is exactly the scope it applies to:
react / vue / svelte / solid / angular, or a Vite / Next / Nuxt / Tailwind project. Backend,
CLI, and library work never sees it.

Read `agent-kit/docs/frontend-design-docs.md` for the full convention — the `DESIGN.md` /
`FRONTEND.md` layout, which file is the token source-of-truth, the Polaris reference, and the
a11y / perf checklists. The detail lives there so it loads when a frontend file is in play
rather than every session.

**Chốt hình dạng dữ liệu trước khi chốt layout.** Mỗi vùng trên màn hình cần đúng những trường
nào, lấy trong bao nhiêu request, endpoint đó đã tồn tại chưa, và query đằng sau nó có index +
phân trang chưa. Nếu layout đòi một hình dạng API chưa có — đề xuất sửa API là một phần của
thiết kế, không phải việc để lại cho lúc implement. Review kiểm lại điều này qua dimension
**API Consumption** (`skills/review/references/review-dimensions.md` §10).
