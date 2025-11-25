# Copilot Instructions for audiobookshelf-app

This guide helps AI coding agents work productively in this codebase. It covers general and cross-platform guidance.

For platform-specific details, see:

- `android/AGENTS.md` (Android-specific, including Media3 and ExoPlayer2 implementation details)
- `ios/AGENTS.md` (iOS-specific architecture, workflows, and conventions)

## Architecture Overview

- **Nuxt 2 web app** (frontend):
  - Routing in `pages/`, layouts in `layouts/`, shared UI in `components/`.
  - State managed via Vuex modules in `store/`.
  - Styling: Tailwind (`tailwind.config.js`) + custom CSS in `assets/`.
  - API/socket helpers: `plugins/`, `middleware/`, `mixins/`.
- **Native Android layer** (Kotlin-first):

  - Main entry: `android/app/src/main/java/com/audiobookshelf/app/MainActivity.kt`.
  - Playback, notification, and session management details are documented in `android/AGENTS.md`.
  - JS bridge: `AbsAudioPlayer.kt` plugin exposes playback/events to web.
  - Server/API: `ApiHandler.kt` centralizes HTTP communication.
  - Data/Managers: `data/` (models), `managers/` (timers, DB), `media/` (playback, progress sync).

- **Native iOS layer:**
  - See `ios/AGENTS.md` for iOS-specific architecture, workflows, and conventions.

## Developer Workflows

- **Install dependencies:** `npm install`
- **Build static web bundle:** `npm run generate`
- **Sync web bundle to native:** `npx cap sync`
- **Open Android Studio project:** `npx cap open android`
- **Open Xcode (iOS):** `npx cap open ios`
- **Android Auto port forward:** Use VS Code task `ABS: Start Android Auto port forward` or run `adb forward tcp:5277 tcp:5277`
- **Manual testing:**
  - Native: see `android/AGENTS.md` for playback, notification, Android Auto, and cast flows
  - Frontend: smoke test via `npm run dev`

## Project-Specific Conventions

- **Formatting:**
  - `.editorconfig` (UTF-8, final newline, trim trailing whitespace, 2-space indents)
  - JS/Vue/JSON: `.prettierrc` (no semicolons, single quotes, printWidth 400)
- **Logging:** Use debug-level logging for development; avoid noisy logs in release

## Integration Points

- **Capacitor 7** bridges Nuxt frontend and native Android/iOS
- **Native playback, notification, and session management:** See `android/AGENTS.md` for details
- **AbsAudioPlayer plugin**: JS-native bridge for playback control/events
- **ApiHandler**: all HTTP/server communication

## Key Files & Directories

- **Frontend (Nuxt 2):**

  - Pages: `pages/` (with subfolders for bookshelf, collection, localMedia, etc.)
  - Shared UI: `components/` (domain-organized: cards, bookshelf, forms, etc.)
  - Layouts: `layouts/` (`default.vue`, `blank.vue`)
  - State: Vuex modules in `store/` (`globals.js`, `libraries.js`, `user.js`)
  - Plugins: `plugins/` (API, constants, i18n, haptics, toast, etc.)
  - Middleware: `middleware/` (e.g., `authenticated.js`)
  - Mixins: `mixins/` (e.g., `bookshelfCardsHelpers.js`)
  - Assets: `assets/` (CSS, fonts, JS, ebook parsers)
  - Static files: `static/` (images, fonts, etc.)

- **Localization:**

  - All strings in `strings/` as JSON files for each supported language

- **Android/iOS:**

  - Native code: `android/app/` and `ios/App/`
  - Android agent instructions: `android/AGENTS.md`
  - iOS agent instructions: `ios/AGENTS.md`

- **Build/Config:**

  - Code style: `.editorconfig`, `.prettierrc`
  - Frontend config: `nuxt.config.js`, `tailwind.config.js`
  - Capacitor/Ionic config: `capacitor.config.json`, `ionic.config.json`

- **Testing/Automation:**

  - VS Code tasks/scripts: `.vscode/`
  - Mobile CI/CD: `fastlane/`

- **Other:**
  - Screenshots: `screenshots/`
  - Local media folders/permissions: see `localMedia` pages and helpers
  - Touch events/custom objects: `objects/`

## Tips for AI Agents

- For Android-specific architecture, build, and testing, see `android/AGENTS.md`
- For iOS-specific instructions, see `ios/AGENTS.md`

- Reply with concise summaries and file paths
- Suggest verification steps after edits
- Prefer existing tasks/scripts; avoid duplicates
- Respect existing code style and conventions
- Avoid destructive git commands

---

_Last updated: November 24, 2025_
