rootProject.name = "flow-cortex"

// Multi-module build (ADR-0003, modular monolith via Spring Modulith). Module
// boundaries are enforced at build time by ModularityTests.verify() in
// :boot:app; see backend/buildSrc for the shared convention plugins.
include(
    ":boot:app",
    ":modules:user",
    ":modules:workflow",
    ":modules:media",
    ":modules:credit",
    ":modules:llm",
    ":modules:support",
    ":modules:space",
    ":modules:realtime",
    ":shared:web",
    ":shared:security",
    ":shared:ratelimit",
)
