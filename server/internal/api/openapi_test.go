package api

import (
	"bufio"
	"net/http"
	"os"
	"slices"
	"strings"
	"testing"
)

// recorder collects the route patterns registered by Routes.
type recorder struct{ patterns []string }

func (r *recorder) Handle(pattern string, _ http.Handler) { r.patterns = append(r.patterns, pattern) }
func (r *recorder) HandleFunc(pattern string, _ func(http.ResponseWriter, *http.Request)) {
	r.patterns = append(r.patterns, pattern)
}

// specOperations reads the "METHOD /path" operations of an OpenAPI YAML file
// written with two-space indentation (paths at 2 spaces, methods at 4).
func specOperations(t *testing.T, name string) []string {
	t.Helper()
	f, err := os.Open(name)
	if err != nil {
		t.Fatal(err)
	}
	defer f.Close()
	var ops []string
	inPaths, path := false, ""
	sc := bufio.NewScanner(f)
	for sc.Scan() {
		line := sc.Text()
		switch {
		case line == "paths:":
			inPaths = true
		case !inPaths || line == "" || strings.HasPrefix(strings.TrimSpace(line), "#"):
		case !strings.HasPrefix(line, " "):
			inPaths = false // next top-level key
		case strings.HasPrefix(line, "  /") && strings.HasSuffix(line, ":"):
			path = strings.TrimSuffix(strings.TrimSpace(line), ":")
		case strings.HasPrefix(line, "    ") && !strings.HasPrefix(line, "     "):
			method := strings.TrimSuffix(strings.TrimSpace(line), ":")
			if slices.Contains([]string{"get", "put", "post", "delete", "patch", "head"}, method) {
				ops = append(ops, strings.ToUpper(method)+" "+path)
			}
		}
	}
	if err := sc.Err(); err != nil {
		t.Fatal(err)
	}
	return ops
}

// TestOpenAPIMatchesRoutes keeps server/openapi.yaml in step with the routes.
func TestOpenAPIMatchesRoutes(t *testing.T) {
	var rec recorder
	(&API{}).Routes(&rec)
	// Registered outside package api (internal/server).
	routes := append(rec.patterns, "GET /healthz", "GET /api/versions")
	spec := specOperations(t, "../../openapi.yaml")
	if len(spec) == 0 {
		t.Fatal("no operation found in openapi.yaml")
	}
	for _, r := range routes {
		if !slices.Contains(spec, r) {
			t.Errorf("route %q is not documented in openapi.yaml", r)
		}
	}
	for _, op := range spec {
		if !slices.Contains(routes, op) {
			t.Errorf("openapi.yaml documents %q, which the server does not serve", op)
		}
	}
}
