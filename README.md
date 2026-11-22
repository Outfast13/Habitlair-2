# HabitLair

Native Android habit, task, journal and Pomodoro app with a bring-your-own-key AI coach.
Kotlin · Jetpack Compose · Material 3 (Material You) · MVVM · Room · DataStore · WorkManager/AlarmManager · Glance widget.

## Build
1. Open this folder in Android Studio (Ladybug or newer, JDK 17). It generates the Gradle wrapper and syncs.
2. Run the `app` configuration on a device/emulator (API 26+).
   Or from a terminal with Gradle 8.9+: `gradle :app:assembleDebug`.

## Layout
- `data/`   Room entities and DAOs, DataStore settings, encrypted API-key store, repository, backup/restore, streaks
- `ai/`     HTTP client (Gemini, OpenAI, OpenRouter, custom), tolerant JSON parser, offline generator, AI service
- `work/`   AlarmManager scheduler, notification channels, receivers (reminder, action, boot), WorkManager maintenance worker
- `widget/` Glance home-screen widget with tap-to-check
- `timer/`  Pomodoro engine (app-scoped)
- `ui/`     Compose screens, editors, view models, theme

## Notes
- API keys live in EncryptedSharedPreferences; they only enter a backup if you tick "Include AI API keys" on export.
- Backups are .zip files (backup.json + media/) with everything: habits and history, tasks, journal, categories, Pomodoro data, AI chat and change log, settings, attachments and optionally the AI API keys (stored unencrypted inside the zip). Old v1 .json backups can still be imported.
- The AI coach never changes data until you confirm a proposal; every applied proposal is logged and can be undone (Settings → AI change history).
- Exact alarms need the "Alarms & reminders" permission on Android 12+ (Settings has a shortcut); otherwise reminders fire inexactly.
- Cleartext HTTP is enabled so a custom endpoint such as a LAN Ollama server works.
