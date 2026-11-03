package admin

import (
	"fmt"
	"net/http"
	"runtime"
	"strconv"
	"strings"
	"time"

	"github.com/julienbrunet/mijote/server/internal/store"
	"github.com/julienbrunet/mijote/server/internal/sysinfo"
)

// System page (FR-ADM-05): instance figures, versions, resource usage of the
// server and of its host, database and image directory, and health checks.

// alert is a point the admin should look at.
type alert struct {
	Level string // "warn" or "info"
	Text  string
	Href  string
}

type systemPage struct {
	Counts       store.InstanceCounts
	Version      string
	GoVersion    string
	APIVersions  string
	Started      time.Time
	Uptime       string
	Versions     []store.ServerVersion
	Current      *store.ServerVersion
	DB           store.DatabaseInfo
	Process      sysinfo.Process
	CPURecent    string
	CPUAverage   string
	Host         sysinfo.Host
	HostUptime   string
	MemUsedPct   int
	Images       sysinfo.DirUsage
	ImageDir     string
	Disk         sysinfo.Disk
	Orphans      int // image files no recipe refers to
	Missing      int // recipe images missing from the directory
	Alerts       []alert
	PoolTotal    int32
	PoolIdle     int32
	PoolMax      int32
	LastPlanned  string
	LastPlanDays int
}

func (a *Admin) systemPage(w http.ResponseWriter, r *http.Request) {
	ctx := r.Context()
	today, err := a.Store.Today(ctx)
	if err != nil {
		a.fail(w, r, err)
		return
	}
	counts, err := a.Store.InstanceCounts(ctx, today)
	if err != nil {
		a.fail(w, r, err)
		return
	}
	db, err := a.Store.DatabaseInfo(ctx, 6)
	if err != nil {
		a.fail(w, r, err)
		return
	}
	versions, err := a.Store.ServerVersions(ctx, 10)
	if err != nil {
		a.fail(w, r, err)
		return
	}
	imgs, err := sysinfo.ReadDir(a.Images.Path)
	if err != nil {
		a.fail(w, r, err)
		return
	}
	p := systemPage{Counts: counts, Version: a.Version, GoVersion: runtime.Version(),
		APIVersions: strings.Join(a.APIVersions, ", "), Versions: versions, DB: db,
		Process: sysinfo.ReadProcess(), Host: sysinfo.ReadHost(), Images: imgs, ImageDir: a.Images.Path,
		Disk: sysinfo.ReadDisk(a.Images.Path)}
	for i := range versions {
		if versions[i].Version == a.Version {
			p.Current = &versions[i]
		}
	}
	if a.CPU != nil {
		p.Started = a.CPU.Started
		p.Uptime = duration(time.Since(a.CPU.Started))
		recent, avg := a.CPU.CPUPercent()
		p.CPURecent, p.CPUAverage = pct(recent), pct(avg)
	}
	if p.Host.Uptime > 0 {
		p.HostUptime = duration(p.Host.Uptime)
	}
	if p.Host.MemTotal > 0 {
		p.MemUsedPct = int((p.Host.MemTotal - p.Host.MemAvailable) * 100 / p.Host.MemTotal)
	}
	if st := a.Store.Pool.Stat(); st != nil {
		p.PoolTotal, p.PoolIdle, p.PoolMax = st.TotalConns(), st.IdleConns(), st.MaxConns()
	}
	referenced := map[string]bool{}
	for _, name := range db.ImagePaths {
		referenced[name] = true
		if !imgs.Names[name] {
			p.Missing++
		}
	}
	for name := range imgs.Names {
		if !referenced[name] {
			p.Orphans++
		}
	}
	if counts.LastPlannedDate != nil {
		p.LastPlanned = frDay(*counts.LastPlannedDate)
		p.LastPlanDays = int(counts.LastPlannedDate.Sub(today).Hours() / 24)
	}
	p.Alerts = alerts(p)
	a.render(w, r, "system", http.StatusOK, view{Title: "Système", Nav: "system", Data: p})
}

