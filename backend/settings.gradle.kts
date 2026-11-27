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
    ":modules:jobs",
    ":modules:llm",
    ":modules:support",
    ":modules:content",
    ":modules:settings",
    ":modules:space",
    ":modules:drive",
    ":modules:profile",
    ":modules:asset",
    ":modules:entity",
    ":modules:realtime",
    ":modules:onboarding",
    ":modules:mail",
    ":modules:connector",
    ":modules:billing",
    ":modules:chat",
    ":modules:cvcheck",
    ":modules:notification",
    ":modules:trend",
    ":modules:trash",
    ":shared:web",
    ":shared:security",
    ":shared:ratelimit",
)
