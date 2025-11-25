# AGENTS.md for iOS (audiobookshelf-app)

This guide provides architecture, workflows, and conventions for AI coding agents working on the iOS layer of audiobookshelf-app. It is intended to complement the general instructions in `.github/copilot-instructions.md` and the Android-specific `android/AGENTS.md`.

---

## Architecture Overview

- **Capacitor Bridge:**
  - The Nuxt 2 frontend communicates with native iOS code via Capacitor plugins.
  - Main plugin: `AbsAudioPlayer.swift` (in `ios/App/App/plugins/`).

- **Playback Management:**
  - `PlayerHandler.swift` (in `ios/App/Shared/player/`): Static class managing playback sessions, player lifecycle, and session cleanup.
  - `AudioPlayer.swift` (in `ios/App/Shared/player/`): Core playback engine using `AVQueuePlayer`. Handles track queue, seeking, playback rate, sleep timer, and remote controls.

- **Session & Progress:**
  - Playback sessions and progress are synced using Realm database models.
  - Metadata and playback state are updated via `NowPlayingInfo` and sent to the frontend.

---

## Developer Workflows

- **Install dependencies:** Use CocoaPods or Swift Package Manager as required.
- **Build and run:** Open `ios/App/` in Xcode. Use `npx cap open ios` from project root to sync web bundle and open Xcode.
- **Manual testing:**
  - Test playback, sleep timer, remote controls, and session sync in simulator and on device.
  - Use Nuxt frontend for UI flows; verify plugin events and metadata updates.

---

## Key Files & Directories

- `ios/App/App/plugins/AbsAudioPlayer.swift` — Capacitor plugin for audio playback/events
- `ios/App/Shared/player/PlayerHandler.swift` — Playback session manager
- `ios/App/Shared/player/AudioPlayer.swift` — AVQueuePlayer-based playback engine
- `ios/App/Shared/player/` — Rate managers, sleep timer, progress, utility files
- `ios/App/Shared/models/` — Realm models for sessions, items, progress
- `ios/App/AppDelegate.swift`, `Info.plist` — App entry/configuration

---

## Integration Points

- **Frontend ↔ Native:**
  - All playback control/events are exposed via Capacitor plugin methods and listeners.
  - Metadata, progress, and session state are sent to the frontend for UI updates.

- **Remote Controls:**
  - Integrated with iOS `MPRemoteCommandCenter` for play/pause/seek/skip.

---

## Project-Specific Conventions

- **Formatting:**
  - Swift: 4-space indents, UTF-8, final newline, trim trailing whitespace
  - Use Xcode's default formatting and linting tools
- **Logging:**
  - Use `AbsLogger` for debug/info/error logs
- **Testing:**
  - Manual playback and session sync testing recommended

---

## Tips for AI Agents

- Reference `.github/copilot-instructions.md` for cross-platform guidance
- Use this file for iOS-specific architecture, workflows, and conventions
- Respect existing code style and project structure
- Suggest verification steps after edits
- Avoid destructive git commands

---

_Last updated: November 24, 2025_
