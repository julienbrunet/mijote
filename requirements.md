# Mijote — Requirements

Status: draft v0.1. Server skeleton in `server/` (migrations, initial schema); menu generation implemented (§10).

Priorities: **Must** (required for v1), **Should** (expected in v1, can slip), **Could** (nice to have).

## 1. Vision and goals

### Problem
Planning a family's weekly menus is repetitive: finding recipes, avoiding repeats, balancing dishes, fitting prep time to the day. Recipes are scattered across websites and family members don't share one plan.

### Target users
- A family sharing one self-hosted instance (one instance = one family).
- Other families able to self-host the same backend and use the same store app.

### Guiding principles
1. **UX first**: the app feels instant (local cache, offline reading), smooth gestures, minimal friction.
2. **Very low resource usage**: the backend runs on a small machine.
3. **Self-hostable**: easy to deploy, back up and update; no dependency on third-party sync services.

## 2. Scope

### In v1
- Recipe import from a web link (paste or Android share sheet), extracted on device from schema.org/Recipe.
- Shared recipe base with tags.
- Per-weekday meal settings and a user-defined planning horizon.
- Server-side menu generation and post-generation adjustments (swap, lock, move).
- Shared menus across family members, offline reading.
- Setup wizard, invitations, two roles.
- Web admin (server-rendered, htmx).
- Docker distribution (ARM + x86), env-var configuration, automatic migrations, documented backups, API versioning.

- Presentation site on GitHub Pages (`site/`, EN/FR): description, downloads, install guide; kept in sync with every feature change (see `CLAUDE.md`).

### Out of scope / later
- Multi-household per instance (no `household_id`).
- OAuth / third-party login.
- Real-time sync (WebSockets, push).
- Server-side scraping of web pages.
- Third-party sync (Google Drive, Firebase, P2P) — rejected.
- Shopping list, portion management, leftovers (decided: not in v1).
- iOS app (decided: Android only for now).

## 3. Roles and permissions

| Capability | Admin | Member |
|---|---|---|
| Manage accounts and invitations | ✅ | ❌ |
| Manage family settings (default meals, preferences) | ✅ | ❌ |
| Add / edit recipes | ✅ | ✅ |
| Delete recipes, manage tags | ✅ | ❌ (to confirm; the API currently reserves archiving to the admin, tags are managed in the web admin only) |
| Generate and adjust menus | ✅ | ✅ |
| Access web admin | ✅ | ❌ |

## 4. Functional requirements

### 4.1 Recipe import (REC-IMP)
| ID | Requirement | Priority |
|---|---|---|
| FR-REC-01 | User can add a recipe by pasting a URL in the app. | Must |
| FR-REC-02 | App registers as an Android share target ("Share → Mijote") for URLs. | Must |
| FR-REC-03 | The mobile app fetches the page and extracts schema.org/Recipe data (JSON-LD, then microdata if feasible): title, image, ingredients, prep/cook/total times, source URL. | Must |
| FR-REC-04 | Extracted data is shown in an editable preview before saving. | Must |
| FR-REC-05 | The app sends the already-structured recipe to the backend, then uploads the recipe image to it (JPEG, PNG or GIF); the backend never fetches recipe pages or images, and nothing is loaded from outside the instance. | Must |
| FR-REC-06 | If no structured data is found, the user can still save the recipe by filling fields manually. | Should |
| FR-REC-07 | Duplicate detection by source URL, with a warning. | Should |

