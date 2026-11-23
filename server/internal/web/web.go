// Package web serves the family planner that uses the versioned API.
package web

import (
	"embed"
	"net/http"
)

//go:embed index.html planner.css planner.js
var assets embed.FS

func Routes(mux *http.ServeMux) {
	mux.Handle("GET /app", http.RedirectHandler("/app/", http.StatusPermanentRedirect))
	for path, file := range map[string]string{"/app/{$}": "index.html", "/app/planner.css": "planner.css", "/app/planner.js": "planner.js"} {
		mux.HandleFunc("GET "+path, func(w http.ResponseWriter, r *http.Request) {
			w.Header().Set("Cache-Control", "no-cache")
			http.ServeFileFS(w, r, assets, file)
		})
	}
}
