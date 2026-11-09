# Mijote — Requirements

Status: draft v0.1. Server skeleton in `server/` (migrations, initial schema); menu generation implemented (§10); app recipes tab, manual meals and in-app administration implemented (§10); web admin meal list implemented (§4.8); web admin navigation regrouped, home page with editable meal cards, recipe statistics and system page implemented (§4.8).

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

| Capability | Admin | Member | Read-only |
|---|---|---|---|
| Manage accounts and invitations (web admin, and app settings) | ✅ | ❌ | ❌ |
| Manage family settings (default meals, preferences) | ✅ | ❌ | ❌ |
| Add / edit recipes | ✅ | ✅ | ❌ |
| Delete recipes, manage tags | ✅ | ❌ (to confirm; the API currently reserves archiving to the admin, tags are managed in the web admin only) | ❌ |
| View recipes and tags | ✅ | ✅ | ✅ |
| Generate and adjust menus, plan a recipe or a manual meal on a day | ✅ | ✅ | ❌ |
| View shared menus | ✅ | ✅ | ✅ |
| Access web admin | ✅ | ❌ | ❌ |

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
| FR-REC-15 | Each user marks recipes as **personal favorites** (own list, not shared with the family; read-only users too) and finds them through the recipe filters ("Mes favoris"). Favorites do not influence generation. (Reinstated 2026-11-03, replaces "dropped".) | Should |
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
| FR-PLAN-05 | Each user sets in their preferences the number of people to cook for (1–20, default 1). Recipes are stored for **one person**; quantities are multiplied by this number. It does not change which recipes generation picks. | Must |
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
| FR-GEN-07 | ~~Favor favorite recipes.~~ Dropped: favorites are personal (FR-REC-15) and do not influence the shared generation. | — |
| FR-GEN-08 | Generation follows the algorithm in §10. | Must |

### 4.5 Adjustments after generation (ADJ)
| ID | Requirement | Priority |
|---|---|---|
| FR-ADJ-01 | Swap a meal's recipe with one gesture (server proposes another suitable recipe). | Must |
| FR-ADJ-02 | Lock meals; regeneration keeps locked meals. | Must |
| FR-ADJ-03 | Move meals by drag and drop between slots. | Must |
| FR-ADJ-04 | Manually pick a specific recipe for a slot. | Should |
| FR-ADJ-05 | Adjustments feel instant (optimistic UI), then sync to the server. | Must |
| FR-ADJ-06 | Plan a **manual meal**: a free text ("Commander une pizza", "Repas chez des amis") on a chosen day and meal, without creating a recipe; shown in the menu like the other meals, renamable and removable. | Must |
| FR-ADJ-07 | Plan a recipe on a chosen day and meal directly from the recipe list (search results included) and from the recipe screen. | Must |

