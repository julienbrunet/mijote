# Mijote

Mijote is a self-hosted family meal planner: collect recipes, plan shared menus and keep dinner ideas close at hand. It consists of a native Android app and a lightweight Go server backed by PostgreSQL.

**[Presentation and installation guide](https://julienbrunet.github.io/mijote/)** · **[Downloads](https://github.com/julienbrunet/mijote/releases)**

The project is under active development.

## What it does

- Import recipes from a link on Android or add them manually; search and organise a shared collection.
- Generate a menu, review and adjust it before sharing, or plan recipes and free-text meals yourself.
- Invite household members, manage access, and read cached recipes and current menus offline.
- Host your data on your own server and manage the instance through a web interface.

## Get started

Download an Android APK and the server binary for your Linux machine from [releases](https://github.com/julienbrunet/mijote/releases). The server needs PostgreSQL 13 or later; Android requires version 8.0 or later. Follow the [installation guide](https://julienbrunet.github.io/mijote/#install) to configure the database, server and HTTPS reverse proxy, then connect the app. Back up both PostgreSQL and the server's image directory.

## Repository

- [`android/`](android/) — Kotlin and Jetpack Compose Android app.
- [`server/`](server/) — Go API, database migrations and web administration. See the [server README](server/README.md), [API guide](server/API.md) and [OpenAPI reference](server/openapi.yaml).
- [`site/`](site/) — static bilingual presentation and installation site.
- [`requirements.md`](requirements.md) — product and technical requirements.

## Development

Run the server tests with `cd server && go test ./...`. Database-dependent tests also require `MIJOTE_TEST_DATABASE_URL` pointing to a disposable PostgreSQL database; see the [server README](server/README.md). Build the Android app with `cd android && ./gradlew :app:assembleDebug` using JDK 21 and the Android SDK. The presentation site is static and can be served locally without a build step.
