// Package web serves the family planner that uses the versioned API.
package web

import (
	"bytes"
	"context"
	"embed"
	"html/template"
	"net/http"
	"sync"
)

//go:embed shell.html index.html shell.js planner.css planner.js *.woff2 inter-LICENSE.txt
var assets embed.FS

var plannerHTML = sync.OnceValue(func() []byte {
	base := template.Must(ParseShell(template.New("planner")))
	page := template.Must(base.ParseFS(assets, "index.html"))
	data := struct {
		Title string
		Shell Shell
	}{Title: "Planning familial"}
	var buffer bytes.Buffer
	if err := page.ExecuteTemplate(&buffer, "shell", data); err != nil {
		panic(err)
	}
	return buffer.Bytes()
})

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
		w.Header().Set("Content-Type", "text/html; charset=utf-8")
		_, _ = w.Write(plannerHTML())
	})
	for _, file := range []string{"planner.css", "planner.js", "shell.js", "inter-regular.woff2", "inter-bold.woff2", "inter-LICENSE.txt"} {
		mux.HandleFunc("GET /app/"+file, func(w http.ResponseWriter, r *http.Request) {
			w.Header().Set("Cache-Control", "no-cache")
			http.ServeFileFS(w, r, assets, file)
		})
	}
}
