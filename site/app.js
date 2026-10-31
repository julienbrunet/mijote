// Language switch and download links (latest GitHub releases, fetched in the browser so the
// page never needs a rebuild when a new version is published).
(function () {
  var REPO = "julienbrunet/mijote";
  var root = document.documentElement;

  function setLang(lang, save) {
    root.setAttribute("data-lang", lang);
    root.lang = lang;
    document.querySelectorAll("[data-set-lang]").forEach(function (b) {
      b.setAttribute("aria-pressed", String(b.getAttribute("data-set-lang") === lang));
    });
    if (save) { try { localStorage.setItem("mijote-lang", lang); } catch (e) {} }
  }
  document.querySelectorAll("[data-set-lang]").forEach(function (b) {
    b.addEventListener("click", function () { setLang(b.getAttribute("data-set-lang"), true); });
  });
  setLang(root.getAttribute("data-lang") || "en", false);

  function el(tag, text) { var e = document.createElement(tag); if (text) e.textContent = text; return e; }

  function show(cardId, release) {
    var card = document.getElementById(cardId);
    var version = card.querySelector("[data-version]");
    var list = card.querySelector("[data-files]");
    version.textContent = "";
    if (!release) {
      version.innerHTML = '<span data-l="en">No release published yet.</span><span data-l="fr">Aucune version publiée pour l\'instant.</span>';
      return;
    }
    version.textContent = release.name || release.tag_name;
    release.assets.forEach(function (a) {
      var li = el("li");
      var link = el("a", a.name);
      link.href = a.browser_download_url;
      li.appendChild(link);
      list.appendChild(li);
    });
  }

  fetch("https://api.github.com/repos/" + REPO + "/releases?per_page=100", { headers: { Accept: "application/vnd.github+json" } })
    .then(function (r) { if (!r.ok) throw new Error(r.status); return r.json(); })
    .then(function (releases) {
      function latest(prefix) {
        // Highest version, not the first listed: a re-published old tag can come first.
        function ver(r) { return r.tag_name.slice(prefix.length).split(".").map(Number); }
        function newer(a, b) { var x = ver(a), y = ver(b); for (var i = 0; i < 3; i++) if (x[i] !== y[i]) return x[i] > y[i]; return false; }
        return releases.filter(function (r) { return !r.draft && !r.prerelease && r.tag_name.indexOf(prefix) === 0; })
          .reduce(function (best, r) { return !best || newer(r, best) ? r : best; }, undefined);
      }
      show("dl-android", latest("android-v"));
      show("dl-server", latest("server-v"));
    })
    .catch(function () {
      // API unreachable or rate limited: the "All releases" link below stays usable.
      document.querySelectorAll("[data-version]").forEach(function (v) { v.textContent = ""; });
    });
})();
