# Changelog

## Unreleased

- Move CSV logging/viewer orchestration out of `MainActivity`.
- Parse only displayed CSV columns when building graph previews.
- Move dashboard configuration JSON serialization into a focused codec.
- Remove the redundant min/max null check reported by the Kotlin compiler.

## 1.0.0

- Rename the application and project to UTCOMP Dashboard.
- Retain simple and Ralliart dashboard view hierarchies between refreshes.
- Reduce allocations in USB packet parsing and live-value formatting.
- Stream CSV rows without per-row field lists or date formatter creation.
- Make verbose packet and decoder logging opt-in.
- Report USB connection and decoded-state changes through explicit callbacks.
- Bound the USB transmit queue and harden reconnect/session cleanup.
- Preserve the legacy Android application ID for seamless upgrades.