### 4.2 Recipe management and tags (REC)
| ID | Requirement | Priority |
|---|---|---|
| FR-REC-10 | List, search and view recipes; works offline from local cache. | Must |
| FR-REC-11 | Edit and delete recipes. | Must |
| FR-REC-12 | Tag recipes by dish type: meat / fish / vegetarian. | Must |
| FR-REC-13 | Store prep time used to classify recipes as quick or long. | Must |
| FR-REC-14 | Tag recipes by season(s). | Should |
| FR-REC-15 | ~~Mark recipes as favorites.~~ Dropped: no favorites. | — |
| FR-REC-18 | Recipes have an ordered list of steps, extracted from `recipeInstructions` (split into steps when given as one block) and editable. | Must |
| FR-REC-19 | Recipes have free-form tags shared by the family (e.g. salad, summer, hot dish, oven), with autocompletion; recipes are searchable by title and tags. | Must |
| FR-REC-20 | Recipes store servings, the meals they suit (lunch, dinner or both) and personal notes. | Should |
| FR-REC-21 | Ingredients are stored as structured lines (quantity, unit, name), parsed on the device from the extracted text and editable before saving. | Must |
| FR-REC-16 | Suggest tags automatically from extracted data (e.g. keywords, category). | Could |
| FR-REC-17 | Image stored as original URL or a compressed thumbnail on disk, never in the database. | Must |

### 4.3 Planning settings (PLAN)
| ID | Requirement | Priority |
|---|---|---|
| FR-PLAN-01 | For each weekday, choose the meals to plan: lunch + dinner, dinner only, or none. | Must |
| FR-PLAN-02 | Admin defines the family default meals per weekday. | Must |
| FR-PLAN-03 | User chooses a planning horizon of X days (user-defined). | Must |
| FR-PLAN-04 | User can add or remove a single meal slot in the period (e.g. away on Tuesday lunch). | Should |

### 4.4 Menu generation (GEN)
| ID | Requirement | Priority |
|---|---|---|
| FR-GEN-01 | Generation runs server-side so all members see the same result. | Must |
| FR-GEN-02 | Generation is available once the recipe base reaches a minimum size; the app explains what is missing otherwise. Threshold to define. | Must |
| FR-GEN-03 | Avoid recently used recipes. | Must |
| FR-GEN-04 | Balance dish types over the period towards the target split set by the admin (e.g. 40 % vegetarian, 30 % meat, 30 % fish). | Must |
| FR-GEN-05 | Prefer quick recipes on weekdays and allow longer ones on weekends (preference, not strict). | Must |
| FR-GEN-06 | Prefer in-season recipes (preference, not strict). | Should |
| FR-GEN-07 | ~~Favor favorite recipes.~~ Dropped: no favorites. | — |
| FR-GEN-08 | Generation follows the algorithm in §10. | Must |

### 4.5 Adjustments after generation (ADJ)
| ID | Requirement | Priority |
|---|---|---|
| FR-ADJ-01 | Swap a meal's recipe with one gesture (server proposes another suitable recipe). | Must |
| FR-ADJ-02 | Lock meals; regeneration keeps locked meals. | Must |
| FR-ADJ-03 | Move meals by drag and drop between slots. | Must |
| FR-ADJ-04 | Manually pick a specific recipe for a slot. | Should |
| FR-ADJ-05 | Adjustments feel instant (optimistic UI), then sync to the server. | Must |

### 4.6 Sharing across family members (SHR)
| ID | Requirement | Priority |
|---|---|---|
| FR-SHR-01 | One shared recipe base and one shared menu per instance. | Must |
| FR-SHR-02 | Clients refresh on app open and on pull-to-refresh (no real-time). | Must |
| FR-SHR-03 | Concurrent edits: last write wins and the overwritten user is informed (see §11). | Must |

### 4.7 Onboarding and invitations (ONB)
| ID | Requirement | Priority |
|---|---|---|
| FR-ONB-01 | On first start, a setup wizard (web) creates the admin account. | Must |
| FR-ONB-02 | Admin generates invitations as a link or QR code containing the server URL and a single-use code. | Must |
| FR-ONB-03 | Member scans/opens the invitation and sets a password. | Must |
| FR-ONB-04 | Invitations expire and can be revoked. | Should |
| FR-ONB-05 | On first launch, the app asks for the server URL (typed or scanned); one store app serves any instance. | Must |
| FR-ONB-06 | Login by username + password (no email); no OAuth. | Must |

