# API & Interface Design Checklist

Contract-first interface reference for the osf kit. Distilled from the
`api-and-interface-design` skill in addyosmani/agent-skills (MIT).

> **When osf kit pulls this in (load on-demand, passive — not auto-triggered):**
> - `/feat` *Stress-test* cites it when designing a new endpoint, public interface,
>   module boundary, or component prop contract.
> - `/review` *Impact Gaps* + *Anti-Patterns* dimensions use it for breaking-change
>   detection.
>
> **Structural safety (CLAUDE.md §5):** changing a published interface/type → use
> GitNexus `impact` + trace ALL implementors, call sites, casts (depth-2). This is
> Hyrum's Law made checkable: the graph shows who already depends on the shape.

## Core Principles

- **Hyrum's Law:** with enough users, *every* observable behavior — undocumented
  quirks, error text, timing, ordering — becomes a de facto contract. Be intentional
  about what you expose; don't leak implementation details; plan deprecation at
  design time. Contract tests are not enough.
- **One-Version Rule:** don't force consumers to juggle multiple versions of the same
  dependency/API. Design so only one version exists at a time — extend, don't fork.

## Checklist

### Contract First
- [ ] Interface (types/schema) defined **before** implementation — the contract is the spec
- [ ] Every endpoint has typed input and output schemas
- [ ] Input and output types separated (output includes server-generated fields)

### Error Semantics (pick one, use everywhere)
- [ ] Single consistent error shape (`{ error: { code, message, details? } }`)
- [ ] Status codes mapped consistently: 400 invalid · 401 unauthenticated · 403 unauthorized · 404 not found · 409 conflict · 422 validation · 500 server (no internals leaked)
- [ ] Don't mix patterns (some throw, some return null, some return `{ error }`)

### Validate at Boundaries
- [ ] Validate at API routes, form handlers, external-response parsing, env loading
- [ ] **Third-party API responses are untrusted data** — validate shape/content before use
- [ ] Internal code trusts its type contracts (no redundant re-validation between internal fns)

### Backward Compatibility
- [ ] New fields are additive and optional
- [ ] No type changes or removals on existing fields
- [ ] List endpoints support pagination from the start (`page`, `pageSize`, `totalItems`, `totalPages`)

### Predictable Naming
| Pattern | Convention | Example |
|---------|-----------|---------|
| REST endpoints | Plural nouns, no verbs | `GET /api/tasks`, `POST /api/tasks` |
| Query params / fields | camelCase | `?sortBy=createdAt`, `{ createdAt }` |
| Boolean fields | is/has/can prefix | `isComplete`, `hasAttachments` |
| Enum values | UPPER_SNAKE | `"IN_PROGRESS"` |

## TypeScript Patterns

```typescript
// Branded IDs — prevents passing a UserId where a TaskId is expected
type TaskId = string & { readonly __brand: 'TaskId' };

// Discriminated unions for variants — consumer gets type narrowing
type TaskStatus =
  | { type: 'pending' }
  | { type: 'in_progress'; assignee: string; startedAt: Date }
  | { type: 'completed'; completedAt: Date };
```

## Red Flags

- Endpoints returning different shapes depending on conditions
- Inconsistent error formats across endpoints
- Validation scattered through internal code instead of at boundaries
- Breaking changes to existing fields (type change, removal)
- List endpoints without pagination
- Verbs in REST URLs (`/api/createTask`)
- Third-party responses used without validation
