# Team tooling setup — gam + xood

Hướng dẫn onboard một máy mới để có đủ tooling khi làm việc trên **gam** (gam-backend + gam-frontend) và **xood** (xood-backend + xood-frontend + xood-admin + …).

Có hai nhóm độc lập. **Nhóm A ai cũng cần và rất nhẹ; Nhóm B chỉ cho ai muốn phân tích impact cross-repo.**

| Nhóm | Cần cho | Nặng? |
|---|---|---|
| **A. Codegen (OpenAPI → TS types)** | Mọi dev build/dev FE — lưới type-safety FE↔BE | Nhẹ (chỉ `npm install`) |
| **B. GitNexus impact-analysis** | Ai muốn hỏi "sửa API này vỡ ở đâu, ai gọi" cross-repo | Nặng (build fork + index từng repo) |

Codegen **không phụ thuộc** GitNexus. Có thể chỉ làm Nhóm A và bỏ qua Nhóm B.

## Prereqs chung

- **Node 24.x** (`.nvmrc` = 24 trong các product repo; GitNexus fork cần `^22.18 || >=24.11`). `nvm install 24`.
- `git`, `npm`. Cho Nhóm B thêm **`bun`** (cài fork global) + Docker (chạy backend + Postgres khi cần regenerate spec).
- Quyền truy cập các repo GitHub: `gam-backend`, `gam-frontend`, `xood-backend`, `xood-frontend`, `xood-admin` và fork `ITminhnh2509/GitNexus` (private → xin add collaborator).

---

## Nhóm A — Codegen (type-safety khi coding, tự dựng LOCAL)

Ý tưởng: backend sinh `openapi.json` → frontend sinh `src/lib/generated/schema.d.ts` từ đó → khi code bạn `import` type từ file này để IDE tự bắt lỗi khi đọc sai field BE.

**Quy ước quan trọng:** với **xood**, codegen KHÔNG nằm trong git repo — đây là **tiện ích local của mỗi người**, tự sinh khi cần, **không commit** vào xood repo. (Product repo giữ nguyên si; không có cổng type-check trong CI — type-safety chỉ hỗ trợ lúc bạn đang gõ code.)

**Mẫu sống:** `gam-backend` + `gam-frontend` ĐÃ commit codegen đầy đủ — đọc 2 file này làm khuôn:
- `gam-backend/scripts/generate-openapi.ts` (boot Nest, dump `openapi.json`, không mở port)
- `gam-frontend/scripts/generate-api-types.mjs` (openapi-typescript → `schema.d.ts`, có fallback)

### Cách nhanh nhất: nhờ Claude Code

Mở Claude Code trong thư mục xood, yêu cầu đại ý: *"Dựng OpenAPI type generation LOCAL cho xood giống gam-project (đọc scripts đã commit của gam làm mẫu), nhắm `xood-backend` + `xood-frontend` + `xood-admin`. KHÔNG commit — chỉ để local coding."* Claude sẽ đọc mẫu gam, tạo script local + chạy.

### Hoặc làm tay

```bash
# 1) Backend: copy mẫu gam, chỉnh output → xood-backend/openapi.json. Cần Postgres up.
#    (script boot AppModule; xood cần các env-gate SCHEDULER_ENABLED=false … — xem mẫu đã dựng)
cd xood-backend && npm run generate:openapi      # nếu đã có script local; nếu chưa, tạo từ mẫu gam

# 2) Frontend/admin: cài openapi-typescript local (KHÔNG commit vào package.json nếu muốn repo sạch)
cd ../xood-frontend && npm i -D openapi-typescript@^7   # admin dùng TS 6 → thêm --legacy-peer-deps
node scripts/generate-api-types.mjs              # → src/lib/generated/schema.d.ts (local, KHÔNG commit)
```

- `openapi.json` là file trung gian — sinh xong dùng ngay, không cần giữ.
- **Đừng `git add`** các file này trong xood repo — chúng là công cụ local.
- **Lưu ý đã biết:** script in cảnh báo `⚠ dangling schema $ref (CreateBatchDto)` — không phải lỗi codegen, là 1 DTO backend chưa `@ApiExtraModels`; types vẫn sinh đủ. Backend đăng ký DTO đó thì cảnh báo tự hết.

> gam thì ngược lại — codegen là infra committed (clone + `npm install` là có types sẵn, `vue-tsc` gác trong CI). xood cố tình để local-only theo lựa chọn của team.