// alerts lists the health checks that need attention.
func alerts(p systemPage) []alert {
	var out []alert
	c := p.Counts
	if !c.AdminTwoFA {
		out = append(out, alert{"warn", "La double authentification du compte administrateur n'est pas activée.", "/admin/account"})
	}
	if c.Recipes < c.MinRecipes {
		out = append(out, alert{"warn", fmt.Sprintf("Seulement %d recette(s) active(s) : il en faut %d pour générer des menus.", c.Recipes, c.MinRecipes), "/admin/recipes"})
	}
	if c.LoginFailures24h > 0 {
		out = append(out, alert{"warn", fmt.Sprintf("%d échec(s) de connexion ou alerte(s) de sécurité ces dernières 24 heures.", c.LoginFailures24h), "/admin/journal?failures=1"})
	}
	if p.Disk.Total > 0 && p.Disk.UsedPct() >= 90 {
		out = append(out, alert{"warn", fmt.Sprintf("Le disque du dossier des images est plein à %d %%.", p.Disk.UsedPct()), ""})
	}
	if p.MemUsedPct >= 90 {
		out = append(out, alert{"warn", fmt.Sprintf("La mémoire de la machine est utilisée à %d %%.", p.MemUsedPct), ""})
	}
	if p.Missing > 0 {
		out = append(out, alert{"warn", fmt.Sprintf("%d image(s) de recette introuvable(s) dans le dossier des images (sauvegarde incomplète ?).", p.Missing), ""})
	}
	if c.UpcomingMeals == 0 {
		out = append(out, alert{"info", "Aucun repas planifié à venir.", "/admin/"})
	} else if p.LastPlanDays < 2 {
		out = append(out, alert{"info", "Les repas planifiés s'arrêtent le " + strings.ToLower(p.LastPlanned) + " : pensez à générer la suite.", "/admin/"})
	}
	if p.Orphans > 0 {
		out = append(out, alert{"info", fmt.Sprintf("%d fichier(s) du dossier des images ne sont liés à aucune recette.", p.Orphans), ""})
	}
	if c.RecipesWithoutImage > 0 {
		out = append(out, alert{"info", fmt.Sprintf("%d recette(s) active(s) sans image.", c.RecipesWithoutImage), "/admin/recipes"})
	}
	return out
}

// pct formats a CPU share in percent of one core.
func pct(v float64) string {
	if v < 0 {
		return "inconnu"
	}
	return strings.Replace(strconv.FormatFloat(v, 'f', 1, 64), ".", ",", 1) + " %"
}

// duration formats an uptime as "3 j 4 h", "2 h 05 min" or "12 min".
func duration(d time.Duration) string {
	d = d.Round(time.Minute)
	days, h, m := int(d.Hours())/24, int(d.Hours())%24, int(d.Minutes())%60
	switch {
	case days > 0:
		return fmt.Sprintf("%d j %d h", days, h)
	case h > 0:
		return fmt.Sprintf("%d h %02d min", h, m)
	}
	return fmt.Sprintf("%d min", m)
}

type statsPage struct {
	store.RecipeStats
	Period   string
	Periods  []statsPeriod
	DishOpts []store.Option
}

type statsPeriod struct{ Key, Label string }

var statsPeriods = []statsPeriod{{"30", "30 jours"}, {"90", "3 mois"}, {"365", "12 mois"}, {"all", "Depuis le début"}}

func (a *Admin) recipeStatsPage(w http.ResponseWriter, r *http.Request) {
	today, err := a.Store.Today(r.Context())
	if err != nil {
		a.fail(w, r, err)
		return
	}
	period := r.URL.Query().Get("period")
	var since *time.Time
	switch period {
	case "30", "90", "365":
		n, _ := strconv.Atoi(period)
		d := today.AddDate(0, 0, -n)
		since = &d
	case "all":
	default:
		period = "90"
		d := today.AddDate(0, 0, -90)
		since = &d
	}
	st, err := a.Store.RecipeStats(r.Context(), today, since)
	if err != nil {
		a.fail(w, r, err)
		return
	}
	a.render(w, r, "recipe_stats", http.StatusOK, view{Title: "Statistiques des recettes", Nav: "stats",
		Data: statsPage{RecipeStats: st, Period: period, Periods: statsPeriods, DishOpts: store.DishTypes}})
}
