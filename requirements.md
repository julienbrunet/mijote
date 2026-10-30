# Mijote — Requirements

Status: draft v0.1. Backend skeleton in `backend/` (migrations, initial schema).

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
| Delete recipes, manage tags | ✅ | ❌ (to confirm) |
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
| FR-REC-05 | The app sends the already-structured recipe to the backend; the backend never fetches recipe pages. | Must |
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
| FR-ADM-04 | Manage recipes and tags. | Should |
| FR-ADM-05 | Show instance info (version, API version, DB status). | Could |

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
| NFR-10 | Security | Token-based API authentication; tokens revocable. |
| NFR-11 | Security | HTTPS expected in production (via reverse proxy); documented. |
| NFR-12 | Security | Invitation codes single-use, random, time-limited. |
| NFR-13 | API versioning | API is versioned (e.g. `/api/v1`); server exposes its supported versions; app degrades gracefully against older instances. |
| NFR-14 | Deployment | Ready-to-use `docker-compose.yml` (backend + PostgreSQL). |
| NFR-15 | Deployment | Container images for `linux/arm64`, `linux/arm/v7` (32-bit Raspberry Pi OS) and `linux/amd64`. |
| NFR-16 | Deployment | Configuration through environment variables only. |
| NFR-17 | Deployment | Database migrations run automatically at startup. |
| NFR-18 | Deployment | Backend is stateless apart from PostgreSQL and the image directory. |
| NFR-19 | Backups | Backup and restore documented (`pg_dump` + image directory). |

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

Validated on the theme prototype (`prototypes/theme-playground/`). Theme name: **Calm Kit** — light, soft and calm.

### Theme
- **Light mode only** for now. A dark variant is out of scope until requested.
- Edge-to-edge display: content scrolls behind the system bars and the bottom navigation.

### Color palette
| Token | Hex | Usage |
|---|---|---|
| Sage | `#6B7F6E` | Primary: main actions, selected navigation item, active toggles, lock icon |
| Moss | `#A7B8A7` | Secondary accents |
| Mist | `#D9E1D9` | Light containers, vegetarian tag background |
| Ivory | `#F4EFE8` | Surface variant |
| Sand | `#C9BDAF` | Outlines |
| Background | `#EFEAE3` | Screen background |
| Card | `#F7F4EF` | Cards, bottom navigation, round buttons |
| Ink | `#2E3530` | Main text |
| Ink soft | `#7D847F` | Secondary text, unselected navigation items |
| Track | `#DDD8D1` | Inactive tracks, dividers |
| On primary | `#FFFFFF` | Text and icons on Sage |

Dish type tags (background / text):
| Dish type | Background | Text |
|---|---|---|
| Meat | `#EBDDD6` | `#7E5A4E` |
| Fish | `#DDE4E8` | `#4F6573` |
| Vegetarian | `#D9E1D9` | `#4F6552` |

Status colors (success, error, warning, info) are not defined yet; they must stay within the same soft, desaturated tone.

### Typography
- Font: **Inter** (Light 300, Regular 400, Medium 500, SemiBold 600), bundled with the app.
- Screen title: uppercase, Light, ~26 sp, wide letter spacing (~4 sp).
- Section headers (days): Medium ~17 sp, followed by the date in secondary text.
- Card labels (meal slot): uppercase ~11 sp, letter spacing ~1 sp, secondary text.
- Recipe titles: Medium ~15 sp, max 2 lines with ellipsis.

### Shapes and elevation
- Cards: 18 dp corner radius, soft shadow (~2 dp).
- Bottom navigation: floating card, 24 dp radius, ~4 dp shadow, 16 dp side margins.
- Tags: 8 dp radius pills. Round icon buttons and badges: circles.

### Home screen layout
- Header: title **"ÇA MIJOTE"** with the period summary below (e.g. "2 → 8 novembre · 11 repas"); round **user button** on the top right.
- Upcoming days listed in order, each with a header (e.g. "Aujourd'hui", "Demain", weekday) and its planned meals.
- Meals shown as **cards in a 2-column grid**: recipe image on top (aspect ~1.4:1), meal slot, recipe title, dish type tag and prep time. A locked meal (lock icon, Sage) is shown as a round badge on the image's top-right corner.
- **Bottom navigation** with 3 items (icon + label), selected item in Sage with a small dot below, unselected in Ink soft; no background behind the floating bar.

## 9. Data model (decided)

Conceptual model for PostgreSQL. Names are indicative; the exact schema is defined with the first migration. No `household_id` (one instance = one family). Images are never stored in the database.

### recipe
| Field | Type | Notes |
|---|---|---|
| id | UUID | Generated by the client or server (to define with the sync design) |
| title | text | Required |
| source_url | text, unique when set | Link to the original recipe; used for duplicate detection |
| image_url | text, nullable | Original image URL |
| image_path | text, nullable | Compressed thumbnail on disk, relative to the image directory |
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

### api_token
id, user_id, token_hash (never stored in clear), device_name, created_at, last_used_at, revoked_at. Lets the admin log out a device.

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

**Swap** (FR-ADJ-01) runs steps 2–5 for one slot, excluding its current recipe.
**Regenerate** re-fills every non-locked slot of the period.
Exact weights are tuned during implementation and kept in code constants.

## 11. Sync and conflicts (decided)

- **Pull**: on app open and pull-to-refresh, the app asks for changes since its last sync (`GET …/changes?since=<cursor>`); the server returns created / updated / deleted items (recipes, tags, menu slots, settings) and a new cursor. Deletions are sent as soft deletes (`archived_at`, `deleted_at`).
- **Offline**: read-only, from the local cache. No offline write queue.
- **Writes** (online only): sent immediately with the version the app knows. The UI updates optimistically and rolls back if the request fails.
- **Generation and swap**: server-side, online only.
- **Conflicts**: **last write wins**. When the server version was newer than the one sent, the write is still applied, and the app shows the other user a notice on their next sync (e.g. "Mardi dîner modifié par Julien").
