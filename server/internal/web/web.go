// Package web serves the family planner that uses the versioned API.
package web

import (
	"context"
	"embed"
	"net/http"
)

//go:embed index.html planner.css planner.js *.woff2 inter-LICENSE.txt
var assets embed.FS

func Routes(mux *http.ServeMux, needsSetup func(context.Context) (bool, error)) {
	mux.Handle("GET /app", http.RedirectHandler("/app/", http.StatusPermanentRedirect))
	mux.HandleFunc("GET /app/{$}", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Cache-Control", "no-store")
		setup, err := needsSetup(r.Context())
		if err != nil {
			http.Error(w, "Vérification de l’installation indisponible.", http.StatusServiceUnavailable)
			return
		}
		if setup {
			http.Redirect(w, r, "/app/admin/setup", http.StatusSeeOther)
			return
		}
		http.ServeFileFS(w, r, assets, "index.html")
	})
	for _, file := range []string{"planner.css", "planner.js", "inter-regular.woff2", "inter-bold.woff2", "inter-LICENSE.txt"} {
		mux.HandleFunc("GET /app/"+file, func(w http.ResponseWriter, r *http.Request) {
			w.Header().Set("Cache-Control", "no-cache")
			http.ServeFileFS(w, r, assets, file)
		})
	}
}
