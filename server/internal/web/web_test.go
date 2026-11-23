package web

import (
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

func TestFamilyPlannerRoutes(t *testing.T) {
	mux := http.NewServeMux()
	Routes(mux)
	for _, test := range []struct {
		path    string
		status  int
		content string
	}{
		{"/app", http.StatusPermanentRedirect, "/app/"},
		{"/app/", http.StatusOK, "Planning familial"},
		{"/app/planner.js", http.StatusOK, "/menu/confirm"},
		{"/app/planner.css", http.StatusOK, ".slots"},
		{"/app/private", http.StatusNotFound, ""},
	} {
		t.Run(test.path, func(t *testing.T) {
			response := httptest.NewRecorder()
			mux.ServeHTTP(response, httptest.NewRequest(http.MethodGet, test.path, nil))
			if response.Code != test.status || !strings.Contains(response.Body.String(), test.content) {
				t.Fatalf("unexpected response for %s: %d", test.path, response.Code)
			}
		})
	}
}
