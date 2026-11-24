package web

import (
	"html/template"
	"strings"
	"unicode/utf8"
)

// Shell describes the navigation shared by the planner and the management pages.
type Shell struct {
	AdminName    string // present only after server-side administrator authentication
	AdminSection string
	Management   bool
	Wide         bool
}

func (s Shell) Initial() string {
	initial, _ := utf8.DecodeRuneInString(s.AdminName)
	return strings.ToUpper(string(initial))
}

// NavItem groups management pages shown as tabs within one menu section.
type NavItem struct {
	Key, Label, Href string
	Subs             []NavItem
}

var managementSections = []NavItem{
	{Key: "home", Label: "Repas du foyer", Href: "/app/admin/"},
	{Key: "recipes", Label: "Gérer les recettes", Href: "/app/admin/recipes", Subs: []NavItem{
		{Key: "recipes", Label: "Recettes", Href: "/app/admin/recipes"},
		{Key: "tags", Label: "Étiquettes", Href: "/app/admin/tags"},
		{Key: "stats", Label: "Statistiques", Href: "/app/admin/recipes/stats"},
	}},
	{Key: "users", Label: "Utilisateurs", Href: "/app/admin/accounts", Subs: []NavItem{
		{Key: "accounts", Label: "Comptes", Href: "/app/admin/accounts"},
		{Key: "invitations", Label: "Invitations", Href: "/app/admin/invitations"},
		{Key: "sessions", Label: "Sessions", Href: "/app/admin/sessions"},
		{Key: "journal", Label: "Journal des connexions", Href: "/app/admin/journal"},
	}},
	{Key: "system", Label: "Système", Href: "/app/admin/system"},
	{Key: "profile", Label: "Profil admin", Href: "/app/admin/account", Subs: []NavItem{
		{Key: "account", Label: "Mon compte", Href: "/app/admin/account"},
		{Key: "tokens", Label: "Jetons d'API", Href: "/app/admin/tokens"},
	}},
}

func (Shell) Sections() []NavItem { return managementSections }

// ManagementSection finds the menu section that owns a page.
func ManagementSection(page string) NavItem {
	for _, section := range managementSections {
		if section.Key == page {
			return section
		}
		for _, sub := range section.Subs {
			if sub.Key == page {
				return section
			}
		}
	}
	return NavItem{}
}

// ParseShell attaches the application shell to the caller's page templates.
func ParseShell(t *template.Template) (*template.Template, error) {
	return t.ParseFS(assets, "shell.html")
}
