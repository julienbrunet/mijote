package store

import (
	"context"
	"errors"
	"time"

	"github.com/jackc/pgx/v5"
)

var ErrWishQuota = errors.New("wish_quota_reached")
var ErrWishRole = errors.New("wish_readonly_only")
var ErrNoWishRecipients = errors.New("no_menu_creators")

type WishStatus struct {
	Requested   bool      `json:"requested"`
	WeeklyLimit int       `json:"weeklyLimit"`
	Used        int       `json:"used"`
	Remaining   int       `json:"remaining"`
	ResetsAt    time.Time `json:"resetsAt"`
}

// Monday midnight through next Monday in the family's timezone, including DST.
func wishWeek(now time.Time, timezone string) (time.Time, time.Time, error) {
	loc, err := time.LoadLocation(timezone)
	if err != nil {
		return time.Time{}, time.Time{}, err
	}
	local := now.In(loc)
	day := time.Date(local.Year(), local.Month(), local.Day(), 0, 0, 0, 0, loc)
	start := day.AddDate(0, 0, -(int(day.Weekday())+6)%7)
	return start, start.AddDate(0, 0, 7), nil
}

func wishStatus(ctx context.Context, q querier, userID, recipeID string, now time.Time) (WishStatus, error) {
	var status WishStatus
	var timezone string
	if err := q.QueryRow(ctx, `SELECT recipe_wishes_per_week, timezone FROM family_settings`).Scan(&status.WeeklyLimit, &timezone); err != nil {
		return status, err
	}
	start, end, err := wishWeek(now, timezone)
	if err != nil {
		return status, err
	}
	status.ResetsAt = end
	if err := q.QueryRow(ctx, `SELECT count(*) FROM recipe_wish WHERE requester_id::text=$1 AND created_at >= $2 AND created_at < $3`, userID, start, end).Scan(&status.Used); err != nil {
		return status, err
	}
	status.Remaining = max(0, status.WeeklyLimit-status.Used)
	err = q.QueryRow(ctx, `SELECT EXISTS (
   SELECT 1 FROM recipe_wish w JOIN recipe_wish_notification n ON n.wish_id=w.id
   JOIN app_user recipient ON recipient.id=n.recipient_id
   WHERE w.requester_id::text=$1 AND w.recipe_id::text=$2
   AND recipient.disabled_at IS NULL AND recipient.role IN ('member','admin'))`, userID, recipeID).Scan(&status.Requested)
	return status, err
}

func (s *Store) RecipeWishStatus(ctx context.Context, userID, recipeID string) (WishStatus, error) {
	var exists bool
	if err := s.Pool.QueryRow(ctx, `SELECT EXISTS(SELECT 1 FROM recipe WHERE id::text=$1 AND archived_at IS NULL)`, recipeID).Scan(&exists); err != nil {
		return WishStatus{}, err
	}
	if !exists {
		return WishStatus{}, ErrNotFound
	}
	return wishStatus(ctx, s.Pool, userID, recipeID, s.now())
}

// RequestRecipe serializes quota checks with settings changes. Retries of a pending
// wish do not create another notification or consume another unit of the weekly quota.
func (s *Store) RequestRecipe(ctx context.Context, userID, recipeID string) (WishStatus, bool, error) {
	var result WishStatus
	created := false
	err := s.tx(ctx, func(tx pgx.Tx) error {
		// Shared lock order: settings first, then requester. This also prevents races
		// between different recipes requested simultaneously by the same account.
		if _, err := tx.Exec(ctx, `SELECT 1 FROM family_settings FOR UPDATE`); err != nil {
			return err
		}
		var role, name string
		if err := tx.QueryRow(ctx, `SELECT role::text,display_name FROM app_user WHERE id::text=$1 AND disabled_at IS NULL FOR UPDATE`, userID).Scan(&role, &name); err != nil {
			return notFound(err)
		}
		if role != RoleReadonly {
			return ErrWishRole
		}
		var title string
		if err := tx.QueryRow(ctx, `SELECT title FROM recipe WHERE id::text=$1 AND archived_at IS NULL`, recipeID).Scan(&title); err != nil {
			return notFound(err)
		}
		var err error
		result, err = wishStatus(ctx, tx, userID, recipeID, s.now())
		if err != nil {
			return err
		}
		if result.Requested {
			return nil
		}
		if result.Remaining == 0 {
			return ErrWishQuota
		}
		var recipients int
		if err := tx.QueryRow(ctx, `SELECT count(*) FROM app_user WHERE role IN ('admin','member') AND disabled_at IS NULL`).Scan(&recipients); err != nil {
			return err
		}
		if recipients == 0 {
			return ErrNoWishRecipients
		}
		var id string
		if err := tx.QueryRow(ctx, `INSERT INTO recipe_wish(requester_id,recipe_id,requester_name,recipe_title,created_at)
    VALUES($1,$2,$3,$4,$5) RETURNING id::text`, userID, recipeID, name, title, s.now()).Scan(&id); err != nil {
			return err
		}
		tag, err := tx.Exec(ctx, `INSERT INTO recipe_wish_notification(wish_id,recipient_id)
            SELECT $1,id FROM app_user WHERE role IN ('admin','member') AND disabled_at IS NULL`, id)
		if err != nil {
			return err
		}
		if tag.RowsAffected() == 0 {
			return ErrNoWishRecipients
		}
		result.Requested = true
		result.Used++
		result.Remaining = max(0, result.WeeklyLimit-result.Used)
		created = true
		return nil
	})
	return result, created, err
}