### 4.8 Web admin (ADM)
| ID | Requirement | Priority |
|---|---|---|
| FR-ADM-01 | Server-rendered pages with htmx, embedded in the backend binary; no SPA. | Must |
| FR-ADM-02 | Manage accounts (list, deactivate, reset password) and invitations. | Must |
| FR-ADM-03 | Manage family settings (default meals, preferences). | Must |
| FR-ADM-04 | Manage recipes (create, edit, archive, restore) and tags (rename, delete). | Should |
| FR-ADM-05 | Show instance info (version, API version, DB status). | Could |
| FR-ADM-06 | Create and revoke personal API tokens (read-only or read/write, with expiry) for scripts and assistants. | Should |

## 5. Non-functional requirements

| ID | Area | Requirement |
|---|---|---|
| NFR-01 | Resources | The whole stack (Go backend + PostgreSQL) runs comfortably on a Raspberry Pi 3B+ with 1 GB RAM alongside the OS; target backend memory < 50 MB. |
| NFR-02 | Resources | PostgreSQL tuned for low memory: small `shared_buffers`, low `max_connections` and `work_mem`; config shipped with the compose file. |
| NFR-03 | Resources | Simple REST API; no WebSockets or long-lived connections. |
| NFR-04 | Resources | Images stored as original URL or compressed thumbnail on disk; never in the database. |
| NFR-05 | Offline | Recipes and current menus readable offline from local cache. |
| NFR-06 | Offline | Offline mode is **read-only**: edit actions are disabled with a clear message until the server is reachable. |
| NFR-07 | Perceived performance | Screens render from local cache immediately; network refresh happens in background. |
| NFR-08 | Perceived performance | Gestures (swap, drag and drop, lock) respond without waiting for the network. |
| NFR-09 | Security | Passwords hashed with a modern algorithm (e.g. argon2id / bcrypt). |
| NFR-10 | Security | Token-based API authentication; tokens revocable (see §13). |
| NFR-11 | Security | HTTPS expected in production (via reverse proxy); documented. |
| NFR-12 | Security | Invitation codes single-use, random, time-limited. |
| NFR-13 | API versioning | API is versioned (e.g. `/api/v1`); server exposes its supported versions; app degrades gracefully against older instances. |
| NFR-14 | Deployment | Ready-to-use `docker-compose.yml` (backend + PostgreSQL). |
| NFR-15 | Deployment | Container images for `linux/arm64`, `linux/arm/v7` (32-bit Raspberry Pi OS) and `linux/amd64`. |
| NFR-16 | Deployment | Configuration through environment variables only. |
| NFR-17 | Deployment | Database migrations run automatically at startup. |
| NFR-18 | Deployment | Backend is stateless apart from PostgreSQL and the image directory. |
| NFR-19 | Backups | Backup and restore documented (`pg_dump` + image directory). |
| NFR-20 | Deployment | App self-update (release builds): at launch the app reads the GitHub releases of the project, and when an `android-v*` release is newer than the installed version it offers to update. The APK is downloaded, checked against the published SHA-256 and handed to the system installer; Android always asks the user to confirm (no silent install outside a store). Debug builds never check. |

## 6. Architecture overview

| Component | Responsibilities |
|---|---|
| **Mobile app** (Kotlin / Jetpack Compose) | Server URL setup, login, share-sheet target, page fetch + schema.org/Recipe extraction, local cache and offline reading, optimistic UI for adjustments, sync on open / pull-to-refresh. |
| **Backend API** (Go) | Versioned REST API, auth and roles, recipe storage, thumbnail storage on disk, **menu generation** and swap suggestions, invitations, migrations at startup. |
| **Web admin** | Server-rendered htmx pages in the same binary: setup wizard, accounts, invitations, family settings, recipes. |
| **PostgreSQL** | Single source of truth for accounts, recipes, tags, settings, menus. Low-memory tuning. |
| **Image directory** | Compressed thumbnails on disk (volume). |

Where logic runs:
- **On device**: recipe extraction, caching, UI.
- **On server**: generation, permissions, persistence, invitations.

```
[Mobile app] --HTTPS REST /api/vN--> [Backend binary: API + htmx admin] --> [PostgreSQL]
                                                     \--> [image dir]
[Browser (admin)] ---------HTTPS-----------/
```

## 7. Decisions on former open questions (2026-10-30)

