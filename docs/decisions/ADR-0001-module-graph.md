# ADR-0001 — Multi-JAR product with plugin-based API runtime identity

Date: 2026-10-27 · Status: accepted

## Context

The master prompt requires a modular plugin family with strict module
boundaries, separate installable JARs per feature, and one runtime class
identity for shared contracts. Alternatives: (a) single mega-JAR with internal
packages, (b) shaded API copy inside every feature JAR, (c) API as a real
Bukkit plugin that other plugins `depend` on.

## Decision

(c): `RebellionAPI` ships as its own small plugin (`load: STARTUP`). Feature
modules declare `depend: [RebellionAPI]`, which makes Bukkit's plugin
classloader guarantee exactly one identity of every API class at runtime.
Feature JARs never shade the API.

## Consequences

- Install order matters and is documented (API → Core → features); the
  descriptor's `depend` makes mistakes fail loudly at load instead of
  producing subtle class-cast failures.
- The API JAR stays contract-only: DTOs, service interfaces, result types.
  No game logic, no database code, no commands — it genuinely has no
  user-facing configuration, which is why it is the documented exception to
  the every-module-owns-config rule.
- Releasing an API change means releasing RebellionAPI itself; incompatible
  changes require a deprecation cycle per the stability promise.
