# Architecture Decision Log — Flow-Cortex

Lightweight ADRs: one short entry per decision that a future reader (including
future-you) would otherwise have to reverse-engineer. Newest at the bottom.
Format per entry: **Context → Decision → Consequences**.

---

## ADR-0001 — Scale the reference conventions down for a solo project

- **Context:** Flow-Cortex adopts the DigiShield reference repo's conventions,
  but DigiShield is a multi-team enterprise system (9 modules, Terraform/EKS,
  three static-analysis gates, full BRD/FRD/ADR doc set). Copying all of that
  into a one-person project is over-engineering.
- **Decision:** Adopt only "Tầng 0–1" now:
  - Git discipline (Conventional Commits + protected `main`) — copied verbatim.
  - `.editorconfig`, ESLint/Prettier, layered package structure.
  - A single Gradle module (not a multi-module build) until a second bounded
    context actually exists.
  - Coverage (JaCoCo / Vitest) **measured, not gated** — the gate is a later,
    separate decision once real numbers exist.
  - Deferred: convention plugins (`buildSrc`), Spring Modulith, Checkstyle/
    SpotBugs gates, contract-first codegen, CI/CD, Terraform.
- **Consequences:** Fast to start and easy to run. When the project grows,
  promote to a multi-module build (extract `buildSrc` convention plugins), turn
  the coverage measurement into a ratchet gate, and add the deferred tầng in
  order. The reference repo remains the template for each step.

---

## ADR-0002 — Java 25 + Spring Boot 4.1, Gradle Kotlin DSL

- **Context:** Match the reference stack and the JDK already installed on the
  dev machine.
- **Decision:** Java 25 toolchain, Spring Boot 4.1.0, Gradle 9.6.1 (Kotlin DSL),
  `io.spring.dependency-management` for BOM-managed versions.
- **Consequences:** Spring Boot 4 reorganised some packages — e.g. the MVC test
  slice (`@WebMvcTest`) moved to `org.springframework.boot.webmvc.test.autoconfigure`
  and now needs `spring-boot-starter-webmvc-test` (it is no longer part of
  `spring-boot-starter-test`). Keep this in mind when copying Boot 2/3 examples.
