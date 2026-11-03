# Mijote API guide

How to use the Mijote REST API from scripts, tools and LLM assistants, without reading
the source code. The exhaustive, machine-readable reference is
[`openapi.yaml`](openapi.yaml) (OpenAPI 3.1): give it to a tool or an assistant together
with this guide.

- [1. Overview](#1-overview)
- [2. Getting access](#2-getting-access)
- [3. Conventions](#3-conventions)
- [4. Errors](#4-errors)
- [5. Endpoints](#5-endpoints)
- [6. Recipe data](#6-recipe-data)
- [7. Add a recipe from a URL](#7-add-a-recipe-from-a-url)
- [8. Plan menus](#8-plan-menus)
- [9. Rules for assistants](#9-rules-for-assistants)

## 1. Overview

Mijote is a self-hosted family weekly menu planner. **One instance = one family**: a
single shared recipe base and a single shared menu, seen by every account.

| Concept | What it is |
|---|---|
| Recipe | Title, dish type, times, ingredients (quantities **for one person**), steps, tags, seasons, meals it suits, optional image and source link. |
| Tag | Free-form family label ("salade", "four"), created implicitly by recipes. |
| Menu slot | One planned meal: a date, `lunch` or `dinner`, and either a recipe or a free-text *manual meal* ("Pizza chez des amis"). |
| Generation | The server fills a period with recipes, avoiding recent repeats, balancing dish types, preferring quick recipes on weekdays and in-season recipes. |

Roles: `admin` (one per instance, everything), `member` (read, add and edit recipes,
plan and generate menus), `readonly` (read only). Archiving recipes is admin-only;
renaming or deleting tags and restoring recipes happen in the web admin (`/admin/`).

Base URL: `https://<your-server>/api/v1`. `GET /api/versions` lists the supported
versions (`{"versions":["v1"]}`); `GET /healthz` checks the server and its database.
Neither needs a token.

## 2. Getting access

**Personal API token (recommended for scripts and assistants).** The admin creates it
in the web admin, *API tokens* page (`/admin/tokens`): a name, a scope and a lifetime
(7, 30, 90 or 365 days). The clear token (`mjt_…`) is shown once.

- Scope `read`: GET and HEAD only; any other method answers `403 read_only_token`.
- Scope `write`: every method. Needed to add recipes or plan meals.
- The token acts with the rights of the admin who created it, but cannot use the
  `/api/v1/admin/*` endpoints (those require the admin signed in on a device).
- It is checked on every request: revocation is immediate.

```bash
export MIJOTE=https://mijote.example.org
export TOKEN=mjt_xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx
curl -s -H "Authorization: Bearer $TOKEN" "$MIJOTE/api/v1/me"
```

**App sign-in (what the Android app does).** `POST /api/v1/auth/login`
`{"username","password","deviceName"}` returns `accessToken` (JWT, 15 minutes),
`refreshToken` (60 days, single use) and the user. Before the access token expires,
`POST /api/v1/auth/refresh` `{"refreshToken"}` returns a new pair; **always keep the new
refresh token**: reusing an old one closes the session. `POST /api/v1/auth/logout` closes
it. Repeated failures lock sign-in for 15 minutes (`429` with `Retry-After`).

## 3. Conventions

- Send `Authorization: Bearer <token>` on every `/api/v1` call except login, refresh and
  invitation redeem.
- JSON in and out (`Content-Type: application/json`), camelCase fields, 64 KiB per body.
  Unknown request fields are ignored. Optional values are `null` when absent.
- Dates are `YYYY-MM-DD` in the family timezone (the server tells "today" in
  `GET /api/v1/menu`); timestamps are RFC 3339.
- Ids are UUIDs.
- Writes are applied immediately; concurrent edits follow "last write wins". Every
  recipe and menu slot has a `version` incremented on each change.
- Images are uploaded as raw bytes, never linked: the server never downloads anything
  from the internet (no web page, no image URL).

## 4. Errors

Every error is `{"error": "<code>", "message": "<detail>"}`. Rely on the HTTP status and
`error`; `message` is for humans (**in French** for `validation_error`, e.g.
`"unité inconnue : cup"`).

| Status | `error` | Meaning / what to do |
|---|---|---|
| 400 | `invalid_json` | Malformed JSON body. |
| 400 | `validation_error` | Required field missing (sign-in, redeem). |
| 400 | `invalid_date` | Bad `from` / `to` in `GET /menu`. |
| 401 | `unauthorized` | Token missing, invalid, expired or revoked. Refresh or ask for a new token. |
| 401 | `invalid_credentials`, `invalid_refresh_token` | Sign in again. |
| 403 | `forbidden` | Role not allowed (read-only user writing, member archiving, `/admin` without admin device session). |
| 403 | `read_only_token` | Token with scope `read` used to write. |
| 404 | `not_found` | Unknown recipe, meal or account (or recipe without image). |
| 404 | `invalid_invitation` | Invitation unknown, used, revoked or expired. |
| 409 | `slot_exists` | That date and meal is already planned; resend with `"replace": true` if intended. |
| 409 | `not_enough_recipes` | Fewer active recipes than the family minimum; body has `have` and `need`. Add recipes. |
| 413 | `too_large` | JSON body over 64 KiB. |
| 422 | `validation_error` | A field breaks a rule (see [§6](#6-recipe-data)); fix the data, don't retry as is. |
| 429 | `too_many_requests` | Wait `Retry-After` seconds. |
| 500 | `internal_error` | Server problem; retry later. |

## 5. Endpoints

Full schemas, parameters and responses: [`openapi.yaml`](openapi.yaml). "Write" = role
`admin` or `member`, and a `write` token.

| Method and path | Who | Purpose |
|---|---|---|
| `GET /healthz`, `GET /api/versions` | anyone | Health, supported API versions. |
| `POST /api/v1/auth/login` · `/refresh` · `/logout` | — | App sign-in, token rotation, sign-out. |
| `POST /api/v1/invitations/redeem` | — | Create an account from an invitation code. |
| `GET /api/v1/me` | any user | Current user, role and `preferences.people`. |
| `PUT /api/v1/me/preferences` | any user | `{"people": 1..20}`: number of people to cook for. |
| `GET /api/v1/recipes?q=&tag=&dishType=&favorite=&limit=&offset=` | any user | Search active recipes (summaries: no ingredients or steps); `favorite=1` keeps the caller's favorites only. |
| `POST /api/v1/recipes` | write | Create a recipe. |
| `GET /api/v1/recipes/{id}` | any user | Full recipe. |
| `PUT /api/v1/recipes/{id}` | write | Replace the whole recipe content. |
| `DELETE /api/v1/recipes/{id}` | admin | Archive the recipe. |
| `GET /api/v1/recipes/{id}/image` | any user | JPEG thumbnail. |
| `PUT /api/v1/recipes/{id}/image` | write | Upload the image (raw JPEG/PNG/GIF body, 10 MB max). |
| `DELETE /api/v1/recipes/{id}/image` | write | Remove the image. |
| `PUT·DELETE /api/v1/recipes/{id}/favorite` | any user | Add the recipe to / remove it from **the caller's own** favorites (`204`). Personal, not shared with the family; read-only users have favorites too. |
| `GET /api/v1/tags` | any user | Tags with their recipe count. |
| `GET /api/v1/menu?from=&to=` | any user | Planned meals and generation info. |
| `POST /api/v1/menu/generate` | write | Generate a period. |
| `POST /api/v1/menu/slots` | write | Plan one meal (server pick, given recipe or manual meal). |
| `PUT /api/v1/menu/slots/{id}` | write | Turn a meal into a given recipe or manual meal. |
| `PUT /api/v1/menu/slots/{id}/people` | write | `{"people": 1..20}`: number of covers of the meal, shared by the family. |
| `POST /api/v1/menu/slots/{id}/swap` | write | Another recipe picked by the server. |
| `DELETE /api/v1/menu/slots/{id}` | write | Remove a meal. |
| `GET·POST /api/v1/admin/invitations`, `POST …/{id}/revoke` | admin (app) | Invitations. |
| `GET /api/v1/admin/users`, `PUT …/{id}`, `PUT …/{id}/password` | admin (app) | Accounts. |

## 6. Recipe data

Body of `POST /api/v1/recipes` and `PUT /api/v1/recipes/{id}`. The response is the
stored recipe, with the read-only fields `id`, `image`, `createdAt`, `updatedAt`,
`version`, `archivedAt` and `favorite` (ignored if sent). `favorite` is personal: whether the
recipe is in the favorites of the user (or API token owner) asking.

| Field | Type | Required | Rules |
|---|---|---|---|
| `title` | string | **yes** | 1–200 characters. |
| `dishType` | `meat` \| `fish` \| `vegetarian` | **yes** | Used to balance menus. |
| `sourceUrl` | string \| null | no | http(s) URL, 2000 characters max. **Unique**: a link already used (archived recipes included) is refused with `422`. |
| `prepMinutes`, `cookMinutes`, `totalMinutes` | int \| null | no | 0–100000. If `totalMinutes` is null, it becomes prep + cook. Recipes above the family's "quick" threshold (30 min by default) are avoided on weekdays; no time = quick. |
| `servings` | int \| null | no | 1–1000. Yield of the original recipe, informational only. |
| `mealTypes` | array of `lunch`, `dinner` | no | Meals the recipe suits; empty or absent = both. |
| `seasons` | array of `spring`, `summer`, `autumn`, `winter` | no | Empty = all year. Out-of-season recipes are avoided by generation. |
| `tags` | array of string | no | 30 max, 40 characters each; new tags are created; case-insensitive duplicates dropped. |
| `ingredients` | array of Ingredient | no | 100 max. |
| `steps` | array of string | no | 100 max, 4000 characters each, in order; empty ones dropped. |
| `notes` | string \| null | no | 4000 characters max. |

**Ingredient** (`{rawText, quantity, unit, name, note}`):

| Field | Rules |
|---|---|
| `name` | **Required**, non-empty: the ingredient itself ("farine", "œufs"). |
| `quantity` | Number ≥ 0 **for one person**, or `null` ("sel, poivre", "quelques feuilles de basilic"). Fractions as decimals (½ → `0.5`); a range keeps its upper value ("2 à 3" → 3). |
| `unit` | A code from the closed list below, or `null`. Any other code answers `422`. |
| `note` | Preparation detail ("finement haché"), or `null`. |
| `rawText` | The original line as displayed ("200 g de farine, tamisée"). Optional: built from the other fields when empty. `rawText` + `name` ≤ 300 characters. |

Units (code → French label, recognized aliases):

| Family | Codes |
|---|---|
| Mass | `g` (g, gr, gramme), `kg` (kg, kilo, kilogramme) |
| Volume | `ml`, `cl`, `dl`, `l` (litre); `tbsp` = c. à soupe (càs, cs, cuillère à soupe, 15 ml); `tsp` = c. à café (càc, cc, cuillère à café, 5 ml) |
| Small amount | `pinch` (pincée), `dash` (trait, filet), `handful` (poignée) |
| Count | `piece` (no unit: "3 œufs"), `clove` (gousse), `slice` (tranche), `bunch` (botte, bouquet), `sprig` (brin), `leaf` (feuille), `can` (boîte, conserve), `pack` (sachet, paquet), `jar` (pot) |

Not in the list (e.g. "cup", "verre", "noix de beurre"): set `unit` to `null`, keep the
quantity, and keep the original wording in `rawText` (optionally in `name`).

## 7. Add a recipe from a URL

The server **never fetches web pages or images**. The client (app, script or
assistant) does the extraction, then sends a structured recipe. Required: a token with
scope `write`, from an `admin` or `member` account.

### Steps

1. **Fetch the page** at the URL (follow redirects; keep the final canonical URL, without
   tracking parameters, as `sourceUrl`).
2. **Extract the recipe**, in this order of preference:
   1. JSON-LD: `<script type="application/ld+json">` blocks; find the object whose
      `@type` is or contains `Recipe` (it may be at the top level, in an array, or inside
      `@graph`);
   2. microdata (`itemtype="https://schema.org/Recipe"`);
   3. otherwise, read the page text (title, ingredient list, steps) or ask the user.
3. **Map it** to the Mijote fields (table below), dividing quantities by the yield.
4. **Decide the fields schema.org lacks**: `dishType` (required), `mealTypes`,
   `seasons`, `tags`. Infer them from the ingredients, category and keywords; ask the user
   when unsure (especially `dishType`).
5. **Check for a duplicate** (optional): `GET /api/v1/recipes?q=<title>` and compare
   `sourceUrl`. The server refuses a duplicate link anyway (`422`, "une recette avec ce
   lien existe déjà").
6. **Create** it: `POST /api/v1/recipes` → `201` with the recipe and its `id`.
7. **Upload the image** (optional): download the schema.org `image` yourself, then
   `PUT /api/v1/recipes/{id}/image` with the raw bytes (JPEG, PNG or GIF; 10 MB max; WebP
   and AVIF are refused, convert them first). An image failure does not undo the recipe.

### schema.org → Mijote mapping

| schema.org (Recipe) | Mijote field | How |
|---|---|---|
| `name` | `title` | Trim; 200 characters max. |
| page URL (or `url`, `mainEntityOfPage`) | `sourceUrl` | Canonical http(s) URL. |
| `prepTime`, `cookTime`, `totalTime` | `prepMinutes`, `cookMinutes`, `totalMinutes` | ISO 8601 durations to minutes: `PT1H15M` → 75, `PT45M` → 45, `P0DT20M` → 20. |
| `recipeYield` | `servings` | First integer of the value ("4", "4 personnes", `["4","4 servings"]` → 4); `null` if none. |
| `recipeIngredient[]` | `ingredients[]` | One line each: parse quantity / unit / name / note (§6), then **divide the quantity by the yield** (servings, 1 if unknown); keep the original line in `rawText`. |
| `recipeInstructions` | `steps[]` | `HowToStep.text` in order; flatten `HowToSection.itemListElement`; a single text block is split into steps (numbered lines or sentences / paragraphs). Strip HTML. |
| `recipeCategory`, `recipeCuisine`, `keywords` | `tags[]` | A few short family-useful tags (lower case, e.g. "tarte", "soupe", "four"); avoid generic ones like "recette". |
| `recipeCategory` (e.g. "Entrée", "Plat principal") | `mealTypes` | Usually both meals; restrict only when obvious. |
| `description` | `notes` | Optional; keep short or leave `null`. |
| `image` (string, array, or `ImageObject.url`) | image upload | Pick the largest; download it; `PUT …/image`. |
| — | `dishType` | `meat` if it contains meat or poultry (charcuterie, lardons and bacon included); else `fish` if fish or seafood; else `vegetarian`. |
| — | `seasons` | Empty (all year) unless the recipe depends on seasonal produce (e.g. asparagus → spring, tomato salad → summer). |

### Quantities are for one person

The app multiplies every quantity by the user's number of people
(`preferences.people`). So, for a recipe "pour 4 personnes" with "500 g de farine",
send `quantity: 125`, `unit: "g"`, `servings: 4`, and keep `rawText: "500 g de farine"`.
Round to two decimals at most. When the yield is unknown, send the quantities as they
are and `servings: null`.

### Complete example

```bash
# 1. Create the recipe (page: https://www.example.org/recettes/soupe-potiron, "pour 4 personnes")
curl -s -X POST "$MIJOTE/api/v1/recipes" \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{
    "title": "Soupe de potiron au lait de coco",
    "sourceUrl": "https://www.example.org/recettes/soupe-potiron",
    "prepMinutes": 15, "cookMinutes": 30, "totalMinutes": 45,
    "servings": 4,
    "dishType": "vegetarian",
    "mealTypes": ["lunch", "dinner"],
    "seasons": ["autumn", "winter"],
    "tags": ["soupe", "potiron"],
    "ingredients": [
      {"rawText": "1 kg de potiron",            "quantity": 0.25, "unit": "kg",    "name": "potiron",       "note": null},
      {"rawText": "1 oignon, émincé",           "quantity": 0.25, "unit": "piece", "name": "oignon",        "note": "émincé"},
      {"rawText": "40 cl de lait de coco",      "quantity": 10,   "unit": "cl",    "name": "lait de coco",  "note": null},
      {"rawText": "2 c. à soupe d'\''huile d'\''olive", "quantity": 0.5, "unit": "tbsp", "name": "huile d'\''olive", "note": null},
      {"rawText": "sel, poivre",                "quantity": null, "unit": null,    "name": "sel, poivre",   "note": null}
    ],
    "steps": [
      "Éplucher le potiron et le couper en cubes.",
      "Faire revenir l'\''oignon dans l'\''huile, ajouter le potiron et couvrir d'\''eau.",
      "Cuire 25 minutes, ajouter le lait de coco et mixer. Saler, poivrer."
    ],
    "notes": null
  }'
# → 201 {"id":"6f1c…","title":"Soupe de potiron au lait de coco",…,"image":null,"version":1,…}

# 2. Upload the picture downloaded from the page
curl -s -o /tmp/soupe.jpg "https://www.example.org/images/soupe-potiron.jpg"
curl -s -X PUT "$MIJOTE/api/v1/recipes/6f1c…/image" \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: image/jpeg" \
  --data-binary @/tmp/soupe.jpg
# → 200 {…,"image":"/api/v1/recipes/6f1c…/image?v=1a2b3c4d",…}
```

Then, optionally, plan it: `POST /api/v1/menu/slots`
`{"date":"2026-11-05","meal":"dinner","recipeId":"6f1c…"}` (see §8).

### Editing later

`PUT /api/v1/recipes/{id}` **replaces** the whole content: first `GET` the recipe, change
the fields, and send everything back (ingredients, steps and tags included; omitted
arrays become empty). The image is kept.

## 8. Plan menus

- **Read the menu**: `GET /api/v1/menu` (today onwards) or `?from=2026-11-02&to=2026-11-08`.
  Each slot: `{id, date, meal, recipe: {id, title, dishType, prepMinutes, totalMinutes, image} | null, title | null, locked, version}`.
  `generation` tells whether generation is possible (`recipeCount` ≥ `minRecipes`).
- **Generate a period**: `POST /api/v1/menu/generate`
  `{"start":"2026-11-02","meals":14,"plan":"lunch_dinner"}` (`meals` 1–42; `plan`
  `default` | `lunch_dinner` | `lunch` | `dinner`). Locked meals and manual meals in the
  period are kept; the other meals are re-picked. Below the minimum recipe count:
  `409 not_enough_recipes`.
- **Plan one meal**: `POST /api/v1/menu/slots` `{"date","meal"}` plus `recipeId` (that
  recipe, locked), `title` (manual meal, 1–100 characters, locked), or neither (server
  pick). `409 slot_exists` if already planned, unless `"replace": true`.
- **Change a meal**: `PUT /api/v1/menu/slots/{id}` `{"recipeId"}` or `{"title"}` (locked);
  `POST /api/v1/menu/slots/{id}/swap` for another server pick (unlocks);
  `DELETE /api/v1/menu/slots/{id}` to remove it.
- **Covers of a meal**: each slot has `people` (1–20, shared by the family; `null` for
  meals planned before it existed: use the user's `preferences.people`). Generation and
  `POST /api/v1/menu/slots` set it to the planner's preference;
  `PUT /api/v1/menu/slots/{id}/people` `{"people": 6}` changes it. Ingredient quantities
  (stored for one person) are multiplied by it.

## 9. Rules for assistants

- Use a personal API token; never ask for or store a user's password.
- Check the role first if unsure: `GET /api/v1/me` (`readonly` cannot write).
- Never send an image URL to the server: download the image and upload its bytes.
- Quantities are for one person; `unit` must be one of the 20 codes or `null`.
- `dishType` is required and drives menu balance: ask the user when the recipe is
  ambiguous.
- Do not create a recipe twice: a `422` mentioning "existe déjà" means the link is
  already in the base (maybe archived, ask the admin to restore it in the web admin).
- Before replacing an already planned meal (`replace: true`, `PUT …/slots/{id}`), check
  `GET /api/v1/menu` and confirm with the user: the menu is shared by the whole family.
- On `422`, read `message` (French), fix the data and retry once; on `401` with a personal
  token, the token is expired or revoked: ask the admin for a new one.
