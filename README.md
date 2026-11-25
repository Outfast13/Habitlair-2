# HabitLair

Native Android habit, task, journal and Pomodoro app with a bring-your-own-key AI coach.
Kotlin · Jetpack Compose · Material 3 (Material You) · MVVM · Room · DataStore · WorkManager/AlarmManager · Glance widget.

## Build
- **SDK:** compileSdk/targetSdk 36 (Android 16), AGP 8.13, Gradle 8.13, Kotlin 2.2.20, Compose BOM 2025.09.
- **GitHub:** push to GitHub; `.github/workflows/build.yml` builds a debug APK and uploads it as the `HabitLair-debug-apk` artifact (Actions tab → run → Artifacts).
  It also tries an R8-optimised APK (`HabitLair-optimised-apk`), which is much smoother than debug.
- **Android Studio:** open this folder (JDK 17), let it sync, run `app`.
- **Terminal:** Gradle 8.9+ → `gradle :app:assembleDebug`.

## Using it
- Tabs: Today, Tasks, Journal, Timer, AI. Settings is the gear on Today; categories and AI change history live there.
- Alarms ring: looping alarm sound + vibration + a full-screen alarm (Done / Snooze / Dismiss), even on the lock screen. Turn it off in Settings → Notifications & Alarms to get plain notifications.
- Time pickers let you type the digits or use the clock. Text fields have a mic button for voice typing.
- Attachments: Media, Camera (photo/video, torch, zoom), Audio recorder, Files. Icons only; Settings → Accessibility adds text labels.
- Three widgets (Material You, Midnight, Sunrise), each resizable down to 2 icons wide.
- Tap a habit or task row to tick it. Long-press to select, then Edit, Move (up/down), Details or Delete.
- Home-screen widget: tap a row to tick it; the circle on the right shows the state.
- First launch asks for notifications, exact alarms and (optional) microphone; each can be allowed or skipped.

## Layout
- `data/`   Room entities and DAOs, DataStore settings, encrypted API-key store, repository, zip backup/restore, streaks
- `ai/`     HTTP client (Gemini, OpenAI, OpenRouter, custom), tolerant JSON parser, chat proposals, apply and undo, offline generator
- `work/`   AlarmManager scheduler, notification channels, receivers, WorkManager maintenance worker
- `widget/` Glance home-screen widget
- `timer/`  Pomodoro engine (app-scoped)
- `ui/`     Compose screens, editors, onboarding, view models, theme

## Notes
- API keys live in EncryptedSharedPreferences; they only enter a backup if you tick "Include AI API keys" on export.
- Backups are .zip files (backup.json + media/) with everything, including attachments and AI chat history. Old .json backups still import.
- The AI coach never changes data until you confirm a proposal; each applied proposal is logged and can be undone.
- Cleartext HTTP is enabled so a custom endpoint such as a LAN Ollama server works.