| ID | Question | Decision |
|---|---|---|
| OQ-1 | Target machine | **Raspberry Pi 3B+, 1 GB RAM** (ARM). x86 still supported for other families. |
| OQ-2 | Backend language | **Go**. |
| OQ-3 | Mobile stack | **Native Android (Kotlin / Jetpack Compose)**. No iPhone app for now. |
| OQ-4 | Later features | **None in v1**: shopping list, portions and leftovers stay out of scope. |

Remaining open points: none.

## 8. Visual design (decided)

Theme name: **Sunny Kit** (2026-10-31, replaces the former "Calm Kit") — warm and colorful: butter and peach sheets on a cream background, white cards, a soft slate grey as the neutral accent, very large radii.

### Theme
- **Light mode only** for now. A dark variant is out of scope until requested.
- Edge-to-edge display: content scrolls behind the system bars and the bottom navigation. System bar icons are always dark (light style), whatever the system theme.

### Color palette
| Token | Hex | Usage |
|---|---|---|
| Butter | `#FFD88C` | **App screen background** (all screens), user corner block |
| Butter light | `#FFEBC2` | Surface variant |
| Peach | `#FFA98B` | "New week" button, card corner actions |
| Peach deep | `#F4876A` | Primary: main buttons, focused fields, first bottom tab |
| Slate | `#8E8E93` | Neutral accent: search tile, secondary actions |
| Cream | `#FFF5E6` | Background of the presentation site and the web admin |
| Card | `#FFFFFF` | Cards, header, dialogs |
| Ink | `#4A4A4A` | Main text |
| Ink title | `#6B6B6B` | Titles |
| Ink soft | `#A3A3A3` | Secondary text |
| Track | `#F0E6D8` | Field underlines, dividers |
| Error / error background | `#C0583E` / `#FFE3D8` | Error messages |
| On primary | `#FFFFFF` | Text and icons on Peach, Peach deep and Slate |

Dish type tags (background / text):
| Dish type | Background | Text |
|---|---|---|
| Meat | `#FFE3D8` | `#C0583E` |
| Fish | `#DDEBF4` | `#4A7391` |
| Vegetarian | `#E4F1D8` | `#5A7F45` |

### Logo and wordmark
- Logo: a coral cocotte with a green sprout as the lid knob, on a butter rounded square (`android/app/src/main/res/drawable/ic_mijote_logo.xml`).
- The same cocotte is the launcher icon (adaptive: butter background, cocotte foreground in the safe zone, monochrome variant for themed icons), sits in the white corner blocks (onboarding, web admin, presentation site) and is the favicon of the site and the web admin.
- Wordmark "Mijote" in **Grandstander ExtraBold** (SIL OFL, bundled), Ink title; used only for the brand name.

### Typography
- Font: **Inter** (Light 300, Regular 400, Medium 500, SemiBold 600, Bold 700), bundled with the app.
- Screen title: Bold, ~32 sp (40 sp on onboarding, on two lines), Ink title, sentence case.
- Day headers: Bold ~19 sp, date on the right in uppercase SemiBold ~12 sp with letter spacing.
- Meal slot: uppercase ~10 sp, letter spacing ~1 sp, Ink soft. Recipe titles: Bold ~16 sp, max 2 lines with ellipsis.
- Buttons: Bold ~15–16 sp.

### Shapes and elevation
- Sheets (generation screen): 36 dp radius, warm (peach-tinted) shadow.
- **Corner block**: a colored square glued to the top-right corner of a sheet, only its bottom-left corner rounded (36 dp); holds the user button (home) or the app emoji (onboarding).
- Cards: 22 dp radius, white, soft shadow. Buttons and tiles: 18 dp radius.
- Card corner action: 46 dp square glued to the card's bottom-right corner (rounded top-left 16 dp), lock icon when the meal is locked, swap icon otherwise.
- Text fields: borderless with an underline (Peach deep when focused).
- Tags: 8 dp radius pills.

### Web admin
- Same tokens: Butter header (rounded bottom corners 32 dp) with a white corner block, navigation as pills (selected in Peach deep), white cards on Cream, underlined fields, Peach deep buttons. System font stack with Inter first (no bundled font, to keep the binary small).