func (s *Store) WishLimit(ctx context.Context) (int, error) {
	var limit int
	err := s.Pool.QueryRow(ctx, `SELECT recipe_wishes_per_week FROM family_settings`).Scan(&limit)
	return limit, err
}
func (s *Store) SetWishLimit(ctx context.Context, limit int) error {
	if limit < 1 || limit > 100 {
		return invalid("la limite doit être comprise entre 1 et 100 demandes par semaine")
	}
	_, err := s.Pool.Exec(ctx, `UPDATE family_settings SET recipe_wishes_per_week=$1, updated_at=$2,version=version+1`, limit, s.now())
	return err
}

type RecipeNotification struct {
	ID              string     `json:"id"`
	RequesterID     string     `json:"requesterId"`
	RequesterName   string     `json:"requesterName"`
	RecipeID        string     `json:"recipeId"`
	RecipeTitle     string     `json:"recipeTitle"`
	RecipeAvailable bool       `json:"recipeAvailable"`
	CreatedAt       time.Time  `json:"createdAt"`
	ReadAt          *time.Time `json:"readAt"`
}
type NotificationPage struct {
	Items       []RecipeNotification `json:"items"`
	UnreadCount int                  `json:"unreadCount"`
	HasMore     bool                 `json:"hasMore"`
}

func (s *Store) RecipeNotifications(ctx context.Context, userID string, offset, limit int) (NotificationPage, error) {
	page := NotificationPage{Items: []RecipeNotification{}}
	rows, err := s.Pool.Query(ctx, `SELECT n.id::text,w.requester_id::text,w.requester_name,w.recipe_id::text,w.recipe_title,
   r.archived_at IS NULL,w.created_at,n.read_at FROM recipe_wish_notification n
   JOIN recipe_wish w ON w.id=n.wish_id JOIN recipe r ON r.id=w.recipe_id
   WHERE n.recipient_id::text=$1 ORDER BY w.created_at DESC,n.id DESC LIMIT $2 OFFSET $3`, userID, limit+1, offset)
	if err != nil {
		return page, err
	}
	list, err := pgx.CollectRows(rows, func(row pgx.CollectableRow) (RecipeNotification, error) {
		var n RecipeNotification
		err := row.Scan(&n.ID, &n.RequesterID, &n.RequesterName, &n.RecipeID, &n.RecipeTitle, &n.RecipeAvailable, &n.CreatedAt, &n.ReadAt)
		return n, err
	})
	if err != nil {
		return page, err
	}
	if len(list) > limit {
		page.HasMore = true
		list = list[:limit]
	}
	if list != nil {
		page.Items = list
	}
	err = s.Pool.QueryRow(ctx, `SELECT count(*) FROM recipe_wish_notification WHERE recipient_id::text=$1 AND read_at IS NULL`, userID).Scan(&page.UnreadCount)
	return page, err
}
func (s *Store) ReadRecipeNotification(ctx context.Context, userID, id string) error {
	tag, err := s.Pool.Exec(ctx, `UPDATE recipe_wish_notification SET read_at=coalesce(read_at,$3) WHERE id::text=$1 AND recipient_id::text=$2`, id, userID, s.now())
	if err == nil && tag.RowsAffected() == 0 {
		return ErrNotFound
	}
	return err
}
func (s *Store) DeleteRecipeNotification(ctx context.Context, userID, id string) error {
	tag, err := s.Pool.Exec(ctx, `DELETE FROM recipe_wish_notification WHERE id::text=$1 AND recipient_id::text=$2`, id, userID)
	if err == nil && tag.RowsAffected() == 0 {
		return ErrNotFound
	}
	return err
}
