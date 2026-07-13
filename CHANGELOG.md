# Changelog

## Unreleased

- Extract simple and Ralliart rendering from `MainActivity`.
- Add configurable dashboard pages with 1×1 through 4×4 grids.
- Support merging, splitting, removing, and restoring dashboard boxes.
- Add editable card borders and independent min/max text scaling.
- Split Ralliart oil minimum and maximum values into the lower corners.
- Redesign the dashboard editor with larger inverted rows and separators.
- Reduce refresh allocations by caching normalized layouts and rendered text.
- Move CSV logging/viewer orchestration out of `MainActivity`.
- Parse only displayed CSV columns when building graph previews.
- Move dashboard configuration JSON serialization into a focused codec.
- Remove the redundant min/max null check reported by the Kotlin compiler.
- Move live USB polling off the Android main looper.
- Retry transient polling-request write failures before reopening USB.
- Add fast first reconnects and structured USB close diagnostics.
- Fix debug-mode title and restore dashboard touch access to the menu.
- Increase the main controls-menu button size and touch targets.

## 1.0.0

- Rename the application and project to UTCOMP Dashboard.
- Retain simple and Ralliart dashboard view hierarchies between refreshes.
- Reduce allocations in USB packet parsing and live-value formatting.
- Stream CSV rows without per-row field lists or date formatter creation.
- Make verbose packet and decoder logging opt-in.
- Report USB connection and decoded-state changes through explicit callbacks.
- Bound the USB transmit queue and harden reconnect/session cleanup.
- Preserve the legacy Android application ID for seamless upgrades.