### Onboarding screens
- Butter header sheet (rounded bottom corners 40 dp) with a white corner block and a big two-line title; the form is a white card overlapping the sheet's bottom edge; secondary actions in Slate below.

### Home screen layout
- Big white header (rounded bottom corners 40 dp) with the logo (76 dp) next to the **"Mijote"** wordmark (54 sp) on the Menus tab (a plain title on the other tabs), the period summary below (e.g. "2 → 8 novembre · 11 repas"), a wide Peach **"Nouvelle semaine"** button and a square Slate search tile; the user button sits in a Butter corner block.
- Upcoming days as a plain list on the Butter background (no colored sheets), each with a header (e.g. "Aujourd'hui", "Demain", weekday) and its planned meals; days are separated by a short centered white pill (56 × 5 dp).
- Meals shown as **full-width horizontal cards**: recipe image on the left, meal slot, recipe title, dish type tag and prep time, and the corner action. (Replaces the former 2-column grid.)
- **Bottom navigation**: stacked tabs anchored to the bottom-right corner of the screen, laid out from the right (Generate, Recipes, Menus) in three peach shades lighter towards the edge; tabs are 56 dp high (plus the system navigation bar inset they extend behind); each tab overlaps its left neighbour with a rounded top-left corner (28 dp). The selected tab widens and shows its label next to the icon; the others show the icon only.

## 9. Data model (decided)

Conceptual model for PostgreSQL. Names are indicative; the exact schema is defined with the first migration. No `household_id` (one instance = one family). Images are never stored in the database.

### recipe
| Field | Type | Notes |
|---|---|---|
| id | UUID | Generated by the client or server (to define with the sync design) |
| title | text | Required |
| source_url | text, unique when set | Link to the original recipe; used for duplicate detection |
| image_path | text, nullable | Thumbnail on disk (JPEG, 800 px max), relative to the image directory. The image is **uploaded** to the server (app or web admin); the server never stores or serves an external image URL and never fetches one. |
| prep_minutes, cook_minutes, total_minutes | int, nullable | From schema.org; total used for quick / long |
| servings | int, nullable | From `recipeYield` when parsable |
| dish_type | enum: `meat`, `fish`, `vegetarian` | Dedicated field (not a tag), used by generation |
| seasons | set of `spring`, `summer`, `autumn`, `winter` | Dedicated field; empty = all year |
| meal_types | set of `lunch`, `dinner` | Meals the recipe suits |
| notes | text, nullable | Family notes ("less salt"…) |
| created_by | → user | |
| created_at, updated_at | timestamp | |
| version | int | Incremented on every change; used for sync and conflict detection |
| archived_at | timestamp, nullable | Soft delete: past menus keep their reference |

### recipe_ingredient
| Field | Type | Notes |
|---|---|---|
| recipe_id | → recipe | |
| position | int | Display order |
| raw_text | text | Line as extracted ("200 g de farine"); kept for display and re-parsing |
| quantity | numeric, nullable | e.g. `200`, `0.5`; null for "une pincée de sel" |
| unit | text, nullable | e.g. `g`, `ml`, `c. à soupe`; null for countable items ("3 œufs") |
| name | text | Ingredient name, e.g. `farine` |
| note | text, nullable | Preparation detail, e.g. "finement haché" |

Quantity, unit and name are parsed **on the device** from `raw_text` at import time, shown in the preview and editable. Units come from the closed list in §9.1.

### recipe_step
| Field | Type | Notes |
|---|---|---|
| recipe_id | → recipe | |
| position | int | Order |
| text | text | |

### 9.1 Units (closed list)

`unit` stores a code from this closed list, or null. The family cannot add units. Codes are English; labels are localized (French for now). Aliases are recognized case-insensitively at import, singular and plural.

