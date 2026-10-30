# Mijote — Requirements

Status: draft v0.1 (specification only, no code yet).

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
- Shopping list, portion management, leftovers — see Open questions (OQ-4).
- iOS support — depends on OQ-3.

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
| FR-PLAN-01 | For each weekday, choose the meals to plan: lunch + dinner, or dinner only. | Must |
| FR-PLAN-02 | Admin defines the family default meals per weekday. | Must |
| FR-PLAN-03 | User chooses a planning horizon of X days (user-defined). | Must |
| FR-PLAN-04 | User can override the meals for a specific date in the period. | Could |

### 4.4 Menu generation (GEN)
| ID | Requirement | Priority |
|---|---|---|
| FR-GEN-01 | Generation runs server-side so all members see the same result. | Must |
| FR-GEN-02 | Generation is available once the recipe base reaches a minimum size; the app explains what is missing otherwise. Threshold to define. | Must |
| FR-GEN-03 | Avoid recently used recipes. | Must |
| FR-GEN-04 | Balance dish types (meat / fish / vegetarian) over the period. | Should |
| FR-GEN-05 | Prefer quick recipes on weekdays and allow longer ones on weekends. | Should |
| FR-GEN-06 | Take season into account. | Could |
| FR-GEN-07 | ~~Favor favorite recipes.~~ Dropped: no favorites. | — |
| FR-GEN-08 | Rules and weights are candidates; the exact algorithm is to be specified in an ADR. | — |

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
| FR-SHR-03 | Concurrent edits are detected (e.g. version / updated_at) and the user is informed; resolution strategy to define. | Should |

### 4.7 Onboarding and invitations (ONB)
| ID | Requirement | Priority |
|---|---|---|
| FR-ONB-01 | On first start, a setup wizard (web) creates the admin account. | Must |
| FR-ONB-02 | Admin generates invitations as a link or QR code containing the server URL and a single-use code. | Must |
| FR-ONB-03 | Member scans/opens the invitation and sets a password. | Must |
| FR-ONB-04 | Invitations expire and can be revoked. | Should |
| FR-ONB-05 | On first launch, the app asks for the server URL (typed or scanned); one store app serves any instance. | Must |
| FR-ONB-06 | Login by username/email + password; no OAuth. | Must |

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
| NFR-01 | Resources | Backend uses a lightweight runtime with a small memory footprint (target to set once OQ-1 is answered). |
| NFR-02 | Resources | PostgreSQL tuned for low memory: small `shared_buffers`, low `max_connections` and `work_mem`; config shipped with the compose file. |
| NFR-03 | Resources | Simple REST API; no WebSockets or long-lived connections. |
| NFR-04 | Resources | Images stored as original URL or compressed thumbnail on disk; never in the database. |
| NFR-05 | Offline | Recipes and current menus readable offline from local cache. |
| NFR-06 | Offline | Edits made offline are queued and synced when the server is reachable (Should; conflict policy to define). |
| NFR-07 | Perceived performance | Screens render from local cache immediately; network refresh happens in background. |
| NFR-08 | Perceived performance | Gestures (swap, drag and drop, lock) respond without waiting for the network. |
| NFR-09 | Security | Passwords hashed with a modern algorithm (e.g. argon2id / bcrypt). |
| NFR-10 | Security | Token-based API authentication; tokens revocable. |
| NFR-11 | Security | HTTPS expected in production (via reverse proxy); documented. |
| NFR-12 | Security | Invitation codes single-use, random, time-limited. |
| NFR-13 | API versioning | API is versioned (e.g. `/api/v1`); server exposes its supported versions; app degrades gracefully against older instances. |
| NFR-14 | Deployment | Ready-to-use `docker-compose.yml` (backend + PostgreSQL). |
| NFR-15 | Deployment | Container images for ARM and x86. |
| NFR-16 | Deployment | Configuration through environment variables only. |
| NFR-17 | Deployment | Database migrations run automatically at startup. |
| NFR-18 | Deployment | Backend is stateless apart from PostgreSQL and the image directory. |
| NFR-19 | Backups | Backup and restore documented (`pg_dump` + image directory). |

## 6. Architecture overview

| Component | Responsibilities |
|---|---|
| **Mobile app** (stack: OQ-3) | Server URL setup, login, share-sheet target, page fetch + schema.org/Recipe extraction, local cache and offline reading, optimistic UI for adjustments, sync on open / pull-to-refresh. |
| **Backend API** (language: OQ-2) | Versioned REST API, auth and roles, recipe storage, thumbnail storage on disk, **menu generation** and swap suggestions, invitations, migrations at startup. |
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

## 7. Open questions

| ID | Question | Options considered |
|---|---|---|
| OQ-1 | Target machine specs (RAM, CPU, ARM or x86)? | e.g. Raspberry Pi–class ARM board; small x86 VPS / mini PC. Drives memory targets (NFR-01). |
| OQ-2 | Backend language? | Go (favored, not confirmed); other lightweight runtimes (e.g. Rust). |
| OQ-3 | Mobile stack? | Native Android (Kotlin / Jetpack Compose); cross-platform (Flutter / React Native) to support iPhone families. |
| OQ-4 | Scope of later features? | Shopping list generated from menus; portion management; leftovers reused the next day. |

Smaller points to clarify (not blocking): member permission to delete recipes, minimum recipe count for generation (FR-GEN-02), conflict resolution policy (FR-SHR-03, NFR-06).

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

### Other entities (to detail later)
- `user` (role admin / member), `invitation` (single-use code, expiry), `family_settings` (default meals per weekday, preferences), `menu_slot` (date, meal `lunch`/`dinner`, recipe, locked).
