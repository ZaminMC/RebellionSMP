# ADR-0003 — Stable `plugin.yml` descriptor; no `paper-plugin.yml`

Date: 2026-10-27 · Status: accepted

## Context

Paper offers two descriptors: the stable Bukkit-style `plugin.yml` and the
newer `paper-plugin.yml`, which Paper documents as experimental with
different (and less battle-tested) load-order/classloading semantics. The
master prompt requires the ordinary loader unless official documentation
demonstrates the Paper-specific descriptor is stable and better.

## Decision

Use `plugin.yml` (with `api-version: '26.3'`, `load: STARTUP` for
API/Core, `depend`/`softdepend` for explicit ordering). Revisit only when
Paper marks `paper-plugin.yml` stable and demonstrates a concrete benefit for
this product family.

## Consequences

- Dependency ordering is enforced by the loader we can rely on; feature
  modules get the documented "fail to load when a required plugin is absent"
  behavior for free.
- We keep the standard permission/command registration surface that
  server admins and permission plugins already understand.
- Cost: none identified today; the experimental descriptor's isolation
  benefits are not needed by a cooperating plugin family that controls its
  own dependency graph.