| Family | Code | Label (fr) | Recognized aliases | Base conversion |
|---|---|---|---|---|
| Mass | `g` | g | g, gr, gramme(s) | 1 g |
| Mass | `kg` | kg | kg, kilo(s), kilogramme(s) | 1000 g |
| Volume | `ml` | ml | ml, millilitre(s) | 1 ml |
| Volume | `cl` | cl | cl, centilitre(s) | 10 ml |
| Volume | `dl` | dl | dl, décilitre(s) | 100 ml |
| Volume | `l` | l | l, litre(s) | 1000 ml |
| Volume | `tbsp` | c. à soupe | c. à soupe, càs, cs, cuillère(s) à soupe, tbsp | 15 ml |
| Volume | `tsp` | c. à café | c. à café, càc, cc, cuillère(s) à café, tsp | 5 ml |
| Small amount | `pinch` | pincée | pincée(s) | — |
| Small amount | `dash` | trait | trait(s), filet(s) | — |
| Small amount | `handful` | poignée | poignée(s) | — |
| Count | `piece` | (none) | no unit, e.g. "3 œufs" | — |
| Count | `clove` | gousse | gousse(s) | — |
| Count | `slice` | tranche | tranche(s) | — |
| Count | `bunch` | botte | botte(s), bouquet(s) | — |
| Count | `sprig` | brin | brin(s) | — |
| Count | `leaf` | feuille | feuille(s) | — |
| Count | `can` | boîte | boîte(s), conserve(s) | — |
| Count | `pack` | sachet | sachet(s), paquet(s) | — |
| Count | `jar` | pot | pot(s) | — |

Parsing rules:
- Unknown unit: keep the quantity, set `unit` to null; `raw_text` keeps the information; the user can fix it in the preview.
- No quantity ("sel, poivre", "quelques feuilles de basilic"): `quantity` and `unit` are null.
- Ranges ("2 à 3 tomates"): keep the upper value.
- Fractions ("½", "1/2"): stored as decimals (`0.5`).

Aggregation (shopping list): quantities are summed only for the same ingredient name within the same family — mass with mass (in g), volume with volume (in ml), other units only with the same unit. Mass and volume are never mixed (density unknown).

### tag / recipe_tag
- `tag`: id, name (unique, case-insensitive). Free-form, shared by the family.
- `recipe_tag`: recipe_id, tag_id.

### Not stored
- **Favorites**: dropped.
- **Last used date**: derived from menu history (FR-GEN-03).

### user
id, username (unique, used to log in; no email), display_name, password_hash, role (`admin` / `member`), created_at, disabled_at (nullable). There is exactly **one admin** per instance.

### Authentication tables (see §13)
- `api_token`: personal API tokens (user, name, SHA-256 hash, 8-character display prefix, scope `read`/`write`, expiry, last use, revocation).
- `refresh_token`: one row per app refresh token; `family_id` groups the tokens of one device session (rotation chain). Only the SHA-256 hash is stored.
- `admin_session`: web admin sessions (cookie hash, expiry, idle limit).
- `user_totp` / `user_totp_backup`: TOTP secret (AES-GCM sealed) and hashed backup codes.
- `auth_event`: login journal.
- `server_secret`: keys generated on first start (JWT, admin cookies, TOTP sealing).

### invitation
id, code_hash (single-use, never stored in clear), created_by, created_at, expires_at, used_by, used_at, revoked_at. Invitations always create a **member** (an admin cannot invite another admin).

### family_settings (single row)
- default meals per weekday: `lunch_dinner`, `dinner` or `none`;
- default planning horizon (days);
- "quick" threshold in minutes for weekdays, and which days are the weekend;
- recent-use window (days) for FR-GEN-03;
- target dish type split (meat / fish / vegetarian percentages, summing to 100);
- minimum recipe count for generation (FR-GEN-02);
- timezone.

### menu_slot
id, date, meal (`lunch` / `dinner`), recipe_id (nullable = empty slot), locked, updated_by, updated_at, version, deleted_at (nullable, so deletions reach other devices). Unique on (date, meal).
- One-off changes (e.g. away on Tuesday lunch) are done by adding or removing a slot; no dedicated table.
- Moving a meal swaps the recipes of two slots.
- Past slots are kept: they are the history used to avoid recent recipes.

