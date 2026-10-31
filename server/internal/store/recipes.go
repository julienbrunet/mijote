package store

import (
	"context"
	"errors"
	"io"
	"math"
	"net/url"
	"slices"
	"strconv"
	"strings"
	"time"
	"unicode/utf8"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgconn"

	"github.com/julienbrunet/mijote/server/internal/images"
)

// Recipes and tags (FR-REC-10..20, FR-ADM-04), data model in requirements.md §9.

// Limits protecting the database and the clients.
const (
	maxTitleLen       = 200
	maxURLLen         = 2000
	maxNotesLen       = 4000
	maxStepLen        = 4000
	maxIngredientLen  = 300
	maxTagLen         = 40
	maxIngredients    = 100
	maxSteps          = 100
	maxTagsPerRecipe  = 30
	maxMinutes        = 100000
	maxServings       = 1000
	maxIngredientQty  = 1000000
	DefaultRecipePage = 100
	MaxRecipePage     = 500
)

// Option is a code stored in the database with its French label.
type Option struct{ Code, Label string }

// Closed lists of requirements.md §9 and §9.1.
var (
	DishTypes = []Option{{"meat", "Viande"}, {"fish", "Poisson"}, {"vegetarian", "Végétarien"}}
	Seasons   = []Option{{"spring", "Printemps"}, {"summer", "Été"}, {"autumn", "Automne"}, {"winter", "Hiver"}}
	Meals     = []Option{{"lunch", "Déjeuner"}, {"dinner", "Dîner"}}
	Units     = []Option{
		{"g", "g"}, {"kg", "kg"}, {"ml", "ml"}, {"cl", "cl"}, {"dl", "dl"}, {"l", "l"},
		{"tbsp", "c. à soupe"}, {"tsp", "c. à café"},
		{"pinch", "pincée"}, {"dash", "trait"}, {"handful", "poignée"},
		{"piece", ""}, {"clove", "gousse"}, {"slice", "tranche"}, {"bunch", "botte"}, {"sprig", "brin"},
		{"leaf", "feuille"}, {"can", "boîte"}, {"pack", "sachet"}, {"jar", "pot"},
	}
)

func codes(opts []Option) []string {
	out := make([]string, len(opts))
	for i, o := range opts {
		out[i] = o.Code
	}
	return out
}

// Ingredient is one line of a recipe. Quantity and Unit are null for "a pinch of salt".
type Ingredient struct {
	RawText  string   `json:"rawText"`
	Quantity *float64 `json:"quantity"`
	Unit     *string  `json:"unit"`
	Name     string   `json:"name"`
	Note     *string  `json:"note"`
}

// Recipe is a recipe with its ingredients, steps and tags. It is also the
// shape of the create / update requests (read-only fields are ignored).
type Recipe struct {
	ID        string  `json:"id"`
	Title     string  `json:"title"`
	SourceURL *string `json:"sourceUrl"`
	// Image is the API path of the thumbnail, null without image. Thumbnails are
	// uploaded to the server, never linked from elsewhere.
	Image        *string      `json:"image"`
	ImagePath    *string      `json:"-"` // file name in the image directory
	PrepMinutes  *int         `json:"prepMinutes"`
	CookMinutes  *int         `json:"cookMinutes"`
	TotalMinutes *int         `json:"totalMinutes"`
	Servings     *int         `json:"servings"`
	DishType     string       `json:"dishType"`
	Seasons      []string     `json:"seasons"`
	MealTypes    []string     `json:"mealTypes"`
	Notes        *string      `json:"notes"`
	Tags         []string     `json:"tags"`
	Ingredients  []Ingredient `json:"ingredients"`
	Steps        []string     `json:"steps"`
	CreatedAt    time.Time    `json:"createdAt"`
	UpdatedAt    time.Time    `json:"updatedAt"`
	// Version is incremented on every change (read-only). Concurrent edits
	// follow "last write wins" (requirements.md §11).
	Version    int        `json:"version"`
	ArchivedAt *time.Time `json:"archivedAt"`
}

// ComposeRawText builds the displayed line of an ingredient ("200 g farine, hachée").
func ComposeRawText(i Ingredient) string {
	var parts []string
	if i.Quantity != nil {
		parts = append(parts, strconv.FormatFloat(*i.Quantity, 'f', -1, 64))
	}
	if i.Unit != nil {
		for _, u := range Units {
			if u.Code == *i.Unit && u.Label != "" {
				parts = append(parts, u.Label)
			}
		}
	}
	parts = append(parts, i.Name)
	s := strings.Join(parts, " ")
	if i.Note != nil {
		s += ", " + *i.Note
	}
	return s
}