---

## Nhóm B — GitNexus impact-analysis (tùy chọn)

GitNexus là code-graph **local, per-developer** (không có server chung — mỗi người index trên máy mình). Dùng bản **fork** vì bản gốc không capture route NestJS `@Controller({ path })` (dạng object) → mù cross-repo API.

### B1. Cài fork global

```bash
git clone https://github.com/ITminhnh2509/GitNexus.git ~/Projects/gitnexus-fork
cd ~/Projects/gitnexus-fork
git checkout fix/nest-object-controller-prefix

# build theo thứ tự (shared trước)
cd gitnexus-shared && npm install && npm run build
cd ../gitnexus     && npm install && npm run build

# cài global (bun symlink — rebuild fork là ăn ngay)
bun remove -g gitnexus 2>/dev/null || true
bun install -g "file:$PWD"          # $PWD = .../gitnexus-fork/gitnexus
bun pm trust --all -g               # chạy native postinstall (tree-sitter, ladybugdb, onnxruntime)

which gitnexus && gitnexus --version   # xác nhận trỏ đúng bản fork
```

### B2. Index từng repo

**Index PLAIN — no `--pdg`, no `--embeddings`** (Master 2026-09-14; CR lo semantic, GitNexus lo cấu trúc — `pdg_query`/`explain` sẽ báo "no layer", đúng chủ đích):

```bash
cd <repo> && gitnexus analyze
gitnexus analyze --repair-fts        # nếu query báo "FTS indexes missing"
```

Làm cho từng repo muốn phân tích: gam-backend, gam-frontend, xood-backend, xood-frontend, xood-admin…

### B3. Tạo group cross-repo

```bash
gitnexus group create gam-project
gitnexus group add gam-project gam-backend  gam-backend
gitnexus group add gam-project gam-frontend gam-frontend
# tương tự cho group "xood" với 6 repo
```

### B4. Sinh link FE↔BE bằng generator (script kèm trong repo này)

Script nằm sẵn tại `~/.claude/scripts/generate-gitnexus-links.mjs`. Nó đọc `openapi.json` + quét call-site FE → ghi `links:` vào `group.yaml`, rồi `group sync` tạo cross-link.

```bash
# gam (mặc định script trỏ gam)
node ~/.claude/scripts/generate-gitnexus-links.mjs --write
gitnexus group sync gam-project

# xood (đa FE) — cần openapi.json của xood-backend đã sinh (Nhóm A bước 1)
node ~/.claude/scripts/generate-gitnexus-links.mjs \
  --group xood --openapi <path>/xood-backend/openapi.json --be-repo xood-backend \
  --fe "xood-frontend:<path>/xood-frontend/src,xood-admin:<path>/xood-admin/src" --write
gitnexus group sync xood
```

**Thứ tự quan trọng:** sau khi reindex đổi shape route, chạy `gitnexus group sync <g>` **trước** `generate-...--write` (refresh `contracts.json`) rồi mới generator + sync — nếu không generator neo theo `contracts.json` cũ sẽ tính sai.

### B5. MCP trong Claude Code (nếu muốn tool `mcp__gitnexus__*`)

Copy khối `gitnexus` từ `~/.claude/mcp-servers.example.json` vào cấu hình MCP của bạn. CLI `gitnexus` dùng được ngay không cần bước này; MCP chỉ để gọi tool trong Claude Code. **Sau khi đổi binary fork phải restart session Claude Code** (MCP server cũ giữ code cũ trong RAM).

---

## Ghi chú portability (khi clone claude-config sang máy khác)

- **API keys:** `mcp-servers.example.json` là mẫu — tự set key của bạn qua env (`FIRECRAWL_API_KEYS`, `TAVILY_API_KEY`, `CONTEXT7_API_KEY`). Repo không chứa key thật.
- **Hooks:** nhiều hook giả định môi trường Master (gitnexus binary, cr-budget/cr-cache dir). Chưa dựng Nhóm B thì một số hook chỉ in cảnh báo — không phá gì; tắt trong `settings.json` nếu phiền.
- **3 script eval** (`skills/osf-workspace/*.py`, `benchmark.json`) hardcode path `/Users/minhnh2509/…` — chỉ ảnh hưởng skill eval nội bộ, bỏ qua được.
- **Dữ liệu cá nhân KHÔNG track:** `projects/` (transcript + memory), caches, sessions — clone sẽ không có, an toàn.
