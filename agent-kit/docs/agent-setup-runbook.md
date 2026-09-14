# Runbook cho Claude Code — cài GitNexus (fork) + tự gen types cho project

> **Đồng nghiệp:** mở Claude Code TẠI thư mục `~/.claude`, rồi nói:
> *"Đọc `docs/agent-setup-runbook.md` và thực hiện Phần 1 (và Phần 2 nếu tôi muốn type-safety)."*
> Claude sẽ chạy theo runbook này. Nó **sẽ dừng hỏi bạn** ở các điểm cần quyết định — trả lời rồi nó chạy tiếp.

Runbook này viết cho **AI agent (Claude Code) thực thi**, không phải để đọc suông.

---

## GUARDRAILS (agent PHẢI tuân thủ)

1. **Dừng và HỎI người dùng trước khi:** cài global package, sửa cấu hình MCP, clone/build repo từ mạng, chạy `npm install`. Nêu rõ lệnh sắp chạy.
2. **KHÔNG `git commit` / `git push` bất cứ gì.** Các file codegen sinh ra là **local-only**, không đưa vào git của product repo.
3. **KHÔNG đoán đường dẫn / URL / key.** Mọi giá trị máy-cụ-thể (thư mục clone, path product repo, API key, backend đã chạy chưa) → hỏi người dùng.
4. **Verify sau mỗi bước** bằng lệnh kiểm, báo kết quả thật (không giả định thành công).
5. Nếu một bước fail và không tự giải được → dừng, báo lỗi thật, không workaround âm thầm.

---

## PHẦN 0 — Hỏi người dùng (bắt buộc trước khi làm gì)

Agent hỏi và ghi lại:
- **A. Đã cài `bun` + Node 24 chưa?** (`bun --version`, `node --version` ≥ 22.18 hoặc ≥ 24.11). Chưa có bun → hướng dẫn cài `curl -fsSL https://bun.sh/install | bash`.
- **B. Có quyền truy cập repo fork `github.com/ITminhnh2509/GitNexus` không?** (private → xin Master add collaborator). Muốn làm Phần 2/3 thì cần quyền các product repo tương ứng.
- **C. Muốn làm tới đâu?** Phần 1 (GitNexus CLI+MCP) / Phần 2 (codegen types local) / Phần 3 (index + group cross-repo).
- **D. Thư mục nào để clone fork?** (mặc định đề xuất `~/Projects/gitnexus-fork`).

---

## PHẦN 1 — Cài fork GitNexus (CLI + MCP)

Bản gốc GitNexus không capture route NestJS `@Controller({ path })` (dạng object) → mù cross-repo API. Fork đã fix. Các bước:

```bash
# 1.1 Clone + checkout branch fix (thay <DIR> bằng câu trả lời D)
git clone https://github.com/ITminhnh2509/GitNexus.git <DIR>
cd <DIR> && git checkout fix/nest-object-controller-prefix

# 1.2 Build theo THỨ TỰ (shared trước, monorepo)
cd gitnexus-shared && npm install && npm run build
cd ../gitnexus     && npm install && npm run build

# 1.3 Cài global (bun symlink tới thư mục fork — ĐỪNG xóa/di chuyển <DIR> sau này)
bun remove -g gitnexus 2>/dev/null || true
bun install -g "file:$PWD"      # đang ở <DIR>/gitnexus
bun pm trust --all -g           # chạy native postinstall: tree-sitter, ladybugdb, onnxruntime
```

**Verify (agent chạy + báo kết quả):**
```bash
which gitnexus && readlink -f "$(which gitnexus)"   # phải trỏ vào <DIR>/gitnexus/dist/...
gitnexus --version                                   # 1.6.9
```

**1.4 Đăng ký MCP cho Claude Code** (hỏi người dùng trước khi chạy):
```bash
claude mcp add gitnexus -s user -- "$(which gitnexus)" mcp
claude mcp list | grep gitnexus     # kỳ vọng: ✔ Connected
```
> **Lưu ý bắt buộc:** sau khi đổi/ cài binary, **restart phiên Claude Code** — MCP server cũ giữ code cũ trong RAM; tool `mcp__gitnexus__*` chỉ chạy đúng bản mới ở phiên mới.

