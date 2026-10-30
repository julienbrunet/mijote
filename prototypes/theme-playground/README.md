# Theme playground (throwaway prototype)

Single-screen Jetpack Compose app used only to tune the visual theme ("Calm Kit" palette, Inter font).
It does **not** decide the mobile stack (open question OQ-3 in `requirements.md`).

- Build the APK: `./gradlew :app:assembleDebug`
- Render the screenshot on the JVM (Paparazzi, no emulator): `./gradlew :app:recordPaparazziDebug`
  → `app/src/test/snapshots/images/`
- Palette and typography: `app/src/main/java/app/mijote/prototype/ui/Theme.kt`
