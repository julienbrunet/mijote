# Mijote — Project rules

Mijote is a self-hosted family weekly menu planner (mobile app + Go-favored backend + PostgreSQL). See `requirements.md`.

## Language
- All code, code comments, documentation and commit messages are written in **English**.
- Conversation with the user happens in **French**.
- Keep answers to the user short and to the point: no long tables or exhaustive detail unless asked; ask only the questions that matter.

## Commits
- Use Conventional Commits: `feat:`, `fix:`, `docs:`, `refactor:`, `test:`, `chore:`, `build:`, `ci:`…

## Scope and decisions
- `requirements.md` is the source of truth for scope. Check it before implementing a feature; update it when a decision changes.
- Record each significant technical decision as a short ADR in `docs/adr/` (`NNNN-title.md`: Context, Decision, Consequences).
- Never treat open questions (requirements.md §7) as decided. Ask the user first.

## Constraints
- Low resources: justify any new dependency or service; prefer the standard library when reasonable.
- No WebSockets / real-time; REST only, versioned API.
- Keep the backend stateless apart from PostgreSQL and the image directory, so the instance stays easy to back up and redeploy.
- Never store images in the database.
- The backend never scrapes recipe web pages; extraction happens on the device.

## Development environment (cloud sandbox, verified 2026-10-30)
- JDK 21 (system), Gradle 8.14.3 at `/opt/gradle` (projects ship a Gradle wrapper).
- Android SDK is **not** preinstalled. Install it into `~/android-sdk`:
  1. download `commandlinetools-linux-11076708_latest.zip` from `dl.google.com/android/repository/` and unzip it to `~/android-sdk/cmdline-tools/latest`;
  2. `yes | sdkmanager --licenses`, then `sdkmanager "platforms;android-35" "build-tools;35.0.0" "platform-tools"`;
  3. write `sdk.dir=$HOME/android-sdk` to the project's `local.properties` (git-ignored).
- No `/dev/kvm`: the Android emulator cannot run. Screenshots are rendered on the JVM with **Paparazzi** (`./gradlew :app:recordPaparazziDebug`).
- Maven Central may answer `429 Too Many Requests`: just retry the build.
- Verified toolchain for Android prototypes: AGP 8.7.3, Kotlin 2.0.21, Compose BOM 2024.12.01, Paparazzi 1.3.5, compileSdk/targetSdk 35, minSdk 26.
- Fonts: Google Fonts GitHub raw URLs are blocked; static TTFs are available from `cdn.jsdelivr.net/fontsource/fonts/<family>@latest/latin-<weight>-normal.ttf`.

## Prototypes
- `prototypes/theme-playground/`: throwaway Compose app used to tune the theme. It does not decide OQ-3 (mobile stack).
