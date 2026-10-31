# Mijote — Project rules

Mijote is a self-hosted family weekly menu planner (native Android app in Kotlin/Compose + Go backend + PostgreSQL, target: Raspberry Pi 3B+ with 1 GB RAM). See `requirements.md`.

## Language
- All code, code comments, documentation and commit messages are written in **English**.
- Conversation with the user happens in **French**.
- Keep answers to the user short and to the point: no long tables or exhaustive detail unless asked; ask only the questions that matter.

## Commits
- Push to your working branch first and follow its CI (GitHub Actions) until it finishes; if it fails, fix it and push again. Once the branch CI is green, push to `main` and follow the `main` CI the same way before reporting the work as done.
- Use Conventional Commits: `feat:`, `fix:`, `docs:`, `refactor:`, `test:`, `chore:`, `build:`, `ci:`…

## Branches
- Name each new branch after the change in progress, e.g. `feat/recipe-import`, `fix/menu-swap`, `docs/sync-policy`.
- Once a branch is merged into `main`, delete it (locally and on the remote).

## Scope and decisions
- `requirements.md` is the source of truth for scope. Check it before implementing a feature; update it when a decision changes.
- Record significant technical decisions in `requirements.md` (or here for dev rules). **Never create ADR files or other standalone decision docs**: either write code or document in `requirements.md`.
- Never treat undecided points as decided. Ask the user first.

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
- `prototypes/theme-playground/`: throwaway Compose app used to tune the theme.

## CI/CD and releases
- GitHub Actions: `.github/workflows/android.yml` (`android/`) and `server.yml` (`server/`, API + web admin). Each runs only when its directory changes; in-progress runs are cancelled; caches enabled.
- Android: SemVer via tags `android-vX.Y.Z`; a tag publishes a public GitHub release with checksums.
- Server: every push to `main` touching `server/` publishes release `server-v0.1.<run number>` automatically (a `server-vX.Y.Z` tag still works). After merging, tell the user the published server version.
- Android: Gradle reads `-PversionName` / `-PversionCode` (`MAJOR*10000+MINOR*100+PATCH`); release signing from env `MIJOTE_KEYSTORE`, `MIJOTE_KEYSTORE_PASSWORD`, `MIJOTE_KEY_ALIAS`, `MIJOTE_KEY_PASSWORD` (repo secrets `ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, `ANDROID_KEY_PASSWORD`).
- Server: static binaries for linux amd64, arm64, armv7; `main` declares `var version = "dev"` (set via `-X main.version`). Docker image not built yet.

## Backend
- `server/`: Go module (Go 1.24, pgx v5), `main` package at its root. Schema changes = new file in `server/internal/migrate/migrations/` (see `requirements.md` §12).
- PostgreSQL 16 binaries are in `/usr/lib/postgresql/16/bin`; run them as the `postgres` user with a data dir outside the scratchpad (e.g. `/var/tmp`), then set `MIJOTE_TEST_DATABASE_URL`.
