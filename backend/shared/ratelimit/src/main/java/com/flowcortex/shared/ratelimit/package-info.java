/**
 * Shared rate-limit library: named limiter definitions, one registry, and a token-bucket store
 * whose budgets are shared by every backend instance.
 */
@org.springframework.modulith.NamedInterface("ratelimit")
package com.flowcortex.shared.ratelimit;