**Nếu máy đã từng cài bản gốc (homebrew/npm):** gỡ để tránh dùng nhầm — `npm rm -g gitnexus` (hoặc `/opt/homebrew/bin/npm rm -g gitnexus`), và xóa mọi override MCP cũ trong `~/.claude.json` trỏ tới bản gốc.

---

## PHẦN 2 — Tự gen types LOCAL cho project (type-safety khi coding)

**Không commit gì vào product repo.** Mục tiêu: sinh `schema.d.ts` local để IDE bắt lỗi khi FE đọc sai field BE.

Mẫu đã dựng sẵn trong claude-config:
- `~/.claude/scripts/templates/generate-openapi.template.ts` (backend NestJS → `openapi.json`)
- `~/.claude/scripts/templates/generate-api-types.template.mjs` (openapi-typescript → `schema.d.ts`, có fallback + xử lý dangling `$ref`)

**Agent làm:**

1. **Hỏi người dùng:** đường dẫn backend repo, (các) frontend repo, và **backend + Postgres đã chạy chưa** (script boot AppModule nên cần DB up — thường là `docker compose up` stack).

2. **Backend — tạo script local từ template:**
   - Copy `generate-openapi.template.ts` → `<backend>/scripts/generate-openapi.ts`.
   - **Chỉnh theo backend cụ thể:** đọc `<backend>/src/main.ts` → mirror y hệt block `DocumentBuilder`, `setGlobalPrefix`, `enableVersioning`. Sửa các dòng `process.env.*_ENABLED = 'false'` cho khớp env-gate scheduler/worker của backend đó (grep `process.env.*ENABLED`, `@Cron`, `onModuleInit` để tìm). Output → `<backend>/openapi.json`.
   - Thêm npm script `"generate:openapi": "ts-node --transpileOnly -r tsconfig-paths/register scripts/generate-openapi.ts"` (dùng `--transpileOnly` nếu bare ts-node fail vì ambient type shim).
   - Chạy: `cd <backend> && npm run generate:openapi` → verify in ra `N paths / M schemas`. So sánh với `/docs-json` của backend nếu đang chạy.

3. **Frontend — tạo script local từ template:**
   - Copy `generate-api-types.template.mjs` → `<frontend>/scripts/generate-api-types.mjs`.
   - **Chỉnh 2 hằng:** `SOURCE` = đường dẫn tương đối tới `<backend>/openapi.json`; `TARGET` = nơi đặt types (vd `src/lib/generated/schema.d.ts` hoặc `src/api/generated/schema.d.ts` — theo convention repo).
   - Cài dev dep: `npm i -D openapi-typescript@^7` (nếu TypeScript của repo là v6 → thêm `--legacy-peer-deps`).
   - Chạy: `node scripts/generate-api-types.mjs` → verify sinh ra `schema.d.ts`.

4. **KHÔNG `git add`** các file vừa tạo (`scripts/generate-*.{ts,mjs}`, `openapi.json`, `src/**/generated/`). Nhắc người dùng chúng là công cụ local.

5. **Dùng khi coding:** `import type { paths, components } from '<TARGET>'` để gõ type request/response. Khi backend đổi API → chạy lại bước 2 → 3.

> Cảnh báo thường gặp: script in `⚠ dangling schema $ref` — không phải lỗi, là 1 DTO backend chưa `@ApiExtraModels`; types vẫn sinh đủ.

---

## PHẦN 3 (tùy chọn) — Index + group cross-repo cho impact-analysis

Chỉ làm nếu người dùng muốn dùng `impact`/`route_map`/`api_impact` cross-repo. GitNexus là **local per-developer** (mỗi người tự index).