## 10. Menu generation algorithm (decided)

Runs on the server. Input: a period, the family settings, the existing slots.

1. **Slots to fill**: slots of the period that are empty and not locked, in date order. Locked and already filled slots are kept and count for the balance and recent use.
2. **Eligible recipes** (hard filters): not archived; suits the slot's meal (lunch / dinner); not already used in the period.
3. **Score** each eligible recipe (preferences, not filters):
   - **Recent use**: strongly penalized if served within the recent-use window (history + this period).
   - **Prep time**: on weekdays, recipes above the "quick" threshold are penalized; recipes with no time are treated as quick. No penalty on weekends.
   - **Season**: recipes not matching the current season are penalized (no season = all year).
   - **Dish type balance**: favor the type furthest below the admin's target split for the period so far.
4. **Pick** randomly among the best-scored recipes (weighted), so regenerating gives variety.
5. **Shortage**: if every eligible recipe was used recently, pick the one served the longest ago. A slot stays empty only when no recipe suits the meal at all.

**Generation request** (implemented 2026-10-31): the app asks for a **number of meals** from a start day (`POST /api/v1/menu/generate` with `start`, `meals` 1–42, `plan`). The plan decides which meals each day gets: `default` (family default meals per weekday), `lunch_dinner` or `dinner`; slots are taken day after day until the count is reached (60 days at most). Locked slots in the period are kept and count among the meals; other slots of the period are refilled, and unlocked slots the new plan leaves out are removed. Seasons are meteorological (northern hemisphere: winter = Dec–Feb…). Below the family's minimum recipe count, the server answers `409 not_enough_recipes` with `have` / `need`. `GET /api/v1/menu?from&to` returns the planned meals (default: today onwards) and the generation info (recipe count, minimum, horizon, default meals). Weights live in `server/internal/menu/plan.go`.