func blankToNil(s *string) *string {
	if s == nil {
		return nil
	}
	if t := strings.TrimSpace(*s); t != "" {
		return &t
	}
	return nil
}

func checkMinutes(label string, v *int) error {
	if v != nil && (*v < 0 || *v > maxMinutes) {
		return invalid(label + " : valeur invalide")
	}
	return nil
}

// normalize trims and validates a recipe in place and fills the defaults.
func (r *Recipe) normalize() error {
	r.Title = strings.TrimSpace(r.Title)
	switch {
	case r.Title == "":
		return invalid("le titre est obligatoire")
	case utf8.RuneCountInString(r.Title) > maxTitleLen:
		return invalid("le titre est trop long")
	}
	r.SourceURL, r.Notes = blankToNil(r.SourceURL), blankToNil(r.Notes)
	for label, p := range map[string]*string{"le lien de la recette": r.SourceURL} {
		if p == nil {
			continue
		}
		if u, err := url.Parse(*p); len(*p) > maxURLLen || err != nil || (u.Scheme != "http" && u.Scheme != "https") || u.Host == "" {
			return invalid(label + " doit être une adresse http(s) valide")
		}
	}
	if r.Notes != nil && utf8.RuneCountInString(*r.Notes) > maxNotesLen {
		return invalid("les notes sont trop longues")
	}
	for label, v := range map[string]*int{"temps de préparation": r.PrepMinutes, "temps de cuisson": r.CookMinutes, "temps total": r.TotalMinutes} {
		if err := checkMinutes(label, v); err != nil {
			return err
		}
	}
	if r.TotalMinutes == nil && (r.PrepMinutes != nil || r.CookMinutes != nil) {
		t := 0
		if r.PrepMinutes != nil {
			t += *r.PrepMinutes
		}
		if r.CookMinutes != nil {
			t += *r.CookMinutes
		}
		r.TotalMinutes = &t
	}
	if r.Servings != nil && (*r.Servings <= 0 || *r.Servings > maxServings) {
		return invalid("nombre de personnes : valeur invalide")
	}
	if !slices.Contains(codes(DishTypes), r.DishType) {
		return invalid("le type de plat est obligatoire (viande, poisson ou végétarien)")
	}
	var err error
	if r.Seasons, err = canonicalSet(r.Seasons, Seasons, "saison"); err != nil {
		return err
	}
	if len(r.MealTypes) == 0 {
		r.MealTypes = codes(Meals)
	}
	if r.MealTypes, err = canonicalSet(r.MealTypes, Meals, "repas"); err != nil {
		return err
	}
	if r.Tags, err = normalizeTags(r.Tags); err != nil {
		return err
	}

	if len(r.Ingredients) > maxIngredients {
		return invalid("trop d'ingrédients")
	}
	for n := range r.Ingredients {
		i := &r.Ingredients[n]
		i.Name, i.RawText = strings.TrimSpace(i.Name), strings.TrimSpace(i.RawText)
		i.Note, i.Unit = blankToNil(i.Note), blankToNil(i.Unit)
		if i.Name == "" {
			return invalid("chaque ingrédient doit avoir un nom")
		}
		if i.Unit != nil && !slices.Contains(codes(Units), *i.Unit) {
			return invalid("unité inconnue : " + *i.Unit)
		}
		if i.Quantity != nil && (math.IsNaN(*i.Quantity) || *i.Quantity < 0 || *i.Quantity > maxIngredientQty) {
			return invalid("quantité invalide pour « " + i.Name + " »")
		}
		if i.RawText == "" {
			i.RawText = ComposeRawText(*i)
		}
		if utf8.RuneCountInString(i.RawText)+utf8.RuneCountInString(i.Name) > maxIngredientLen {
			return invalid("un ingrédient est trop long")
		}
	}

	steps := make([]string, 0, len(r.Steps))
	for _, s := range r.Steps {
		if s = strings.TrimSpace(s); s != "" {
			if utf8.RuneCountInString(s) > maxStepLen {
				return invalid("une étape est trop longue")
			}
			steps = append(steps, s)
		}
	}
	if len(steps) > maxSteps {
		return invalid("trop d'étapes")
	}
	r.Steps = steps
	return nil
}

