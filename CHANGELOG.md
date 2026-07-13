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
- Restyle controls-menu actions as larger black buttons with blue borders.
- Add editable Ralliart top-bar text sizing and field visibility.
- Scale the top chrome and Ralliart canvas consistently across display densities.
- Keep the controls panel usable on short high-density landscape displays.
- Restore dynamic source subtitles on simple dashboard cards.
- Move protocol-only actions into a descriptive diagnostics menu.
- Refresh firmware and input mappings automatically after USB connects.
- Restyle the CSV logging menu with the dark dashboard action design.
- Add persistent, exportable app diagnostics with crash and UI-stall tracking.
- Replace the CSV preview dialog with a full-screen dark viewer.
- Stream large CSV files with bounded graph memory and visible load progress.
- Add a top-bar CSV LOG/STOP shortcut with a configurable destination.
- Apply the dark application theme to remaining Android dialogs and popups.
- Add selectable CSV graph rows while keeping AFR, boost, oil pressure, and oil temperature as defaults.
- Make large CSV graphs vertically and horizontally scrollable without compressing extra rows.
- Thin graph strokes on high-density displays and remove redundant cursor header text.
- Remove deprecated direct status/navigation bar color assignments from the CSV viewer.
- Add a full-screen single-value live signal inspector with raw and smoothed traces.
- Report rolling noise statistics and use the dashboard smoothing algorithm interactively.
- Sample each inspected value only when its actual UTCOMP source packet updates.
- Make full protocol tracing opt-in and show only the latest protocol event by default.
- Add automatic and manual periodic-noise rejection to the live signal inspector.

## 1.0.0

- Rename the application and project to UTCOMP Dashboard.
- Retain simple and Ralliart dashboard view hierarchies between refreshes.
- Reduce allocations in USB packet parsing and live-value formatting.
- Stream CSV rows without per-row field lists or date formatter creation.
- Make verbose packet and decoder logging opt-in.
- Report USB connection and decoded-state changes through explicit callbacks.
- Bound the USB transmit queue and harden reconnect/session cleanup.
- Preserve the legacy Android application ID for seamless upgrades.
