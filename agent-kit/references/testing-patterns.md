# Testing Patterns Reference

Cross-stack testing reference for the osf kit. Adapted from addyosmani/agent-skills (MIT).

> **When osf kit pulls this in (load on-demand):** `/test` cites it for structure,
> naming, mocking, and anti-patterns; `osf-apply` reads the Prove-It pattern when
> fixing a bug; `/review` uses the anti-patterns table.

## Core Disciplines

- **Prove-It (bug fixes):** write a failing test that *reproduces the bug first*,
  confirm it fails, then fix until it passes, then run the full suite. A fix with
  no failing test never proved anything.
- **DAMP over DRY in tests:** production code is DRY; tests are DAMP (Descriptive And
  Meaningful Phrases). Each test reads as a self-contained spec — duplication that
  clarifies intent is acceptable.
- **State-based, not interaction-based:** assert on the outcome (returned/stored
  state), not on which methods were called. Interaction assertions break on refactor.
- **Mock preference order (most → least trust):** real implementation → fake
  (in-memory) → stub (canned data) → mock (interaction). Mock only at boundaries.
- **Test pyramid (~80/15/5):** unit (no I/O, ms) / integration (test DB, seconds) /
  E2E (real browser, minutes).

## Test Structure (Arrange-Act-Assert)

```typescript
it('describes expected behavior', () => {
  const input = { title: 'Test Task', priority: 'high' };   // Arrange
  const result = createTask(input);                          // Act
  expect(result.title).toBe('Test Task');                    // Assert
  expect(result.status).toBe('pending');
});
```

## Naming: `[unit] [expected behavior] [condition]`

```typescript
describe('TaskService.createTask', () => {
  it('creates a task with default pending status', () => {});
  it('throws ValidationError when title is empty', () => {});
});
```

## Mock at Boundaries Only

```
Mock these:                    Don't mock these:
├── Database calls             ├── Internal utility functions
├── HTTP requests              ├── Business logic
├── File system operations     ├── Data transformations
├── External API calls         ├── Validation functions
└── Time/Date (when needed)    └── Pure functions
```

## React / API / E2E (quick patterns)

```tsx
// Query by accessible role/label, not test IDs
fireEvent.change(screen.getByRole('textbox', { name: /title/i }), { target: { value: 'New Task' } });
fireEvent.click(screen.getByRole('button', { name: /create/i }));
await waitFor(() => expect(onSubmit).toHaveBeenCalledWith({ title: 'New Task' }));
```

```typescript
// API (supertest): assert status + response shape
const res = await request(app).post('/api/tasks').send({ title: '' }).expect(422);
expect(res.body.error.code).toBe('VALIDATION_ERROR');
```

## Test Anti-Patterns

| Anti-Pattern | Problem | Better Approach |
|---|---|---|
| Testing implementation details | Breaks on refactor | Test inputs/outputs |
| Snapshot everything | No one reviews snapshot diffs | Assert specific values |
| Shared mutable state | Tests pollute each other | Setup/teardown per test |
| Testing third-party code | Wastes time, not your bug | Mock the boundary |
| Skipping tests to pass CI | Hides real bugs | Fix or delete the test |
| Overly broad assertions | Doesn't catch regressions | Be specific |
| No async error handling | Swallowed errors, false passes | Always `await` async tests |
