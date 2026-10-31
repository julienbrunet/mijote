package admin

import (
	"html/template"
	"net/http"
	"regexp"
)

// downloadPage is where the app is downloaded and installed (bilingual project page).
const downloadPage = "https://julienbrunet.github.io/mijote/"

var inviteCodeRe = regexp.MustCompile(`^[A-Za-z0-9]{8,64}$`)

type invitePage struct {
	Server, Code string
	AppLink      template.URL // mijote://invite?... : opens the app when installed
	DownloadPage string
}

// invitePage is the public page behind the shared invitation link
// (https://server/invite/<code>). It never reads the database: it neither
// consumes the code nor tells whether it is valid, so it reveals nothing; the
// app finds out when the account is created.
func (a *Admin) invitePage(w http.ResponseWriter, r *http.Request) {
	code := r.PathValue("code")
	if !inviteCodeRe.MatchString(code) {
		a.render(w, r, "error", http.StatusNotFound, view{Title: "Invitation introuvable",
			Error: "Ce lien d'invitation n'est pas valide : demandez-en un nouveau à l'administrateur."})
		return
	}
	server := a.serverURL(r)
	w.Header().Set("X-Robots-Tag", "noindex, nofollow")
	a.render(w, r, "invite", http.StatusOK, view{Title: "Invitation Mijote", Data: invitePage{
		Server: server, Code: code, AppLink: template.URL(appInvitationLink(server, code)), DownloadPage: downloadPage}})
}