### 4.6 Sharing across family members (SHR)
| ID | Requirement | Priority |
|---|---|---|
| FR-SHR-01 | One shared recipe base and one shared menu per instance. | Must |
| FR-SHR-02 | Clients refresh on app open, when the app comes back to the foreground, and on pull-to-refresh (menus and recipes tabs); no real-time and no periodic polling (decided 2026-11-02). | Must |
| FR-SHR-03 | Concurrent edits: last write wins and the overwritten user is informed (see §11). | Must |
| FR-SHR-04 | Read-only members can express a wish for an active recipe; every active writer/admin receives a personal in-app notification, readable and individually deletable, with unread counter. Wishes never automatically change menus. | Must |
| FR-SHR-05 | Shared instance setting, editable by every writer/admin in app preferences: maximum wishes per read-only account per calendar week, default 5 (1–100). Monday-based week in the family timezone; transactional quota checking, retained after notification deletion. | Must |

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
| FR-ADM-05 | **System page** ("Système", `/admin/system`, implemented 2026-11-03): points of attention (admin without 2FA, fewer active recipes than the generation minimum, login failures in the last 24 h, disk or host memory ≥ 90 %, recipe images missing from the image directory, no upcoming meal or meals ending within 2 days, orphan image files, recipes without image); counts (recipes, archived, tags, accounts by role, upcoming / past / manual meals, pending invitations, connected devices, admin sessions, active API tokens); server version and the date it first ran, instance installation date (first applied migration), start date and uptime, API versions, schema version, Go version; server process memory (RSS, Go heap), CPU (last minute and average since start, in % of one core), goroutines, connection pool; host CPUs, load average, memory, free space of the image directory's disk; database size, PostgreSQL version, largest tables; image directory size and file count. Figures come from the Go runtime, `/proc` and `statfs` (package `internal/sysinfo`, standard library only; a 15 s sampler keeps one minute of CPU readings in memory). Server versions are recorded at startup in `server_version` (migration 0009) for the update history. | Could |
| FR-ADM-06 | Create and revoke personal API tokens (read-only or read/write, with expiry) for scripts and assistants. | Should |
| FR-ADM-07 | The admin finds part of the administration in the app's preferences: invitations (create, share the link or QR code, revoke) and accounts (role, deactivate / reactivate, new password). Family settings, tags, API tokens, sessions and the journal stay in the web admin. | Must |
| FR-ADM-08 | **Meal list**, the **home page** of the admin (`/admin/`, moved there 2026-11-03; `/admin/meals` redirects): every planned meal of the instance (meals are shared, not per user), grouped by day ("Aujourd'hui", "Demain", weekday, and the date), as a **compact plan using the whole screen width** (2026-11-03): one row per day (day title and date), lunch and dinner side by side, each meal on one line (44 px thumbnail or dish emoji, recipe with a link to its admin page or the manual meal, dish type tag, time, badges "repas libre", "recette archivée", "fixé", **who planned it**, when in a tooltip); on a phone the columns stack. Tabs *À venir* (default: today and later, ascending) / *Passés* (most recent first), filter by account; 300 rows per page at most. On upcoming meals, the pencil opens `/admin/meals/{id}` (choose a recipe, suitable ones for the meal first; another random recipe; manual meal) and a trash button removes the meal after a confirmation; a chosen recipe or manual meal is locked, as in the app. "Planned by" is `menu_slot.updated_by`: the last account that planned or changed the meal (generation, swap, chosen recipe or manual meal, including the admin's changes here); there is no separate creator column. | Should |
| FR-ADM-09 | **Navigation** (2026-11-03): five menus in the header, sub-pages as tabs under the page title: *Accueil* (meal list), *Recettes* (recipes, tags, **statistics**), *Utilisateurs* (accounts, invitations, sessions, login journal), *Système* (FR-ADM-05), *Profil admin* (password, 2FA, API tokens). The footer shows the server version (signed-in pages only) and links to the GitHub repository. | Should |
| FR-ADM-10 | **Recipe statistics** (`/admin/recipes/stats`): over a period (30 days, 3 months by default, 12 months, since the beginning; past meals until yesterday), the dish type split of the served meals against the family target, the number of manual meals and of active recipes never served, and per recipe the times served in the period and in total, the last and next planned dates (most served first; archived recipes only when served in the period). | Could |

**Web admin invitations page** (2026-11-02): a three-step header (*Créer* → *Partager* → *Rejoindre*) shows where the admin is. Step 1 is the creation form (role of the future account, lifetime). After creation, the page shows a single share card: the invitation summary (role, single use, expiry), the link in a read-only field with a **"Copier le lien"** button right next to it, and **one** QR code of the same link; it reminds that the link is shown only once, then offers "Créer une autre invitation". The list below (tabs *En attente* / *Toutes*) shows state badges and explains that a lost link means revoking and creating a new invitation. The copy button is the admin's only script, served from `/admin/admin.js` (the CSP forbids inline scripts; Clipboard API over HTTPS, text selection fallback otherwise); without JavaScript the button stays hidden and the link can be selected by hand.

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
| NFR-21 | API documentation | The API is documented for tools and LLM assistants: a guide (`server/API.md`, including adding a recipe from a URL with client-side extraction) and an OpenAPI 3.1 reference (`server/openapi.yaml`), kept in sync with the routes by a test. |
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
| **Web admin** | Server-rendered htmx pages in the same binary: setup wizard, accounts, invitations, meal list, family settings, recipes. |
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
- Same codes as the app (updated 2026-11-03): a compact white header on one row (rounded bottom corners 32 px, warm shadow) with the **logo tile and the "Mijote" wordmark** (an SVG of the Grandstander glyphs, so no font is bundled), a small "Admin" label, then the navigation pills (selected in Peach deep) and "Déconnexion"; no corner block (the profile is a menu entry); sub-pages as tabs; white cards on Cream, underlined fields, Peach deep buttons; dish type tags as in the app. Footer: small logo, server version, link to the GitHub repository. The logo is the favicon. Stylesheet, script, logo and wordmark are embedded files served from `/admin/` with an ETag (`static/`), not inline. System font stack with Inter first (no bundled font, to keep the binary small).

### Onboarding screens
- Butter header sheet (rounded bottom corners 40 dp) with a white corner block and a big two-line title; the form is a white card overlapping the sheet's bottom edge; secondary actions in Slate below.

### Home screen layout
- Big white header (rounded bottom corners 40 dp), the same on every tab (updated 2026-11-02): the logo tile (48 dp) next to the **"Mijote"** wordmark (44 sp) on the Menus tab, a plain title on the other tabs (Recipes adds its search field and "Nouvelle recette" below); the user button sits in an 84 dp Butter corner block that **starts below the status bar** (only its bottom-left corner rounded, 32 dp), so nothing coloured runs under the phone's status icons, the period summary below (e.g. "2 → 8 novembre · 11 repas"), a wide Peach **"Nouvelle semaine"** button and a square Slate search tile; the user button sits in a Butter corner block.
- Upcoming days as a plain list on the Butter background (no colored sheets), each with a header (e.g. "Aujourd'hui", "Demain", weekday) and its planned meals; days are separated by a short centered white pill (56 × 5 dp).
- Meals shown as **full-width horizontal cards**: recipe image on the left, meal slot, recipe title, dish type tag and prep time, and the corner action. (Replaces the former 2-column grid.)
- **Bottom navigation** (updated 2026-11-02): a floating Peach deep pill centred at the bottom of the screen holding the tab icons only, no text (Menus, Recipes, "Idées de repas" with a fork-and-knife and sparkle icon; read-only users have no "Idées de repas" tab); the selected icon sits on a translucent white circle. The pill and the Recipes round buttons ("+" on the left, filters on the right) have a dark shadow so they stand out from the list scrolling under them. Tab names stay as accessibility descriptions.

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
| servings | int, nullable | From `recipeYield` when parsable. Recipes are meant to be stored for one person (quantities multiplied by the user's number of people, FR-PLAN-05). |
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

### recipe_favorite (2026-11-03)
user_id → user, recipe_id → recipe, created_at; primary key (user_id, recipe_id). Personal favorites (FR-REC-15); deleted with the user or the recipe.

### Not stored
- **Last used date**: derived from menu history (FR-GEN-03).

### user
id, username (unique, used to log in; no email), display_name, people (preference: number of people to cook for, 1–20, default 1), password_hash, role (`admin` / `member` / `readonly`), created_at, disabled_at (nullable). There is exactly **one admin** per instance.

### Authentication tables (see §13)
- `api_token`: personal API tokens (user, name, SHA-256 hash, 8-character display prefix, scope `read`/`write`, expiry, last use, revocation).
- `refresh_token`: one row per app refresh token; `family_id` groups the tokens of one device session (rotation chain). Only the SHA-256 hash is stored.
- `admin_session`: web admin sessions (cookie hash, expiry, idle limit).
- `user_totp` / `user_totp_backup`: TOTP secret (AES-GCM sealed) and hashed backup codes.
- `auth_event`: login journal.
- `server_secret`: keys generated on first start (JWT, admin cookies, TOTP sealing).

### invitation
id, code_hash (single-use, never stored in clear), created_by, role (`member` / `readonly`, default `member`), created_at, expires_at, used_by, used_at, revoked_at. Invitations create a **member** or a **read-only** account (an admin cannot invite another admin).

### family_settings (single row)
- default meals per weekday: `lunch_dinner`, `dinner` or `none`;
- default planning horizon (days);
- "quick" threshold in minutes for weekdays, and which days are the weekend;
- recent-use window (days) for FR-GEN-03;
- target dish type split (meat / fish / vegetarian percentages, summing to 100);
- minimum recipe count for generation (FR-GEN-02);
- timezone.

### menu_slot
id, date, meal (`lunch` / `dinner`), recipe_id (nullable), title (nullable: free text of a **manual meal**, never set together with recipe_id; both null = empty slot), people (1–20, nullable: covers of the meal, shared by the family; set to the planner's preference when planned, null for older meals), locked, updated_by, updated_at, version, deleted_at (nullable, so deletions reach other devices). Unique on (date, meal).
- A manual meal is not a recipe: it never counts for recent use or the dish type balance.
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

**Legacy immediate generation request** (implemented 2026-10-31; retained for older clients and scripts): the app asks for a **number of meals** from a start day (`POST /api/v1/menu/generate` with `start`, `meals` 1–42, `plan`). The plan decides which meals each day gets: `default` (family default meals per weekday), `lunch_dinner`, `lunch` or `dinner`; slots are taken day after day until the count is reached (60 days at most). Locked slots in the period are kept and count among the meals; other slots of the period are refilled, and unlocked slots the new plan leaves out are removed. Seasons are meteorological (northern hemisphere: winter = Dec–Feb…). Below the family's minimum recipe count, the server answers `409 not_enough_recipes` with `have` / `need`. `GET /api/v1/menu?from&to` returns the planned meals (default: today onwards) and the generation info (recipe count, minimum, horizon, default meals). Weights live in `server/internal/menu/plan.go`.

**Home loading** (app): show a loading indicator until the first menu response; on connection failure offer a retry, and show the empty-menu message only after a successful empty response. Keep an already loaded menu visible if refresh fails.

**Home menu layout** (app, 2026-11-03): today's meals keep the full recipe cards. All other meals appear in one compact white card with yellow row separators and no day headings. Each row shows a day/month tile on the left, the weekday (or "Demain") and lunch/dinner above the recipe title, and the individual pencil action on the right for members and admins (hidden for read-only accounts). Titles wrap to three lines before truncation; tapping a recipe still opens it and tapping a manual meal opens its editing actions. The generation review keeps its full cards. Today is taken from the server's menu date, including cached menus.

**Home reordering** (app, 2026-11-04): members with write access and the administrator can reorder saved meals directly on Home. Compact rows stack a small six-dot handle above the pencil in its existing action column; today's cards show the handle above the pencil. Dragging starts with the same platform long-press delay as the generation review (`detectDragGesturesAfterLongPress`), supports edge auto-scroll, and highlights the target. Tapping a handle opens a destination list; accessibility actions move up/down. Occupied destinations insert the meal and shift intermediate meal contents across dates and lunch/dinner, preserving recipe/manual title, lock and covers together while slot dates remain fixed. Empty lunch/dinner destinations (including empty days within the displayed period) appear only during dragging and are also available in the destination list: dropping there moves the source directly without changing other meals. Release saves the affected days atomically through the existing `/menu/confirm` endpoint using the saved slot versions as a conflict baseline. A concurrent change rejects the whole move. A successful move offers Undo for eight seconds; undo uses the post-save baseline and cannot overwrite concurrent changes. Read-only users have no move controls.

**Unsaved proposals** (app and server, 2026-11-03): Android calls `POST /api/v1/menu/preview` with the generation request. The server runs the existing planner in a rolled-back transaction and returns the proposed slots plus the original IDs/versions of the saved period (`base`). It changes no saved meals, including soft deletions, versions or audit fields. The draft lives in the app's ViewModel and is discarded on leaving the review or process death. All review edits (swap, add, remove, rename, covers, drag reorder) affect this local draft only. Random recipe choices use read-only `POST /api/v1/menu/preview/recipe` with the draft as planning context. `POST /api/v1/menu/confirm` is the only draft operation that writes the shared menu: after validating every slot and matching the original period's baseline, it replaces the exact period atomically. Invalid input or a concurrent menu change leaves stored meals intact; conflicts return `409 menu_changed` and require a new proposal. Write roles only. No new storage or migration is needed. The legacy `/menu/generate` endpoint keeps its immediate-save behavior for older clients; the Android flow never uses it.

**Generation flow** (app, decided 2026-10-31), two steps:
1. **"Idées de repas" tab** (renamed from "Générer" / "Nouveau menu", form redesigned 2026-11-03): same white header as the home screen on the Butter background. One white form card groups the settings with yellow separators: **À partir de quand ?** (horizontal date tiles for the next 14 days, tomorrow by default), **Durée** (only a − / + day stepper, 1–21, default: the family horizon; no preset day shortcuts), **Quels repas ?** (Midi / Soir / Les deux; the family default meals are not used by the app), and **À table** (people stepper, 1–20, saved in the user's preferences, `PUT /api/v1/me/preferences`). A second white card scrolls with the form and shows the period, meal and people counts, how many planned meals will be replaced or kept, errors, and **"Trouver mes idées de repas"**. No fixed summary panel: the scroll viewport reserves space for the floating navigation so that every field and the button remain reachable without overlap. The button is disabled during generation or when recipe availability is unknown or below the family's minimum; a shortage warning appears above the form. The app sends `meals = days × meals per day` with plan `lunch`, `dinner` or `lunch_dinner`. The existing two-step generation/review flow is unchanged.
2. **Review screen** ("Proposition de menu", every day of the period, also empty ones; updated 2026-11-03): the proposal is an unsaved draft, marked "Brouillon · pas encore enregistré". This screen lets the user adjust it before saving. Tap a meal to open its recipe; the pencil opens its actions, and missing meals show "Ajouter le midi / le soir". Each recipe meal retains its "N pers." control (1–20). The shared floating navigation stays visible, with a separate "Valider ce menu" button above it on a transparent background; the list scrolls underneath and includes enough bottom padding to bring every card above the floating controls. Only "Valider ce menu" saves the draft to the server and closes the review on success. Back or selecting a navigation tab discards the draft and leaves the previously saved menu intact. Validation errors keep the draft open for correction. Drag handles are always visible beside every meal, with no reordering mode or extra header controls: hold a handle, drag to another occupied slot (automatic scrolling near the edges), then release to insert the meal there and shift intermediate meals. Tapping a handle offers a destination list; accessibility actions also move up/down. Reordering remains local until confirmation: draft dates and slot IDs stay fixed while recipes, manual titles, locks and people move together. The whole edited proposal is saved atomically with `POST /api/v1/menu/confirm`. Read-only accounts cannot reorder. Validation is disabled during a move. A rejected move leaves the stored menu unchanged; errors appear on the review screen. No additional generation step is introduced.

**Meal adjustment endpoints** (implemented 2026-10-31, extended 2026-11-02): `POST /api/v1/menu/slots/{id}/swap` (another recipe; on a locked meal or a manual meal it releases the lock and plans a recipe), `POST /api/v1/menu/slots` `{date, meal, recipeId?, title?, replace?}` (without `recipeId`/`title` the server picks a recipe; with `recipeId` that recipe, which must be active; with `title` a manual meal of 1–100 characters; `409 slot_exists` if the meal is already planned unless `replace` is true), `PUT /api/v1/menu/slots/{id}` `{recipeId}` or `{title}` (turns a meal into that recipe or manual meal), `DELETE /api/v1/menu/slots/{id}` (soft delete, also allowed on locked meals). The recipes planned within 6 days around the slot count as "the period" when picking.

**Chosen meals** (decided 2026-11-02): a recipe chosen by the user (from the recipe screens) and a manual meal are saved **locked**, so generating the period again keeps them; manual meals are kept by generation in any case. "Changer de recette" on such a meal releases it.

**Recipes tab** (app, 2026-11-02, filters 2026-11-03): search by title or tag (server-side, `GET /api/v1/recipes?q=`), archived recipes left out; a round funnel button at the bottom right of the navigation pill (showing the number of active filters) opens a sheet with "Mes favoris" (the user's own favorites), dish type, meal, duration (quick ≤ 30 min, recipes without time count as quick; long), season (recipes without season always match) and the 20 most used tags. Filters apply on the device to the search results: choices within a group add up, groups narrow each other; active filters show as pills under the search, removed with one tap. Each card opens the recipe (a small heart marks the user's favorites) and, for members, has a calendar corner action to plan it. The recipe screen has a heart button (top right of the picture) to add the recipe to the user's favorites or remove it, for every role (`PUT`/`DELETE /api/v1/recipes/{id}/favorite`; recipes carry a personal `favorite` flag, `GET /api/v1/recipes?favorite=1` lists them). A round "+" (members) at the bottom left of the navigation pill opens the editor (both round buttons: 60 dp like the pill, white with a Peach deep ring, 24 dp icon like the tabs, dark shadow; updated 2026-11-03): title, dish type, meals, prep/cook times, seasons, tags (comma separated), ingredients and steps one per line (ingredient lines parsed on the device, FR-REC-21, with a preview), notes; quantities are for one person. The recipe screen offers "Ajouter au menu" and "Modifier" to members and "Archiver" to the admin (archiving stays admin-only, see §3; restore from the web admin). The image is not editable from the app yet. "Ajouter au menu" opens a dialog choosing the day (next 21 days) and the meal (midi / soir), showing the meal it would replace.

**Manual meals** (app, 2026-11-02; no "add" button on the home screen, decided 2026-11-02): a manual meal is planned by editing a meal — its pencil offers *Remplacer par un repas libre* (dialog: free text only, no suggestions) — and, on the review screen, "Ajouter le midi / le soir" asks for a random recipe or a manual meal. Manual meals use a recipe notebook illustration in app cards and the web admin. The pencil of a manual meal offers *Modifier l'intitulé / Remplacer par une recette au hasard / Retirer ce repas*; the pencil of a recipe also offers *Remplacer par un repas libre*.

**Administration in the app** (2026-11-02, FR-ADM-07): an "Administration" card in the preferences, admin only, opens *Invitations* (role member / read-only, lifetime 1, 7 or 30 days; the created invitation shows its QR code and link, shared through the Android share sheet or copied) and *Comptes*. API: `GET/POST /api/v1/admin/invitations` (the creation answers the clear code once, the link, the app link and the QR code as base64 PNG), `POST /api/v1/admin/invitations/{id}/revoke`, `GET /api/v1/admin/users`, `PUT /api/v1/admin/users/{id}` `{role?, disabled?}`, `PUT /api/v1/admin/users/{id}/password`. Only the admin signed in on a device can use them (personal API tokens get `403`); actions are logged in the login journal like in the web admin.

**Home screen**: days are listed without separators (day title and date, then the meal cards, wider gap between days). Each card shows the recipe thumbnail (fetched from `GET /api/v1/recipes/{id}/image` with the access token, memory cache in the app; an emoji stands in for recipes without picture); tapping it opens the **recipe screen** (picture, times, ingredients multiplied by the number of people, steps, notes; a − / + stepper next to "Ingrédients" adjusts the quantities live — opened from a planned meal by a member, it starts from the meal's number of people and **saves** it for that meal (shared by the family, "N pers." on the card); opened from the Recipes tab (or by a read-only user), it starts from the user's preference and nothing is saved — 2026-11-03); the pencil on the card opens the same meal actions as the review screen.

**Android 8 date compatibility** (2026-11-04): server timestamps are RFC 3339 and may contain offsets (notably the family's next Monday for wish quotas). Parse them with `OffsetDateTime` and convert to `Instant`: Android 8/8.1's `Instant.parse` only accepts `Z`, unlike recent Android/JDK releases. Malformed timestamps become a handled `IOException`/`invalid_response` error rather than an uncaught exception on the recipe screen or inbox. Keep minimum Android API 26 and add no compatibility dependency.

**Recipe wishes** (2026-11-03): a read-only member sees "J’ai envie de cette recette" on an active recipe, with remaining weekly quota. A successful request shows "Envie envoyée". The default quota is 5 requests per reader per Monday–Sunday week in the family timezone; the shared setting is editable by every writer/admin from app preferences, range 1–100. A pending duplicate is idempotent and costs no extra quota; once all active recipients clear it, the recipe can be requested again within the quota. A wish creates one notification per active writer/admin, without planning a meal. Each recipient owns its read/delete state. The menu tab header has a bell immediately left of the profile button, shown only while the inbox contains notifications (read or unread). Its badge counts unread notifications only; there is no bell in the bottom navigation. The inbox is also linked from preferences. The inbox can open the recipe, mark read, clear and paginate. It refreshes on app open/foreground/menu refresh and explicit inbox refresh, with no push or background polling. Archived recipes cannot be newly requested; existing notifications keep the requester/title snapshot and can still be read/cleared. New migration `0011_recipe_wishes.sql` adds the instance setting, durable request history (quota survives notification deletion), and per-recipient notifications. No new service or dependency.

**Preferences screen** (app, opened from the user button of the home header): account (name, username, role); administration (admin only, see above); number of people with a +/− stepper on the right (writers/admins only; hidden for read-only accounts); server address with "Changer de serveur" (signs out, then asks for the new address); app version with "Rechercher des mises à jour" (manual check, also in debug builds; a newer release opens the update dialog); sign out. The "Nouvelle semaine" button of the home screen opens the generation screen; after generation the review screen opens.

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

- Run automatically at startup (NFR-17). Each start also records the server version in `server_version` (first and last start), shown on the system page. Driver `github.com/jackc/pgx/v5` (Go has no PostgreSQL driver). Other dependencies: `golang-jwt/jwt/v5` (access tokens), `golang.org/x/crypto` (argon2id), `rsc.io/qr` (2FA QR code).
- Plain, forward-only SQL files `NNNN_description.sql` in `server/internal/migrate/migrations/`, embedded in the binary; a small in-house runner applies them in order, one transaction each, records them in `schema_migrations`, and holds a PostgreSQL advisory lock so two instances never migrate at once.
- Applied files are never edited; fixes go into a new migration.
- No down migrations: a binary older than the schema refuses to start; rollback = restore a backup, so back up before upgrading.

## 13. Security (decided)

Modeled on the author's paper-stacks project.

- **Passwords**: argon2id (19 MiB, 3 passes), 8 characters minimum. The first admin is created by the setup wizard (`/admin/setup`, only while the instance has no account).
- **App tokens**: JWT access token (HS256, 15 min, `Authorization: Bearer`) + random 256-bit refresh token (60 days) used only on `POST /api/v1/auth/refresh`. Each refresh rotates the token within its device session; presenting an already rotated token revokes the whole session and is logged. Every request checks that the device session is still open and the account active, so a revocation takes effect immediately. Endpoints: `POST /api/v1/auth/login`, `/refresh`, `/logout`, `GET /api/v1/me`.
- **Invitations**: the admin creates one in the web admin (`/admin/invitations`), valid 1, 7 (default) or 30 days. The clear code (128-bit, base32) is shown once; only its SHA-256 hash is stored. The admin shares a plain web link, `https://<server>/invite/<code>` (also rendered as a QR code): it works with or without the app. That public page (no session, never reads the database, so it neither consumes the code nor reveals whether it is valid) offers an "Open in Mijote" button and, for people without the app, the install steps with a link to the project download page and the server address and code to type. The button uses the deep link `mijote://invite?server=<server URL>&code=<code>`, which the app handles directly; the app also accepts the server URL and the code typed by hand ("J'ai une invitation"). There is no in-app QR scanner (camera/ML dependency avoided): the phone's camera app scans the QR code and opens the web page. An invitation link never replaces an open session. `POST /api/v1/invitations/redeem` (`code`, `username`, `displayName`, `password`, `deviceName`) creates the **member** account, consumes the code in one transaction and signs the device in (answers `201` with the same token pair as login). Unknown, used, revoked and expired codes all answer `404 invalid_invitation`; failures count against the per-address limit. A refused username or password (`422`) leaves the code usable.
- **Accounts** (`/admin/accounts`): list, deactivate/reactivate a member (closes its sessions), set a new password for a member (closes its sessions). The admin account cannot be disabled; its password changes from "Mon compte".
- **Web admin session**: cookie `mijote_admin` (HttpOnly, SameSite=Strict, Secure over HTTPS, path `/admin`); 12 h max and 2 h idle, or 15 days max and 5 days idle with "stay signed in". Every form carries a CSRF token derived from the session. Changing the password closes the account's other sessions.
- **Admin sign-in**: password only (single admin). Optional **TOTP** (RFC 6238, 6 digits, 30 s, ±1 step, each code accepted once) set up from *Mon compte* with a QR code, plus 10 single-use backup codes. Two steps: password → signed 5-min challenge cookie → code → session. Disabling 2FA or regenerating backup codes requires the password and a code. Recovery: `mijote reset-2fa <username>` on the server. 2FA covers the web admin only.
- **Rate limiting** (in memory, app and admin): 5 failures in 15 min on an account (admin: shared key) or 20 from one address block sign-in for 15 min (`429` + `Retry-After`). Same response whether the account exists or not.
- **Reverse proxy**: `X-Forwarded-For` / `X-Forwarded-Proto` are trusted only from `MIJOTE_TRUSTED_PROXIES`, for real client addresses and HTTPS detection.
- **Headers** on every response: `X-Content-Type-Options`, `X-Frame-Options: DENY`, `Referrer-Policy`, `Content-Security-Policy`, and HSTS over HTTPS.
- **Personal API tokens** (`/admin/tokens`, admin only): for scripts and assistants that read or write through `/api/v1` without a device session. Format `mjt_` + 256-bit random (URL-safe base64), sent as `Authorization: Bearer mjt_…`; only the SHA-256 hash is stored, the clear token is shown once. The admin picks a name, a scope (`read`: GET/HEAD only, otherwise `403 read_only_token`; `write`: all methods) and a lifetime of 7, 30, 90 (default) or 365 days (no token without expiry). A token acts with the rights of its creator's account, is checked on every request (revocation and account deactivation are immediate) and has no device session (`/auth/logout` answers `400`). Creation and revocation are logged; last use and address are shown in the list; tokens expired for 30 days are purged.
- **Tracking in the web admin**: device and admin sessions (revocable one by one, "log out my other sessions"), and the login journal (sign-ins, failures, lockouts, logouts, token reuse, 2FA events) with a "failures only" filter.
- **Retention**: journal 180 days, expired tokens and sessions 30 days (daily purge).