**Generation screen** (app, "Générer" tab): Butter sheet titled "Nouveau menu"; a card with a +/− meal count stepper on its left (default: meals of the family horizon), a white face summarizing the count and the period, and a Slate band of plan choices (Réglages famille / Midi & soir / Soir seulement, selected one Butter with a check); start day choices below (Aujourd'hui, Demain, Lundi prochain), then "Générer le menu". The "Nouvelle semaine" button of the home screen opens it; after generation the app returns to the menus.

**Swap** (FR-ADJ-01) runs steps 2–5 for one slot, excluding its current recipe.
**Regenerate** re-fills every non-locked slot of the period.
Exact weights are tuned during implementation and kept in code constants.

## 11. Sync and conflicts (decided)

- **Pull**: on app open and pull-to-refresh, the app asks for changes since its last sync (`GET …/changes?since=<cursor>`); the server returns created / updated / deleted items (recipes, tags, menu slots, settings) and a new cursor. Deletions are sent as soft deletes (`archived_at`, `deleted_at`).
- **Offline**: read-only, from the local cache. No offline write queue.
- **Writes** (online only): sent immediately with the version the app knows. The UI updates optimistically and rolls back if the request fails.
- **Generation and swap**: server-side, online only.
- **Conflicts**: **last write wins**. When the server version was newer than the one sent, the write is still applied, and the app shows the other user a notice on their next sync (e.g. "Mardi dîner modifié par Julien").

## 12. Database migrations (decided)

- Run automatically at startup (NFR-17). Driver `github.com/jackc/pgx/v5` (Go has no PostgreSQL driver). Other dependencies: `golang-jwt/jwt/v5` (access tokens), `golang.org/x/crypto` (argon2id), `rsc.io/qr` (2FA QR code).
- Plain, forward-only SQL files `NNNN_description.sql` in `server/internal/migrate/migrations/`, embedded in the binary; a small in-house runner applies them in order, one transaction each, records them in `schema_migrations`, and holds a PostgreSQL advisory lock so two instances never migrate at once.
- Applied files are never edited; fixes go into a new migration.
- No down migrations: a binary older than the schema refuses to start; rollback = restore a backup, so back up before upgrading.

## 13. Security (decided)

Modeled on the author's paper-stacks project.

- **Passwords**: argon2id (19 MiB, 3 passes), 8 characters minimum. The first admin is created by the setup wizard (`/admin/setup`, only while the instance has no account).
- **App tokens**: JWT access token (HS256, 15 min, `Authorization: Bearer`) + random 256-bit refresh token (60 days) used only on `POST /api/v1/auth/refresh`. Each refresh rotates the token within its device session; presenting an already rotated token revokes the whole session and is logged. Every request checks that the device session is still open and the account active, so a revocation takes effect immediately. Endpoints: `POST /api/v1/auth/login`, `/refresh`, `/logout`, `GET /api/v1/me`.
- **Invitations**: the admin creates one in the web admin (`/admin/invitations`), valid 1, 7 (default) or 30 days. The clear code (128-bit, base32) is shown once; only its SHA-256 hash is stored. The invitation link is `mijote://invite?server=<server URL>&code=<code>` (also rendered as a QR code); the app opens it directly (the phone's camera app scans the QR code and hands the link to Mijote; there is no in-app scanner, to avoid a camera/ML dependency) and also accepts the server URL and the code typed by hand ("J'ai une invitation"). An invitation link never replaces an open session. `POST /api/v1/invitations/redeem` (`code`, `username`, `displayName`, `password`, `deviceName`) creates the **member** account, consumes the code in one transaction and signs the device in (answers `201` with the same token pair as login). Unknown, used, revoked and expired codes all answer `404 invalid_invitation`; failures count against the per-address limit. A refused username or password (`422`) leaves the code usable.
- **Accounts** (`/admin/accounts`): list, deactivate/reactivate a member (closes its sessions), set a new password for a member (closes its sessions). The admin account cannot be disabled; its password changes from "Mon compte".
- **Web admin session**: cookie `mijote_admin` (HttpOnly, SameSite=Strict, Secure over HTTPS, path `/admin`); 12 h max and 2 h idle, or 15 days max and 5 days idle with "stay signed in". Every form carries a CSRF token derived from the session. Changing the password closes the account's other sessions.
- **Admin sign-in**: password only (single admin). Optional **TOTP** (RFC 6238, 6 digits, 30 s, ±1 step, each code accepted once) set up from *Mon compte* with a QR code, plus 10 single-use backup codes. Two steps: password → signed 5-min challenge cookie → code → session. Disabling 2FA or regenerating backup codes requires the password and a code. Recovery: `mijote reset-2fa <username>` on the server. 2FA covers the web admin only.
- **Rate limiting** (in memory, app and admin): 5 failures in 15 min on an account (admin: shared key) or 20 from one address block sign-in for 15 min (`429` + `Retry-After`). Same response whether the account exists or not.
- **Reverse proxy**: `X-Forwarded-For` / `X-Forwarded-Proto` are trusted only from `MIJOTE_TRUSTED_PROXIES`, for real client addresses and HTTPS detection.
- **Headers** on every response: `X-Content-Type-Options`, `X-Frame-Options: DENY`, `Referrer-Policy`, `Content-Security-Policy`, and HSTS over HTTPS.
- **Personal API tokens** (`/admin/tokens`, admin only): for scripts and assistants that read or write through `/api/v1` without a device session. Format `mjt_` + 256-bit random (URL-safe base64), sent as `Authorization: Bearer mjt_…`; only the SHA-256 hash is stored, the clear token is shown once. The admin picks a name, a scope (`read`: GET/HEAD only, otherwise `403 read_only_token`; `write`: all methods) and a lifetime of 7, 30, 90 (default) or 365 days (no token without expiry). A token acts with the rights of its creator's account, is checked on every request (revocation and account deactivation are immediate) and has no device session (`/auth/logout` answers `400`). Creation and revocation are logged; last use and address are shown in the list; tokens expired for 30 days are purged.
- **Tracking in the web admin**: device and admin sessions (revocable one by one, "log out my other sessions"), and the login journal (sign-ins, failures, lockouts, logouts, token reuse, 2FA events) with a "failures only" filter.
- **Retention**: journal 180 days, expired tokens and sessions 30 days (daily purge).
