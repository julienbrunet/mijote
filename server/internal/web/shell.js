"use strict";

(() => {
    const toggle = document.getElementById("nav-toggle");
    const nav = document.getElementById("nav");

    function setOpen(open) {
        toggle.setAttribute("aria-expanded", String(open));
        toggle.setAttribute("aria-label", open ? "Fermer le menu" : "Ouvrir le menu");
    }

    toggle.addEventListener("click", () => setOpen(toggle.getAttribute("aria-expanded") !== "true"));
    nav.addEventListener("close-menu", () => setOpen(false));
    nav.addEventListener("click", event => {
        if (event.target.closest("a[href]")) setOpen(false);
    });
    document.addEventListener("keydown", event => {
        if (event.key === "Escape" && !document.querySelector("dialog[open]") && toggle.getAttribute("aria-expanded") === "true") {
            setOpen(false);
            toggle.focus();
        }
    });
})();