// canonicalSet validates values against a closed list and returns them without duplicates, in list order.
func canonicalSet(values []string, allowed []Option, what string) ([]string, error) {
	for _, v := range values {
		if !slices.Contains(codes(allowed), v) {
			return nil, invalid(what + " inconnu(e) : " + v)
		}
	}
	out := []string{}
	for _, o := range allowed {
		if slices.Contains(values, o.Code) {
			out = append(out, o.Code)
		}
	}
	return out, nil
}

// normalizeTags trims tags, drops empty ones and case-insensitive duplicates.
func normalizeTags(tags []string) ([]string, error) {
	out := []string{}
	seen := map[string]bool{}
	for _, t := range tags {
		t = strings.Join(strings.Fields(t), " ")
		if t == "" || seen[strings.ToLower(t)] {
			continue
		}
		if utf8.RuneCountInString(t) > maxTagLen {
			return nil, invalid("une étiquette est trop longue (" + strconv.Itoa(maxTagLen) + " caractères maximum)")
		}
		seen[strings.ToLower(t)] = true
		out = append(out, t)
	}
	if len(out) > maxTagsPerRecipe {
		return nil, invalid("trop d'étiquettes")
	}
	return out, nil
}

// ---------- Reading ----------

const recipeCols = `r.id::text, r.title, r.source_url, r.image_path, r.prep_minutes, r.cook_minutes, r.total_minutes,
	r.servings, r.dish_type::text, r.seasons::text[], r.meal_types::text[], r.notes,
	array(SELECT t.name FROM recipe_tag rt JOIN tag t ON t.id = rt.tag_id WHERE rt.recipe_id = r.id ORDER BY lower(t.name)),
	r.created_at, r.updated_at, r.version, r.archived_at`

func scanRecipe(row pgx.Row) (Recipe, error) {
	var r Recipe
	err := row.Scan(&r.ID, &r.Title, &r.SourceURL, &r.ImagePath, &r.PrepMinutes, &r.CookMinutes, &r.TotalMinutes,
		&r.Servings, &r.DishType, &r.Seasons, &r.MealTypes, &r.Notes, &r.Tags,
		&r.CreatedAt, &r.UpdatedAt, &r.Version, &r.ArchivedAt)
	r.Ingredients, r.Steps = []Ingredient{}, []string{}
	if r.ImagePath != nil {
		// "<id>-<hash>.jpg": the hash makes the URL change with the picture.
		v := strings.TrimSuffix(strings.TrimPrefix(*r.ImagePath, r.ID+"-"), ".jpg")
		img := "/api/v1/recipes/" + r.ID + "/image?v=" + v
		r.Image = &img
	}
	return r, err
}

// RecipeFilter selects recipes for a listing.
type RecipeFilter struct {
	Q        string // matches the title or a tag
	Tag      string // exact tag, case-insensitive
	DishType string
	Archived string // "" = active only, "only" = archived only, "all"
	Limit    int
	Offset   int
}