```bash
# 3.1 Index từng repo — PLAIN, no --pdg/--embeddings (Master 2026-09-14; CR=semantic, GitNexus=structural)
cd <repo> && gitnexus analyze
gitnexus analyze --repair-fts     # nếu query báo "FTS indexes missing"

# 3.2 Tạo group (vd "myapp" gồm backend + các frontend)
gitnexus group create myapp
gitnexus group add myapp <backend-registry-name>  <backend-registry-name>
gitnexus group add myapp <frontend-registry-name> <frontend-registry-name>

# 3.3 Sinh link FE↔BE bằng generator (đã có trong claude-config)
#     Cần openapi.json (Phần 2 bước 2). Đọc --help trong script để biết cờ.
node ~/.claude/scripts/generate-gitnexus-links.mjs --group myapp \
  --openapi <backend>/openapi.json --be-repo <backend-registry-name> \
  --fe "<fe-registry>:<fe>/src" --write
gitnexus group sync myapp
```

**Thứ tự quan trọng:** sau khi reindex đổi shape route → `gitnexus group sync <g>` **trước** `generate-...--write` (refresh `contracts.json`) rồi mới generator + sync.

---

## PHẦN 4 — Cài 6 tool phân tích behavioral / hygiene (global)

Cần cho các dimension mà knowledge graph **không** tính được: độ phức tạp, clone, dead code, luật kiến trúc. `bin/git-behavioral` (đã có sẵn trong claude-config, không cần cài) gọi `lizard`; 5 tool còn lại do agent gọi trực tiếp khi brief yêu cầu plan-support / refactor-targeting / risk.

```bash
# 4.1 brew — scc + pipx (pipx là runtime cho 2 tool Python bên dưới)
brew install scc pipx

# 4.2 pipx — tool Python, mỗi tool một venv riêng
pipx install lizard
pipx install vulture
pipx ensurepath          # thêm ~/.local/bin vào PATH; mở shell mới sau lệnh này

# 4.3 npm global — tool JS/TS
npm install -g jscpd knip dependency-cruiser
```

| Tool | Lệnh cài | Dùng để làm gì |
|---|---|---|
| `scc` | `brew install scc` | Đếm LOC + ước lượng complexity toàn repo trong ~1 giây — lấy quy mô trước khi đào sâu. |
| `lizard` | `pipx install lizard` | Cyclomatic complexity **theo từng function**; `bin/git-behavioral` join số này với churn để ra bảng hotspot. |
| `vulture` | `pipx install vulture` | Dead code cho Python (code không ai gọi). |
| `jscpd` | `npm install -g jscpd` | Phát hiện đoạn code copy-paste (clone) đa ngôn ngữ. |
| `knip` | `npm install -g knip` | Dead code / export & dependency thừa cho TS/JS, hiểu framework (Next, Vite...). |
| `dependency-cruiser` | `npm install -g dependency-cruiser` | Kiểm luật kiến trúc đã khai báo (`.dependency-cruiser.js`) — binary tên là `depcruise`. |

**Verify (agent chạy + báo kết quả thật):**
```bash
for t in scc lizard vulture jscpd knip depcruise; do "$t" --version >/dev/null 2>&1; echo "$t=$?"; done
# kỳ vọng: cả 6 dòng đều =0
```

> `dependency-cruiser` cài bằng tên package đó nhưng **binary là `depcruise`** — kiểm bằng `depcruise`, không phải `dependency-cruiser`.
> `lizard`/`vulture` nằm ở `~/.local/bin`. Nếu `--version` báo not found dù pipx báo cài xong → chưa chạy `pipx ensurepath` hoặc chưa mở shell mới.

---

## Xong — verify tổng

Agent chạy và báo:
- `gitnexus --version` = fork 1.6.9, binary trỏ thư mục fork.
- `claude mcp list` → gitnexus ✔ Connected (nhắc restart phiên để tool chạy).
- (nếu làm Phần 2) `schema.d.ts` tồn tại ở TARGET; `import type { paths }` gợi ý được trong IDE.
- (nếu làm Phần 3) `gitnexus group <name>` trả cross-links > 0.
