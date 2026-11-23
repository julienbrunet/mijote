package web

import (
	"context"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

func TestFamilyPlannerRoutes(t *testing.T) {
	mux := http.NewServeMux()
	Routes(mux, func(context.Context) (bool, error) { return false, nil })
	for _, test := range []struct {
		path    string
		status  int
		content string
	}{
		{"/app", http.StatusPermanentRedirect, "/app/"},
		{"/app/", http.StatusOK, "Planning familial"},
		{"/app/planner.js", http.StatusOK, "/menu/confirm"},
		{"/app/planner.css", http.StatusOK, ".slots"},
		{"/app/inter-regular.woff2", http.StatusOK, "wOF2"},
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