func likePattern(q string) string {
	return "%" + strings.NewReplacer(`\`, `\\`, `%`, `\%`, `_`, `\_`).Replace(q) + "%"
}

// ListRecipes returns recipes without their ingredients and steps, sorted by title.
func (s *Store) ListRecipes(ctx context.Context, f RecipeFilter) ([]Recipe, error) {
	if f.Limit <= 0 {
		f.Limit = DefaultRecipePage
	}
	f.Limit = min(f.Limit, MaxRecipePage)
	where, args := []string{"true"}, []any{}
	arg := func(v any) string { args = append(args, v); return "$" + strconv.Itoa(len(args)) }
	if q := strings.TrimSpace(f.Q); q != "" {
		p := arg(likePattern(q))
		where = append(where, `(r.title ILIKE `+p+` OR EXISTS (SELECT 1 FROM recipe_tag rt JOIN tag t ON t.id = rt.tag_id
			WHERE rt.recipe_id = r.id AND t.name ILIKE `+p+`))`)
	}
	if f.Tag != "" {
		where = append(where, `EXISTS (SELECT 1 FROM recipe_tag rt JOIN tag t ON t.id = rt.tag_id
			WHERE rt.recipe_id = r.id AND lower(t.name) = lower(`+arg(f.Tag)+`))`)
	}
	if f.DishType != "" {
		where = append(where, `r.dish_type::text = `+arg(f.DishType))
	}
	switch f.Archived {
	case "only":
		where = append(where, `r.archived_at IS NOT NULL`)
	case "all":
	default:
		where = append(where, `r.archived_at IS NULL`)
	}
	rows, err := s.Pool.Query(ctx, `SELECT `+recipeCols+` FROM recipe r WHERE `+strings.Join(where, " AND ")+
		` ORDER BY lower(r.title), r.id LIMIT `+arg(f.Limit)+` OFFSET `+arg(max(f.Offset, 0)), args...)
	if err != nil {
		return nil, err
	}
	list, err := pgx.CollectRows(rows, func(r pgx.CollectableRow) (Recipe, error) { return scanRecipe(r) })
	if list == nil {
		list = []Recipe{}
	}
	return list, err
}

// Recipe returns one recipe, archived or not, with its ingredients and steps.
func (s *Store) Recipe(ctx context.Context, id string) (Recipe, error) {
	if !validUUID(id) {
		return Recipe{}, ErrNotFound
	}
	return s.recipeIn(ctx, s.Pool, id)
}

type querier interface {
	Query(ctx context.Context, sql string, args ...any) (pgx.Rows, error)
	QueryRow(ctx context.Context, sql string, args ...any) pgx.Row
}

func (s *Store) recipeIn(ctx context.Context, q querier, id string) (Recipe, error) {
	r, err := scanRecipe(q.QueryRow(ctx, `SELECT `+recipeCols+` FROM recipe r WHERE r.id = $1`, id))
	if err != nil {
		return r, notFound(err)
	}
	rows, err := q.Query(ctx, `SELECT raw_text, quantity::float8, unit::text, name, note FROM recipe_ingredient
		WHERE recipe_id = $1 ORDER BY position`, id)
	if err != nil {
		return r, err
	}
	if r.Ingredients, err = pgx.CollectRows(rows, pgx.RowToStructByPos[Ingredient]); err != nil {
		return r, err
	}
	rows, err = q.Query(ctx, `SELECT text FROM recipe_step WHERE recipe_id = $1 ORDER BY position`, id)
	if err != nil {
		return r, err
	}
	r.Steps, err = pgx.CollectRows(rows, pgx.RowTo[string])
	if r.Ingredients == nil {
		r.Ingredients = []Ingredient{}
	}
	if r.Steps == nil {
		r.Steps = []string{}
	}
	return r, err
}

func validUUID(s string) bool {
	if len(s) != 36 {
		return false
	}
	for i, c := range s {
		switch {
		case i == 8 || i == 13 || i == 18 || i == 23:
			if c != '-' {
				return false
			}
		case !(c >= '0' && c <= '9' || c >= 'a' && c <= 'f' || c >= 'A' && c <= 'F'):
			return false
		}
	}
	return true
}

// ---------- Writing ----------

// CreateRecipe validates and stores a new recipe.
func (s *Store) CreateRecipe(ctx context.Context, userID string, in Recipe) (Recipe, error) {
	if err := in.normalize(); err != nil {
		return Recipe{}, err
	}
	var out Recipe
	err := s.tx(ctx, func(tx pgx.Tx) error {
		var id string
		err := tx.QueryRow(ctx, `INSERT INTO recipe (title, source_url, prep_minutes, cook_minutes, total_minutes,
				servings, dish_type, seasons, meal_types, notes, created_by)
			VALUES ($1,$2,$3,$4,$5,$6,$7::dish_type,$8::season[],$9::meal[],$10,$11) RETURNING id::text`,
			in.Title, in.SourceURL, in.PrepMinutes, in.CookMinutes, in.TotalMinutes, in.Servings,
			in.DishType, in.Seasons, in.MealTypes, in.Notes, userID).Scan(&id)
		if err != nil {
			return duplicateURL(err)
		}
		if err := writeContent(ctx, tx, id, in); err != nil {
			return err
		}
		out, err = s.recipeIn(ctx, tx, id)
		return err
	})
	return out, err
}

// UpdateRecipe replaces a recipe's content (last write wins, §11).
func (s *Store) UpdateRecipe(ctx context.Context, id string, in Recipe) (Recipe, error) {
	if !validUUID(id) {
		return Recipe{}, ErrNotFound
	}
	if err := in.normalize(); err != nil {
		return Recipe{}, err
	}
	var out Recipe
	err := s.tx(ctx, func(tx pgx.Tx) error {
		tag, err := tx.Exec(ctx, `UPDATE recipe SET title=$2, source_url=$3, prep_minutes=$4, cook_minutes=$5,
				total_minutes=$6, servings=$7, dish_type=$8::dish_type, seasons=$9::season[], meal_types=$10::meal[], notes=$11,
				version = version + 1, updated_at = now()
			WHERE id = $1`,
			id, in.Title, in.SourceURL, in.PrepMinutes, in.CookMinutes, in.TotalMinutes, in.Servings,
			in.DishType, in.Seasons, in.MealTypes, in.Notes)
		if err != nil {
			return duplicateURL(err)
		}
		if tag.RowsAffected() == 0 {
			return ErrNotFound
		}
		if _, err := tx.Exec(ctx, `DELETE FROM recipe_ingredient WHERE recipe_id = $1`, id); err != nil {
			return err
		}
		if _, err := tx.Exec(ctx, `DELETE FROM recipe_step WHERE recipe_id = $1`, id); err != nil {
			return err
		}
		if _, err := tx.Exec(ctx, `DELETE FROM recipe_tag WHERE recipe_id = $1`, id); err != nil {
			return err
		}
		if err := writeContent(ctx, tx, id, in); err != nil {
			return err
		}
		out, err = s.recipeIn(ctx, tx, id)
		return err
	})
	return out, err
}

func duplicateURL(err error) error {
	var pg *pgconn.PgError
	if errors.As(err, &pg) && pg.Code == "23505" {
		return invalid("une recette avec ce lien existe déjà (peut-être archivée)")
	}
	return err
}

// writeContent inserts the ingredients, steps and tags of a validated recipe.
func writeContent(ctx context.Context, tx pgx.Tx, id string, in Recipe) error {
	for n, i := range in.Ingredients {
		if _, err := tx.Exec(ctx, `INSERT INTO recipe_ingredient (recipe_id, position, raw_text, quantity, unit, name, note)
			VALUES ($1,$2,$3,$4,$5::unit,$6,$7)`, id, n, i.RawText, i.Quantity, i.Unit, i.Name, i.Note); err != nil {
			return err
		}
	}
	for n, text := range in.Steps {
		if _, err := tx.Exec(ctx, `INSERT INTO recipe_step (recipe_id, position, text) VALUES ($1,$2,$3)`, id, n, text); err != nil {
			return err
		}
	}
	for _, name := range in.Tags {
		var tagID string
		// The no-op update makes RETURNING work for an existing tag.
		if err := tx.QueryRow(ctx, `INSERT INTO tag (name) VALUES ($1)
			ON CONFLICT (lower(name)) DO UPDATE SET name = tag.name RETURNING id::text`, name).Scan(&tagID); err != nil {
			return err
		}
		if _, err := tx.Exec(ctx, `INSERT INTO recipe_tag (recipe_id, tag_id) VALUES ($1,$2)`, id, tagID); err != nil {
			return err
		}
	}
	return nil
}

// SetRecipeArchived archives (soft delete) or restores a recipe. Past menus keep their reference.
func (s *Store) SetRecipeArchived(ctx context.Context, id string, archived bool) (Recipe, error) {
	if !validUUID(id) {
		return Recipe{}, ErrNotFound
	}
	// A recipe already in the wanted state is left untouched (not an error).
	_, err := s.Pool.Exec(ctx, `UPDATE recipe SET archived_at = CASE WHEN $2 THEN now() ELSE NULL END,
			version = version + 1, updated_at = now()
		WHERE id = $1 AND (archived_at IS NOT NULL) <> $2`, id, archived)
	if err != nil {
		return Recipe{}, err
	}
	return s.Recipe(ctx, id)
}

// ---------- Tags ----------

// Tag is a family-wide label with the number of active recipes using it.
type Tag struct {
	ID      string `json:"id"`
	Name    string `json:"name"`
	Recipes int    `json:"recipes"`
}

// ListTags returns all tags sorted by name.
func (s *Store) ListTags(ctx context.Context) ([]Tag, error) {
	rows, err := s.Pool.Query(ctx, `SELECT t.id::text, t.name,
			(SELECT count(*) FROM recipe_tag rt JOIN recipe r ON r.id = rt.recipe_id
			 WHERE rt.tag_id = t.id AND r.archived_at IS NULL)::int
		FROM tag t ORDER BY lower(t.name)`)
	if err != nil {
		return nil, err
	}
	list, err := pgx.CollectRows(rows, pgx.RowToStructByPos[Tag])
	if list == nil {
		list = []Tag{}
	}
	return list, err
}

// touchTagRecipes bumps the version of the recipes carrying a tag, so that syncing devices see the change.
func touchTagRecipes(ctx context.Context, tx pgx.Tx, tagID string) error {
	_, err := tx.Exec(ctx, `UPDATE recipe SET version = version + 1, updated_at = now()
		WHERE id IN (SELECT recipe_id FROM recipe_tag WHERE tag_id = $1)`, tagID)
	return err
}

// RenameTag renames a tag. A name already used by another tag is refused.
func (s *Store) RenameTag(ctx context.Context, id, name string) error {
	names, err := normalizeTags([]string{name})
	if err != nil {
		return err
	}
	if len(names) == 0 {
		return invalid("le nom de l'étiquette est obligatoire")
	}
	if !validUUID(id) {
		return ErrNotFound
	}
	return s.tx(ctx, func(tx pgx.Tx) error {
		var other bool
		if err := tx.QueryRow(ctx, `SELECT EXISTS (SELECT 1 FROM tag WHERE lower(name) = lower($1) AND id <> $2)`,
			names[0], id).Scan(&other); err != nil {
			return err
		}
		if other {
			return invalid("une étiquette « " + names[0] + " » existe déjà")
		}
		tag, err := tx.Exec(ctx, `UPDATE tag SET name = $2 WHERE id = $1`, id, names[0])
		if err != nil {
			return err
		}
		if tag.RowsAffected() == 0 {
			return ErrNotFound
		}
		return touchTagRecipes(ctx, tx, id)
	})
}

// DeleteTag deletes a tag and removes it from the recipes.
func (s *Store) DeleteTag(ctx context.Context, id string) error {
	if !validUUID(id) {
		return ErrNotFound
	}
	return s.tx(ctx, func(tx pgx.Tx) error {
		if err := touchTagRecipes(ctx, tx, id); err != nil {
			return err
		}
		tag, err := tx.Exec(ctx, `DELETE FROM tag WHERE id = $1`, id)
		if err != nil {
			return err
		}
		if tag.RowsAffected() == 0 {
			return ErrNotFound
		}
		return nil
	})
}

// SetRecipeImage records the thumbnail file of a recipe (nil removes it) and
// returns the previous file name, which the caller deletes from disk.
func (s *Store) SetRecipeImage(ctx context.Context, id string, name *string) (old *string, err error) {
	if !validUUID(id) {
		return nil, ErrNotFound
	}
	err = s.Pool.QueryRow(ctx, `UPDATE recipe n SET image_path = $2, version = n.version + 1, updated_at = now()
		FROM recipe o WHERE n.id = $1 AND o.id = n.id RETURNING o.image_path`, id, name).Scan(&old)
	return old, notFound(err)
}

// RecipeImagePath returns the thumbnail file name of a recipe (ErrNotFound without image).
func (s *Store) RecipeImagePath(ctx context.Context, id string) (string, error) {
	if !validUUID(id) {
		return "", ErrNotFound
	}
	var p *string
	if err := s.Pool.QueryRow(ctx, `SELECT image_path FROM recipe WHERE id = $1`, id).Scan(&p); err != nil {
		return "", notFound(err)
	}
	if p == nil {
		return "", ErrNotFound
	}
	return *p, nil
}

// ChangeRecipeImage replaces the picture of a recipe with an uploaded one,
// stored as a thumbnail in dir. The previous file is deleted.
func (s *Store) ChangeRecipeImage(ctx context.Context, dir images.Dir, id string, upload io.Reader) (Recipe, error) {
	if !validUUID(id) {
		return Recipe{}, ErrNotFound
	}
	if _, err := s.RecipeImagePath(ctx, id); err != nil && err != ErrNotFound {
		return Recipe{}, err
	}
	data, hash, err := images.Thumbnail(upload)
	if errors.Is(err, images.ErrInvalid) {
		return Recipe{}, invalid("image refusée : " + strings.TrimPrefix(err.Error(), "invalid image: "))
	} else if err != nil {
		return Recipe{}, err
	}
	name, err := dir.Save(id, hash, data)
	if err != nil {
		return Recipe{}, err
	}
	old, err := s.SetRecipeImage(ctx, id, &name)
	if err != nil {
		dir.Remove(name)
		return Recipe{}, err
	}
	if old != nil && *old != name {
		dir.Remove(*old)
	}
	return s.Recipe(ctx, id)
}

// RemoveRecipeImage deletes the picture of a recipe.
func (s *Store) RemoveRecipeImage(ctx context.Context, dir images.Dir, id string) error {
	old, err := s.SetRecipeImage(ctx, id, nil)
	if err == nil && old != nil {
		dir.Remove(*old)
	}
	return err
}
