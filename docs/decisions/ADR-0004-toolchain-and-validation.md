# ADR-0004 — Java 25 toolchain, strict closed-tag MiniMessage validation, testable core services

Date: 2026-10-28 · Status: accepted

## Context

Three foundational choices needed evidence:

1. **Toolchain.** Paper 26.3's `paper-api` ships class files at major 69
   (Java 25). Building against Java 21 fails; running Gradle itself on 21 is
   fine because Gradle 9.8.2 auto-provisions the Java 25 toolchain via the
   wrapper.
2. **Message validation.** Bundled templates previously used idiomatic
   *unclosed* MiniMessage tags (`<red>text`). Direct probes against
   adventure-text-minimessage 5.2.0 (the version Paper 26.3 provides
   transitively) showed the default MiniMessage instance throws on **no**
   malformed input — everything degrades to literal text — while
   `builder().strict(true)` throws precisely on unclosed tags. Admin
   copy/paste mistakes would otherwise render silently broken output.
3. **Bukkit coupling in core services.** `DiagnosticsService` called
   `Bukkit.getVersion()` statically, which NPEs outside a live server and
   makes the service untestable in plain JUnit.

## Decision

1. Toolchain pinned at Java 25 in every module build file; wrapper
   provisions it; CI uses Temurin 25.
2. Well-formedness contract: every tag in every `messages.yml` must be
   explicitly closed. Validation uses a strict MiniMessage instance plus a
   catch-all resolver that accepts bare runtime placeholders
   (`<version>`, `<incident>`, …) but never shadows standard tags
   (`has(name) == !standard.has(name) && identifier`). The render path stays
   lenient with real resolvers. Bundled defaults rewritten fully closed.
3. Core services take their environment as injected dependencies
   (`Supplier<String> serverVersion`) — no Bukkit statics in service code;
   event handlers and commands remain the only Bukkit-touching layer.

## Consequences

- Hand-edited files with unclosed tags now fail load with the exact key and
  reason instead of shipping visibly broken chat output.
- Malformed tag *arguments* (e.g. `<color:#zzzzzz>`) still degrade to
  literal text — a documented limitation of MiniMessage 5.2.0, recorded in
  REFERENCE_LEDGER rather than papered over.
- The full suite runs with zero Bukkit server runtime; integration
  verification stays a separate, honest gate (TEST_PLAN).
