# 0001 — CI/CD and independent release versioning

## Context
The repository holds two deliverables with different lifecycles: the Android app (`android/`) and the Go backend binary that also serves the htmx web admin (`server/`). Both must be downloadable by anyone, and CI minutes should be spent sparingly.

## Decision
- GitHub Actions, one workflow per deliverable (`.github/workflows/android.yml`, `server.yml`), each triggered only by changes under its own directory (path filters), with `cancel-in-progress` concurrency and dependency caching.
- Independent SemVer versioning driven by git tags: `android-vX.Y.Z` and `server-vX.Y.Z`. A tag builds and publishes a GitHub release with the artifacts and checksums.
- Android: `versionName` = tag version, `versionCode` = `MAJOR*10000 + MINOR*100 + PATCH`, passed as Gradle properties. Release APKs are signed with a keystore stored in repository secrets (`ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, `ANDROID_KEY_PASSWORD`).
- Server: static binaries cross-compiled on a single runner for `linux-amd64`, `linux-arm64` and `linux-armv7` (Raspberry Pi 3B+ on 32-bit OS); version injected with `-X main.version`.

## Consequences
- Releasing = pushing a tag, e.g. `git tag server-v0.1.0 && git push origin server-v0.1.0`.
- The Android Gradle build must read `versionName`/`versionCode` properties and the `MIJOTE_KEYSTORE*` / `MIJOTE_KEY_*` environment variables for its release signing config.
- The server's `main` package must declare `var version = "dev"`.
- Docker images (NFR-14) are not covered yet and will be added to the server workflow later.
