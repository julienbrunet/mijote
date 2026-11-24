"use strict";

const main = document.getElementById("main");
const nav = document.getElementById("nav");
const profile = document.getElementById("profile");
const panel = document.getElementById("panel");
const statusBox = document.getElementById("status");
const loginTemplate = main.firstElementChild.cloneNode(true);
const types = { vegetarian: "Végé", fish: "Poisson", meat: "Viande" };
const seasons = { spring: "Printemps", summer: "Été", autumn: "Automne", winter: "Hiver" };
const meals = { lunch: "Midi", dinner: "Soir" };
const dateFormatter = new Intl.DateTimeFormat("fr-FR", { dateStyle: "full", timeZone: "UTC" });
const weekdayFormatter = new Intl.DateTimeFormat("fr-FR", { weekday: "long", timeZone: "UTC" });
const monthFormatter = new Intl.DateTimeFormat("fr-FR", { day: "numeric", month: "short", timeZone: "UTC" });
const weekLayout = matchMedia("(min-width: 960px)");
const roleLabels = { admin: "Administrateur", member: "Membre du foyer", readonly: "Lecture seule" };
const icons = {
    pencil: "M4 16l-1 5 5-1L20 8l-4-4z M14 6l4 4",
    eye: "M2 12s3.5-6 10-6 10 6 10 6-3.5 6-10 6S2 12 2 12Z M15 12a3 3 0 1 1-6 0 3 3 0 0 1 6 0Z",
    left: "m15 5-7 7 7 7", right: "m9 5 7 7-7 7", up: "m5 15 7-7 7 7", down: "m5 9 7 7 7-7",
    plus: "M12 5v14 M5 12h14", lunch: "M12 3v2 M12 19v2 M3 12h2 M19 12h2 M5.6 5.6 7 7 M17 17l1.4 1.4 M5.6 18.4 7 17 M17 7l1.4-1.4 M16 12a4 4 0 1 1-8 0 4 4 0 0 1 8 0Z",
    dinner: "M20 15A9 9 0 0 1 9 4a9 9 0 1 0 11 11Z",
};
const slotKey = (date, meal) => date + "|" + meal;
const mealTitle = slot => slot?.title || slot?.recipe?.title || "Créneau libre";
const recipeMinutes = recipe => recipe.totalMinutes ?? recipe.prepMinutes ?? recipe.minutes;
const recipePath = id => "/recipes/" + encodeURIComponent(id);
const writable = () => user?.role === "admin" || user?.role === "member";
let session = null, refreshing = null, user = null, today = null;
let first = null, last = null, slots = new Map(), page = "agenda", proposal = null;
let agendaWeek = null, weekRequest = 0;
let edgeObserver, todayObserver, loadingEdge = false, dragSource = null, statusTimer, dialogVersion = 0;
let authGeneration = 0;
const images = new Map();
const imageObserver = new IntersectionObserver(entries => {
    for (const entry of entries) {
        if (!entry.isIntersecting) continue;
        imageObserver.unobserve(entry.target);
        loadImage(entry.target);
    }
}, { rootMargin: "200px" });

function node(tag, attributes, ...children) {
    const element = document.createElement(tag);
    for (const [name, value] of Object.entries(attributes || {})) {
        if (value == null || value === false) continue;
        if (name.startsWith("on") && typeof value === "function") element.addEventListener(name.slice(2), value);
        else element.setAttribute(name, value === true ? "" : value);
    }
    for (const child of children.flat()) if (child != null) element.append(child);
    return element;
}

function button(label, action, className = "", disabled = false) {
    return node("button", { type: "button", class: className, disabled, onclick: action }, label);
}

function icon(name) {
    const svg = document.createElementNS("http://www.w3.org/2000/svg", "svg");
    svg.setAttribute("viewBox", "0 0 24 24");
    svg.setAttribute("aria-hidden", "true");
    const path = document.createElementNS(svg.namespaceURI, "path");
    path.setAttribute("d", icons[name]);
    svg.append(path);
    return svg;
}

function iconButton(label, symbol, action, className = "") {
    const control = button(icon(symbol), action, "icon-button " + className);
    control.setAttribute("aria-label", label);
    control.title = label;
    return control;
}

function notice(message, error = false) {
    statusBox.textContent = message;
    statusBox.classList.toggle("error", error);
    statusBox.hidden = false;
    clearTimeout(statusTimer);
    statusTimer = setTimeout(() => { statusBox.hidden = true; }, 4000);
}

class ApiError extends Error {
    constructor(status, body) {
        const messages = {
            invalid_credentials: "Identifiant ou mot de passe incorrect.",
            menu_changed: "Le planning a changé. Rouvrez le repas ou recalculez la proposition.",
            unauthorized: "Votre session a expiré. Reconnectez-vous.",
            too_many_requests: "Trop de tentatives. Réessayez dans un instant.",
            not_found: "Cet élément n’est plus disponible.",
        };
        super(messages[body?.error] || body?.message || "Le serveur n’a pas répondu comme prévu.");
        this.status = status;
        this.code = body?.error;
    }
}

async function request(path, options = {}, authenticated = true, retry = true) {
    const generation = authGeneration;
    const bearer = authenticated ? session?.accessToken : null;
    if (authenticated && !bearer) throw new Error("Connectez-vous pour continuer.");
    const headers = { Accept: options.image ? "image/*" : "application/json" };
    const webSession = path.startsWith("/app/session/");
    if (webSession) headers["X-Mijote-Web"] = "1";
    if (bearer) headers.Authorization = "Bearer " + bearer;
    if (options.body != null) headers["Content-Type"] = "application/json";
    const response = await fetch(webSession ? path : "/api/v1" + path, {
        method: options.method || "GET", headers, cache: "no-store",
        body: options.body == null ? undefined : JSON.stringify(options.body),
    });
    if (authenticated && generation !== authGeneration) throw new Error("La session a changé.");
    if (response.status === 401 && authenticated && retry && session) {
        if (session.accessToken === bearer) {
            if (!refreshing) {
                const owner = session;
                const pending = resumeSession().then(pair => {
                    if (session !== owner) return;
                    if (pair.user.id !== owner.user.id) {
                        showLogin();
                        throw new Error("Le compte a changé. Reconnectez-vous.");
                    }
                    session = pair;
                })
                    .catch(error => {
                        if (session === owner && error.status === 401) showLogin();
                        throw error;
                    }).finally(() => { if (refreshing === pending) refreshing = null; });
                refreshing = pending;
            }
            await refreshing;
        }
        if (generation !== authGeneration) throw new Error("La session a changé.");
        return request(path, options, authenticated, false);
    }
    if (!response.ok) throw new ApiError(response.status, await response.json().catch(() => null));
    if (options.image) return response.blob();
    return response.status === 204 ? null : response.json();
}

function resumeSession() {
    const refresh = () => request("/app/session/refresh", { method: "POST" }, false);
    // A token rotates once per request. Tabs on this origin share the cookie jar.
    return navigator.locks ? navigator.locks.request("mijote-web-session", refresh) : refresh();
}

const range = (from, to) => request("/menu?from=" + from + "&to=" + to);
const catalog = async (query = "") => {
    const data = await request("/recipes?limit=500&q=" + encodeURIComponent(query));
    return data.recipes || [];
};
const post = (path, body) => request(path, { method: "POST", body });
const dateOf = iso => new Date(iso + "T00:00:00Z");
const shiftDate = (iso, days) => new Date(dateOf(iso).getTime() + days * 86400000).toISOString().slice(0, 10);
const dayDistance = (a, b) => Math.round((dateOf(a) - dateOf(b)) / 86400000);
const weekStart = iso => shiftDate(iso, -((dateOf(iso).getUTCDay() + 6) % 7));
const datesIn = (from, to) => Array.from({ length: dayDistance(to, from) + 1 }, (_, day) => shiftDate(from, day));
const dateLabel = iso => {
    const label = dateFormatter.format(dateOf(iso));
    return label.charAt(0).toUpperCase() + label.slice(1);
};

function bindLogin() {
    document.getElementById("login").addEventListener("submit", async event => {
        event.preventDefault();
        const form = event.currentTarget;
        const fields = new FormData(form);
        const submit = form.querySelector("button");
        submit.disabled = true;
        try {
            session = await request("/app/session/login", {
                method: "POST",
                body: { username: fields.get("username").trim(), password: fields.get("password"), deviceName: "Planning web" },
            }, false);
            authGeneration++;
            user = session.user;
            form.reset();
            showNavigation();
            await openInitialView();
        } catch (error) {
            notice(error.message, true);
            if (user) renderAgenda();
        } finally { submit.disabled = false; }
    });
}

function showLogin() {
    authGeneration++;
    refreshing = null;
    session = user = today = first = last = proposal = dragSource = agendaWeek = null;
    slots = new Map();
    images.clear();
    page = "agenda";
    edgeObserver?.disconnect();
    todayObserver?.disconnect();
    closePanel();
    document.body.classList.remove("signed-in");
    nav.dispatchEvent(new Event("close-menu"));
    nav.querySelector(".management-nav")?.remove();
    profile.replaceChildren();
    unobserveArt(main);
    main.replaceChildren(loginTemplate.cloneNode(true));
    bindLogin();
}

function showNavigation() {
    document.body.classList.add("signed-in");
    for (const item of nav.querySelectorAll("[data-write]")) item.hidden = !writable();
    nav.querySelector(".management-nav")?.remove();
    if (user.role === "admin") nav.append(document.getElementById("management-menu").content.cloneNode(true));
    const name = user.displayName || user.username;
    profile.replaceChildren(node("a", { class: "profile-link", href: "/app/?view=account", "data-page": "account" },
        node("span", { class: "avatar", "aria-hidden": "true" }, Array.from(name)[0].toUpperCase()),
        node("span", {}, node("strong", {}, name), node("small", {}, roleLabels[user.role]))));
    markNavigation();
}

function markNavigation() {
    for (const item of nav.querySelectorAll("[data-page]")) {
        if (item.dataset.page === page) item.setAttribute("aria-current", "page");
        else item.removeAttribute("aria-current");
    }
}

async function showFamilyView(destination) {
    switch (destination) {
        case "recipes": openRecipes(); break;
        case "account": openAccount(); break;
        case "notifications": if (writable()) await openNotices(); break;
        case "compose":
            if (!writable() || !today || proposal) break;
            if (page !== "agenda") await openAgenda();
            composeMenu();
            break;
        case "agenda": await openAgenda(); break;
    }
}

async function openInitialView() {
    const destination = new URLSearchParams(location.search).get("view");
    await openAgenda(true);
    if (destination) await showFamilyView(destination);
}

for (const container of [nav, profile]) {
    container.addEventListener("click", event => {
        const link = event.target.closest("a[data-page]");
        if (!user || !link || event.button !== 0 || event.ctrlKey || event.metaKey || event.shiftKey || event.altKey) return;
        event.preventDefault();
        const destination = link.dataset.page;
        if (["recipes", "account", "agenda"].includes(destination)) history.replaceState(null, "", link.href);
        showFamilyView(destination).catch(error => notice(error.message, true));
    });
}

function applyMenu(menu, from, to) {
    today = menu.today;
    for (const [key, slot] of slots) if (slot.date >= from && slot.date <= to) slots.delete(key);
    for (const slot of menu.slots || []) slots.set(slotKey(slot.date, slot.meal), slot);
}

async function openAgenda(initial = false) {
    page = "agenda";
    markNavigation();
    closePanel();
    if (!today || initial) {
        const owner = user;
        const current = await request("/menu");
        if (user !== owner || page !== "agenda") return;
        const from = shiftDate(current.today, -7);
        const to = shiftDate(current.today, 13);
        const menu = await range(from, to);
        if (user !== owner || page !== "agenda") return;
        first = from;
        last = to;
        applyMenu(menu, from, to);
        agendaWeek = weekStart(today);
    }
    renderAgenda();
    if (!proposal && !weekLayout.matches) jumpToday();
    else window.scrollTo(0, 0);
}

async function openWeek(date) {
    const from = weekStart(date), to = shiftDate(from, 6);
    const owner = user, current = ++weekRequest;
    const menu = await range(from, to);
    if (current !== weekRequest || user !== owner || page !== "agenda" || proposal) return;
    applyMenu(menu, from, to);
    agendaWeek = from;
    renderAgenda();
}

weekLayout.addEventListener("change", () => {
    if (!user || page !== "agenda") return;
    renderAgenda();
    if (weekLayout.matches || proposal) window.scrollTo(0, 0);
    else jumpToday();
});

function jumpToday() {
    document.getElementById("day-" + today)?.scrollIntoView({ block: "start" });
}

async function extend(before) {
    if (loadingEdge || !today || proposal || page !== "agenda") return;
    loadingEdge = true;
    const from = before ? shiftDate(first, -7) : shiftDate(last, 1);
    const to = before ? shiftDate(first, -1) : shiftDate(last, 7);
    const anchor = before ? first : null;
    const top = anchor && document.getElementById("day-" + anchor)?.getBoundingClientRect().top;
    const owner = user;
    try {
        const menu = await range(from, to);
        if (user !== owner || page !== "agenda" || proposal || weekLayout.matches) return;
        applyMenu(menu, from, to);
        if (before) first = from; else last = to;
        renderAgenda();
        if (anchor && typeof top === "number") {
            const offset = document.getElementById("day-" + anchor).getBoundingClientRect().top - top;
            window.scrollBy(0, offset);
        }
    } catch (error) { notice(error.message, true); }
    finally { loadingEdge = false; }
}

function currentSlot(date, meal) {
    return proposal ? proposal.slots.find(slot => slot.date === date && slot.meal === meal) : slots.get(slotKey(date, meal));
}

function weekLabel(from, to) {
    return monthFormatter.formatRange(dateOf(from), dateOf(to));
}

function showWeekPicker() {
    let selected = agendaWeek;
    showPanel("Aller à une date", dateControl("Date du planning", selected, null, null, date => { selected = date; }),
        button("Afficher la semaine", event => run(event.currentTarget, async () => {
            await openWeek(selected);
            closePanel();
        }), "primary"));
}

function weekToolbar() {
    const previous = iconButton("Semaine précédente", "left", event => run(event.currentTarget,
        () => openWeek(shiftDate(agendaWeek, -7))));
    const next = iconButton("Semaine suivante", "right", event => run(event.currentTarget,
        () => openWeek(shiftDate(agendaWeek, 7))));
    return node("div", { class: "week-toolbar" },
        node("div", { class: "actions" }, previous, node("strong", { class: "week-period", "aria-live": "polite" },
            weekLabel(agendaWeek, shiftDate(agendaWeek, 6))), next),
        node("div", { class: "actions" }, agendaWeek !== weekStart(today) ? button("Cette semaine", event =>
            run(event.currentTarget, () => openWeek(today)), "quiet") : null,
        button("Aller à une date", showWeekPicker)));
}

function renderWeekBoard(dates) {
    const headers = dates.map(date => node("th", { scope: "col", class: date === today ? "is-today" : null,
        "aria-label": dateLabel(date), id: "day-" + date },
        node("span", {}, weekdayFormatter.format(dateOf(date))),
        node("strong", {}, dateOf(date).getUTCDate()),
        date === today ? node("small", {}, "Aujourd’hui") : null));
    const services = Object.entries(meals).map(([meal, label]) => node("tr", {},
        node("th", { scope: "row" }, icon(meal), label),
        dates.map(date => node("td", { "data-date": date, "data-meal": meal }, mealCard(date, meal)))));
    return node("div", { class: "week-board card" }, node("table", { class: "week-grid", "aria-label":
        "Repas du " + weekLabel(dates[0], dates.at(-1)) },
        node("colgroup", {}, node("col", { class: "service-column" }), node("col", { span: dates.length })),
        node("thead", {}, node("tr", {}, node("th", { scope: "col", "aria-label": "Créneau" }), headers)),
        node("tbody", {}, services)));
}

function renderDay(date) {
    const header = node("div", { class: "day-head" }, node("h2", {}, dateLabel(date)),
        date === today ? node("span", { class: "badge" }, "Aujourd’hui") : null);
    return node("section", { class: date === today ? "day today" : "day", id: "day-" + date },
        node("div", { class: "day-inner" }, header, node("div", { class: "slots" },
            mealCard(date, "lunch"), mealCard(date, "dinner"))));
}

function renderAgenda() {
    if (page !== "agenda") return;
    edgeObserver?.disconnect();
    todayObserver?.disconnect();
    unobserveArt(main);
    const desktop = weekLayout.matches;
    const title = proposal ? "Proposition de menu" : desktop ? "Menu de la semaine" : "Menus";
    const header = node("header", { class: proposal ? "page-heading agenda-heading draft-heading" : "page-heading agenda-heading" }, node("h1", {}, title));
    if (proposal) header.append(button("Quitter la proposition", () => {
        if (window.confirm("Quitter la proposition ?")) {
            proposal = null;
            renderAgenda();
            if (!desktop) jumpToday();
        }
    }));
    else if (today && writable()) header.append(button([icon("plus"), "Composer un menu"], composeMenu, "primary"));
    main.replaceChildren(header);
    if (!today || !first || !last) {
        main.append(button("Charger le planning", () => openAgenda(true).catch(error => notice(error.message, true))));
        return;
    }

    if (desktop) {
        agendaWeek ??= weekStart(today);
        if (!proposal) main.append(weekToolbar(), renderWeekBoard(datesIn(agendaWeek, shiftDate(agendaWeek, 6))));
        else {
            const dates = datesIn(proposal.from, proposal.to);
            main.append(node("p", { class: "draft-note" }, "Brouillon privé · " + weekLabel(proposal.from, proposal.to)));
            for (let start = 0; start < dates.length; start += 7) main.append(renderWeekBoard(dates.slice(start, start + 7)));
        }
    } else {
        const days = node("div", { class: "agenda-days" },
            !proposal ? edge(true) : null,
            datesIn(proposal?.from || first, proposal?.to || last).map(renderDay),
            !proposal ? edge(false) : null);
        main.append(days);
        if (!proposal) observeAgenda(days);
    }
    if (proposal) main.append(node("div", { class: "publish" },
        button("Valider cette proposition", event => run(event.currentTarget, publishProposal), "primary")));
}

function observeAgenda(days) {
    const returnToday = button("Aujourd’hui", jumpToday, "jump-today");
    const currentDay = document.getElementById("day-" + today);
    const headerHeight = document.querySelector(".sidebar").offsetHeight;
    returnToday.hidden = true;
    main.append(returnToday);
    todayObserver = new IntersectionObserver(([entry]) => {
        returnToday.hidden = entry.intersectionRatio >= 1;
        const above = entry.boundingClientRect.top < headerHeight;
        returnToday.replaceChildren(icon(above ? "up" : "down"), "Aujourd’hui");
        returnToday.setAttribute("aria-label", "Revenir à aujourd’hui, vers le " + (above ? "haut" : "bas"));
    }, { rootMargin: `-${headerHeight}px 0px 0px`, threshold: [0, 1] });
    if (currentDay) todayObserver.observe(currentDay);
    edgeObserver = new IntersectionObserver(entries => {
        for (const entry of entries) {
            if (!entry.isIntersecting) continue;
            edgeObserver.unobserve(entry.target);
            extend(entry.target.dataset.before === "true");
        }
    });
    for (const target of days.querySelectorAll(".edge")) edgeObserver.observe(target);
}

function edge(before) {
    return node("div", { class: "edge", "data-before": String(before) },
        button(before ? "Charger les jours précédents" : "Charger les jours suivants", () => extend(before)));
}

function mealCard(date, meal) {
    const slot = currentSlot(date, meal), past = date < today, editable = writable() && !past;
    const available = editable && (slot || proposal || date <= shiftDate(today, 20));
    const card = node("article", { class: slot ? "slot" : "slot empty-slot", draggable: slot && editable ? "true" : null });
    const title = slot?.recipe
        ? button(mealTitle(slot), () => openRecipe(slot.recipe.id, { people: slot.people || user.preferences.people }), "link-title")
        : node("strong", {}, slot ? mealTitle(slot) : past ? "Aucun repas" : "Créneau libre");
    const picture = art(slot?.recipe, slot?.title ? "📝" : slot ? null : "");
    const duration = slot?.recipe && recipeMinutes(slot.recipe);
    const summary = [meals[meal], duration != null ? duration + " min" : null].filter(Boolean).join(" · ");
    card.append(slot?.recipe
        ? node("button", { type: "button", class: "art-link", "aria-label": "Lire " + mealTitle(slot),
            onclick: () => openRecipe(slot.recipe.id, { people: slot.people || user.preferences.people }) }, picture)
        : picture,
        node("div", { class: "slot-cap" }, node("small", { class: duration != null ? "muted" : "muted meal-label" }, summary),
            slot || !available ? title : button([icon("plus"), "Planifier"], () => openMeal(date, meal), "slot-add"),
            slot?.people ? node("small", { class: "muted" }, slot.people + " pers.") : null,
            past && writable() && (slot?.recipe || slot?.title) ? button("Copier ce repas", () => openCopy(slot), "slot-copy") : null));
    if (slot && available) card.append(iconButton("Modifier le repas", "pencil", () => openMeal(date, meal), "slot-action"));
    if (editable) {
        card.addEventListener("dragstart", event => {
            if (!slot) return;
            dragSource = slot;
            event.dataTransfer.setData("text/plain", slot.id);
            event.dataTransfer.effectAllowed = "move";
        });
        card.addEventListener("dragend", () => { dragSource = null; });
        card.addEventListener("dragover", event => {
            if (!dragSource) return;
            event.preventDefault();
            card.classList.add("drop");
        });
        card.addEventListener("dragleave", () => card.classList.remove("drop"));
        card.addEventListener("drop", event => {
            event.preventDefault();
            card.classList.remove("drop");
            const source = dragSource;
            dragSource = null;
            if (source) moveMeal(source, { date, meal, slot }).catch(error => notice(error.message, true));
        });
    }
    return card;
}


function art(recipe, fallback) {
    const symbol = fallback === "📝" ? node("img", { src: "/app/admin/manual-meal.jpg", alt: "" }) :
        fallback ?? ({ vegetarian: "🥗", fish: "🐟", meat: "🍗" }[recipe?.dishType] || "🍽️");
    const block = node("div", { class: "art", "aria-hidden": "true" }, symbol);
    if (recipe?.image) {
        block.dataset.recipe = recipe.id;
        imageObserver.observe(block);
    }
    return block;
}

async function loadImage(block) {
    const id = block.dataset.recipe;
    try {
        if (!images.has(id)) {
            const value = request(recipePath(id) + "/image", { image: true }).then(blob => new Promise((resolve, reject) => {
                const reader = new FileReader();
                reader.onload = () => resolve(reader.result);
                reader.onerror = reject;
                reader.readAsDataURL(blob);
            }));
            if (images.size >= 48) images.delete(images.keys().next().value);
            images.set(id, value);
        }
        const url = await images.get(id);
        if (block.isConnected && typeof url === "string" && url.startsWith("data:image/")) {
            block.replaceChildren(node("img", { src: url, alt: "" }));
        }
    } catch { /* A thumbnail is optional. */ }
}

function unobserveArt(root) {
    for (const item of root.querySelectorAll(".art[data-recipe]")) imageObserver.unobserve(item);
}

function closePanel() {
    dialogVersion++;
    if (panel.open) panel.close();
    unobserveArt(panel);
    panel.replaceChildren();
}

panel.addEventListener("cancel", event => {
    event.preventDefault();
    panel.querySelector(".panel-footer button")?.click();
});
panel.addEventListener("click", event => { if (event.target === panel) closePanel(); });

function showPanel(title, body, footer = null, back = closePanel) {
    panel.classList.remove("recipe-panel");
    const heading = node("h2", { id: "panel-title" }, title);
    unobserveArt(panel);
    panel.replaceChildren(node("div", { class: "panel-inner stack" },
        node("header", { class: "panel-head" }, heading),
        node("div", { class: "panel-body stack" }, body),
        node("footer", { class: "panel-footer" }, button("Retour", back), footer)));
    if (!panel.open) panel.showModal();
    panel.scrollTop = 0;
    return ++dialogVersion;
}

async function run(control, action) {
    if (control.dataset.busy) return;
    control.dataset.busy = "true";
    control.disabled = true;
    try { await action(); }
    catch (error) {
        notice(error.message, true);
        if (panel.open) {
            const body = panel.querySelector(".panel-body");
            body?.querySelector(".request-error")?.remove();
            body?.prepend(node("p", { class: "error-text request-error", role: "alert" }, error.message));
        }
    } finally {
        delete control.dataset.busy;
        if (control.isConnected) control.disabled = false;
    }
}

function countControl(label, value, min, max, onChange) {
    const count = node("output", { "aria-live": "polite" }, String(value));
    const change = delta => {
        value = Math.max(min, Math.min(max, value + delta));
        count.value = String(value);
        less.disabled = value === min;
        more.disabled = value === max;
        onChange(value);
    };
    const less = button("−", () => change(-1), "", value <= min);
    const more = button("+", () => change(1), "", value >= max);
    less.setAttribute("aria-label", label + " : diminuer");
    more.setAttribute("aria-label", label + " : augmenter");
    return node("div", { class: "stepper" }, node("strong", {}, label), less, count, more);
}

function dateControl(label, value, min, max, onChange) {
    const input = node("input", { type: "date", value, min, max, required: true });
    const formatted = node("small", { class: "muted" }, dateLabel(value));
    input.addEventListener("change", () => {
        if (input.validity.valid) { formatted.textContent = dateLabel(input.value); onChange(input.value); }
        else { input.value = value; }
        value = input.value;
    });
    return node("label", {}, label, input, formatted);
}

function mealControl(options, initial, onChange) {
    return node("fieldset", {}, node("legend", {}, "Créneaux"),
        options.map(([value, label]) => node("label", {},
            node("input", { type: "radio", name: "meal", value, checked: value === initial,
                onchange: event => { if (event.target.checked) onChange(value); } }), label)));
}

function serializeSlot(slot) {
    return {
        id: slot.id, date: slot.date, meal: slot.meal, recipe: slot.recipe?.id ? { id: slot.recipe.id } : null,
        title: slot.title || null, people: slot.people ?? null, locked: Boolean(slot.locked),
    };
}

function baseline(snapshot) {
    return snapshot.map(slot => ({ id: slot.id, version: slot.version }));
}

function mustMatch(current, expected) {
    if ((current?.id ?? null) !== (expected?.id ?? null) || (current?.version ?? 0) !== (expected?.version ?? 0)) {
        throw new Error("Ce créneau a changé. Ouvrez-le à nouveau pour vérifier son contenu.");
    }
}

function newSlot(date, meal, contents, previous) {
    return { ...contents, id: previous?.id || newID(), date, meal, version: previous?.version || 0 };
}

function newID() {
    if (crypto.randomUUID) return crypto.randomUUID();
    const bytes = crypto.getRandomValues(new Uint8Array(16));
    bytes[6] = (bytes[6] & 15) | 64;
    bytes[8] = (bytes[8] & 63) | 128;
    const hex = Array.from(bytes, value => value.toString(16).padStart(2, "0")).join("");
    return [hex.slice(0, 8), hex.slice(8, 12), hex.slice(12, 16), hex.slice(16, 20), hex.slice(20)].join("-");
}

async function writeMeal(date, meal, expected, replacement) {
    if (!writable() || date < today) throw new Error("Ce repas est en lecture seule.");
    if (proposal) {
        if (date < proposal.from || date > proposal.to) throw new Error("Ce créneau est hors de la proposition.");
        mustMatch(currentSlot(date, meal), expected);
        proposal.slots = proposal.slots.filter(slot => slot.date !== date || slot.meal !== meal);
        if (replacement) proposal.slots.push(replacement);
    } else {
        const validated = await range(date, date);
        if (date < validated.today) throw new Error("Ce repas est passé.");
        if (!expected && replacement && date > shiftDate(validated.today, 20)) throw new Error("Choisissez un jour dans les 21 prochains jours.");
        const snapshot = validated.slots || [];
        mustMatch(snapshot.find(slot => slot.meal === meal), expected);
        const next = snapshot.filter(slot => slot.meal !== meal);
        if (replacement) next.push(replacement);
        const saved = await post("/menu/confirm", { from: date, to: date, slots: next.map(serializeSlot), base: baseline(snapshot) });
        applyMenu({ today: validated.today, slots: saved.slots }, date, date);
    }
    closePanel();
    renderAgenda();
}

async function moveMeal(source, target) {
    if (!writable() || source.date < today || target.date < today) return;
    if (source.date === target.date && source.meal === target.meal || Math.abs(dayDistance(source.date, target.date)) >= 60) return;
    const from = source.date < target.date ? source.date : target.date;
    const to = source.date > target.date ? source.date : target.date;
    const snapshot = proposal
        ? proposal.slots.filter(slot => slot.date >= from && slot.date <= to)
        : (await range(from, to)).slots || [];
    mustMatch(snapshot.find(slot => slot.date === source.date && slot.meal === source.meal), source);
    mustMatch(snapshot.find(slot => slot.date === target.date && slot.meal === target.meal), target.slot);
    let next = [...snapshot];
    if (target.slot) {
        next.sort((a, b) => a.date.localeCompare(b.date) || (a.meal === "lunch" ? -1 : 1));
        const positions = [...next], contents = [...next];
        const a = contents.findIndex(slot => slot.id === source.id);
        const b = contents.findIndex(slot => slot.id === target.slot.id);
        contents.splice(b, 0, contents.splice(a, 1)[0]);
        next = positions.map((position, i) => {
            const { recipe, title, people, locked } = contents[i];
            return { ...position, recipe, title, people, locked };
        });
    } else {
        next = next.filter(slot => slot.id !== source.id);
        next.push(newSlot(target.date, target.meal, source));
    }
    if (proposal) {
        proposal.slots = proposal.slots.filter(slot => slot.date < from || slot.date > to).concat(next);
    } else {
        const saved = await post("/menu/confirm", { from, to, slots: next.map(serializeSlot), base: baseline(snapshot) });
        applyMenu({ today, slots: saved.slots }, from, to);
    }
    renderAgenda();
    notice("Repas déplacé.");
}

function openMeal(date, meal) {
    const original = currentSlot(date, meal);
    renderMealEditor({
        date, meal, original, people: original?.people || user.preferences.people,
        manual: original?.title || "", selected: null,
    });
}

function renderMealEditor(draft) {
    const { original, date, meal } = draft;
    const manualInput = node("input", { value: draft.manual, maxlength: 100, placeholder: "Intitulé du repas" });
    const save = button("Enregistrer", event => run(event.currentTarget, async () => {
        const content = evaluate();
        await writeMeal(date, meal, original, newSlot(date, meal, content, original));
        notice("Repas enregistré.");
    }), "primary");
    const manualCard = node("section", { class: "card stack" },
        node("h3", {}, original ? "Remplacer par un repas libre" : "Ajouter un repas libre"),
        node("label", {}, "Intitulé du repas", manualInput));
    const replaceCard = node("section", { class: "card stack" }, node("h3", {}, "Recette de remplacement"),
        draft.selected ? recipeCard(draft.selected) : null,
        node("div", { class: "actions" },
            button("Recette au hasard", () => openRandom(draft)),
            button("Choisir une recette", () => openPicker(draft))));
    function evaluate() {
        const manual = draft.manual.trim();
        const title = manual || (!draft.selected ? original?.title || null : null);
        const recipe = title ? null : draft.selected || original?.recipe || null;
        const changed = title !== (original?.title || null) || recipe?.id !== original?.recipe?.id;
        save.textContent = changed && title ? "Enregistrer le repas libre" :
            changed && recipe ? original ? "Changer de recette" : "Planifier la recette" : "Enregistrer";
        save.disabled = Boolean(save.dataset.busy) || (!changed && draft.people === (original?.people || user.preferences.people)) ||
            (!original && !recipe && !title);
        manualCard.classList.toggle("warning", Boolean(manual && manual !== original?.title));
        replaceCard.classList.toggle("warning", Boolean(!title && draft.selected && draft.selected.id !== original?.recipe?.id));
        return { recipe, title, people: draft.people, locked: changed || Boolean(original?.locked) };
    }
    manualInput.addEventListener("input", () => { draft.manual = manualInput.value; evaluate(); });
    const content = [
        node("p", { class: "muted" }, dateLabel(date) + " · " + meals[meal]),
        node("section", { class: "card stack" }, node("h3", {}, original ? "Repas prévu" : "Créneau libre"),
            original ? node("div", { class: "meal-summary" }, art(original.recipe, original.title ? "📝" : null),
                node("strong", {}, mealTitle(original)), original.recipe ? iconButton("Voir la recette", "eye", () =>
                    openRecipe(original.recipe.id, { people: draft.people, back: () => renderMealEditor(draft) })) : null) : null,
            countControl("Nombre de personnes", draft.people, 1, 20, value => { draft.people = value; evaluate(); })),
        replaceCard, manualCard,
        original ? node("div", { class: "edge" }, button("Supprimer ce repas", () => {
            showPanel("Supprimer ce repas ?", node("p", {}, "« " + mealTitle(original) + " » sera retiré du planning."),
                button("Supprimer", event => run(event.currentTarget, async () => {
                    await writeMeal(date, meal, original, null);
                    notice("Repas supprimé.");
                }), "danger"), () => renderMealEditor(draft));
        }, "danger")) : null,
    ];
    evaluate();
    showPanel("Modifier le repas", content, save);
}

function recipeCard(recipe, onChoose = null) {
    const metadata = [types[recipe.dishType], recipeMinutes(recipe) != null ? recipeMinutes(recipe) + " min" : null,
        ...(recipe.tags || []).slice(0, 5)].filter(Boolean).join(" · ");
    return node(onChoose ? "button" : "article", {
        type: onChoose ? "button" : null, class: "card recipe-row", onclick: onChoose,
    }, art(recipe), node("div", {}, node("strong", {}, recipe.title), node("small", {}, metadata)));
}

function recipeTile(recipe) {
    const type = types[recipe.dishType];
    const duration = recipeMinutes(recipe);
    return button([art(recipe), node("div", { class: "recipe-tile-meta" },
        node("strong", {}, recipe.title),
        node("div", { class: "actions" }, type ? node("span", { class: "tag " + recipe.dishType }, type) : null,
            duration != null ? node("small", { class: "muted" }, duration + " min") : null),
        recipe.tags?.length ? node("small", { class: "muted" }, recipe.tags.slice(0, 3).join(" · ")) : null,
    )], () => openRecipe(recipe.id), "card recipe-tile");
}

function recipeSearch(makeCard, groupClass) {
    let query = "", items = [], timer, revision = 0;
    const typeFilters = new Set(), seasonFilters = new Set();
    const search = node("input", { type: "search", placeholder: "Titre ou étiquette", "aria-label": "Rechercher une recette" });
    const results = node("div", { class: groupClass, "aria-live": "polite" }, "Chargement du carnet…");
    const controls = node("div", { class: "recipe-search" }, search,
        node("div", { class: "chips", "aria-label": "Filtres" },
            Object.entries(types).map(([key, label]) => filterButton(key, label, typeFilters, render)),
            Object.entries(seasons).map(([key, label]) => filterButton(key, label, seasonFilters, render))));

    function render() {
        unobserveArt(results);
        const filtered = items.filter(recipe =>
            (!typeFilters.size || typeFilters.has(recipe.dishType)) &&
            (!seasonFilters.size || !recipe.seasons?.length || recipe.seasons.some(season => seasonFilters.has(season))));
        results.replaceChildren(...filtered.map(makeCard));
        if (!filtered.length) results.append(node("p", { class: "muted" }, "Aucune recette pour cette recherche."));
    }
    async function load() {
        const current = ++revision;
        try {
            const found = await catalog(query);
            if (current !== revision || !results.isConnected) return;
            items = found;
            render();
        } catch (error) {
            if (current === revision && results.isConnected) results.replaceChildren(
                node("p", { class: "error-text" }, error.message), button("Réessayer", load));
        }
    }
    search.addEventListener("input", () => {
        query = search.value;
        revision++;
        clearTimeout(timer);
        timer = setTimeout(load, 180);
    });
    return { controls, results, load };
}

function openPicker(draft) {
    const { controls, results, load } = recipeSearch(recipe => recipeCard(recipe, () => {
        draft.selected = recipe;
        draft.manual = "";
        renderMealEditor(draft);
    }), "stack");
    showPanel("Choisir une recette", [controls, results], null, () => renderMealEditor(draft));
    load();
}

function filterButton(id, label, filters, update) {
    const chip = button(label, () => {
        if (filters.has(id)) filters.delete(id); else filters.add(id);
        chip.setAttribute("aria-pressed", String(filters.has(id)));
        update();
    });
    chip.setAttribute("aria-pressed", String(filters.has(id)));
    return chip;
}

function shuffled(items) {
    const shuffledItems = [...items];
    for (let i = shuffledItems.length - 1; i > 0; i--) {
        const j = Math.floor(Math.random() * (i + 1));
        [shuffledItems[i], shuffledItems[j]] = [shuffledItems[j], shuffledItems[i]];
    }
    return shuffledItems;
}

async function openRandom(draft) {
    const loading = node("p", {}, "Vérification du planning récent…");
    const version = showPanel("Recette au hasard", loading, null, () => renderMealEditor(draft));
    try {
        const [recipes, history] = await Promise.all([catalog(), range(shiftDate(draft.date, -4), draft.date)]);
        if (version !== dialogVersion || !panel.open) return;
        const planned = proposal
            ? (history.slots || []).filter(slot => slot.date < proposal.from || slot.date > proposal.to).concat(proposal.slots)
            : history.slots || [];
        const lastUsed = new Map();
        for (const slot of planned) {
            const days = dayDistance(draft.date, slot.date), id = slot.recipe?.id;
            if (id && days >= 0 && days < 5) lastUsed.set(id, Math.min(lastUsed.get(id) ?? 5, days));
        }
        const available = recipes.filter(recipe => recipe.id !== draft.original?.recipe?.id &&
            (recipe.mealTypes || ["lunch", "dinner"]).includes(draft.meal));
        const order = shuffled(available.filter(recipe => !lastUsed.has(recipe.id)))
            .concat(shuffled(available.filter(recipe => lastUsed.has(recipe.id))));
        randomDeck(draft, order, lastUsed);
    } catch (error) {
        if (version !== dialogVersion) return;
        loading.replaceWith(node("div", { class: "stack" }, node("p", { class: "error-text" }, error.message),
            button("Réessayer", () => openRandom(draft))));
    }
}

function randomDeck(draft, order, lastUsed) {
    const ideas = new Map([
        ["Végé", recipe => recipe.dishType === "vegetarian"],
        ["30 min ou moins", recipe => recipeMinutes(recipe) != null && recipeMinutes(recipe) <= 30],
        ...Object.entries(seasons).map(([code, label]) => [label, recipe => (recipe.seasons || []).includes(code)]),
        ["Salades", recipe => /salade/i.test([recipe.title, ...(recipe.tags || [])].join(" "))],
        ["Soupes", recipe => /soupe|velouté/i.test([recipe.title, ...(recipe.tags || [])].join(" "))],
    ]);
    for (const tag of [...new Set(order.flatMap(recipe => recipe.tags || []))].slice(0, 8)) {
        if (!ideas.has(tag)) ideas.set(tag, recipe => (recipe.tags || []).includes(tag));
    }
    const filters = new Set();
    let candidates = order, index = 0;
    const deck = node("div", { class: "deck", "aria-label": "Suggestions" });
    const previous = button("Précédente", () => go(index - 1));
    const next = button("Suivante", () => go(index + 1));
    const counter = node("p", { class: "muted", "aria-live": "polite" });
    const choose = button("Choisir cette recette", () => {
        if (!candidates[index]) return;
        draft.selected = candidates[index];
        draft.manual = "";
        renderMealEditor(draft);
    }, "primary");
    function update() {
        previous.disabled = index === 0;
        next.disabled = !candidates.length || index >= candidates.length - 1;
        choose.disabled = !candidates.length;
        counter.textContent = candidates.length ? (index + 1) + " / " + candidates.length :
            "Aucune recette ne correspond à ces envies.";
    }
    function go(pageNumber) {
        index = Math.max(0, Math.min(pageNumber, candidates.length - 1));
        deck.children[index]?.scrollIntoView({ block: "nearest", inline: "start" });
        update();
    }
    function render() {
        unobserveArt(deck);
        deck.replaceChildren(...candidates.map(recipe => {
            const days = lastUsed.get(recipe.id);
            return node("article", { class: "card" }, art(recipe),
                node("h3", {}, recipe.title),
                node("p", { class: "muted" }, [recipeMinutes(recipe) != null ? recipeMinutes(recipe) + " min" : "",
                    types[recipe.dishType], ...(recipe.tags || [])].filter(Boolean).join(" · ")),
                days != null ? node("p", { class: "warning" }, days === 0 ? "Recette déjà planifiée ce jour-là" :
                    "Recette déjà planifiée il y a " + days + (days === 1 ? " jour" : " jours")) : null);
        }));
        go(index);
    }
    deck.addEventListener("scroll", () => {
        const edge = deck.getBoundingClientRect().left;
        const visible = [...deck.children].reduce((best, child, i) =>
            Math.abs(child.getBoundingClientRect().left - edge) < best.distance
                ? { index: i, distance: Math.abs(child.getBoundingClientRect().left - edge) } : best,
        { index: 0, distance: Infinity });
        index = visible.index;
        update();
    });
    showPanel("Recette au hasard", [
        node("p", { class: "muted" }, dateLabel(draft.date) + " · " + meals[draft.meal]),
        deck, node("div", { class: "actions" }, previous, counter, next),
        node("h3", {}, "Filtrer les suggestions"),
        node("div", { class: "chips" }, [...ideas.keys()].map(label => filterButton(label, label, filters, () => {
            candidates = order.filter(recipe => [...filters].every(idea => ideas.get(idea)(recipe)));
            index = 0;
            render();
        }))),
    ], choose, () => renderMealEditor(draft));
    render();
}

function composeMenu() {
    let start = shiftDate(today, 1), duration = 7, people = user.preferences.people, plan = "lunch_dinner";
    const date = dateControl("Jour de départ", start, today, shiftDate(today, 13), value => { start = value; });
    const body = [
        date, countControl("Durée en jours", duration, 1, 21, value => { duration = value; }),
        countControl("Nombre de personnes", people, 1, 20, value => { people = value; }),
        mealControl([["lunch", "Midi"], ["lunch_dinner", "Midi et soir"], ["dinner", "Soir"]], plan, value => { plan = value; }),
    ];
    showPanel("Composer un menu", body, button("Créer la proposition", event => run(event.currentTarget, async () => {
        if (people !== user.preferences.people) user = await request("/me/preferences", { method: "PUT", body: { people } });
        const menu = await post("/menu/preview", { start, meals: duration * (plan === "lunch_dinner" ? 2 : 1), plan });
        proposal = { ...menu, slots: menu.slots || [] };
        closePanel();
        renderAgenda();
        window.scrollTo(0, 0);
    }), "primary"));
}

async function publishProposal() {
    const draft = proposal;
    if (!draft) return;
    const saved = await post("/menu/confirm", { ...draft, slots: draft.slots.map(serializeSlot) });
    applyMenu({ today, slots: saved.slots }, draft.from, draft.to);
    if (last < draft.to) last = draft.to;
    proposal = null;
    renderAgenda();
    notice("Proposition validée.");
}

function openCopy(source) {
    const minimum = shiftDate(today, 1), maximum = shiftDate(today, 20);
    let date = minimum, meal = source.meal, destination = null, revision = 0;
    const message = node("p", { role: "status", class: "muted" });
    const copy = button("Copier ce repas", event => run(event.currentTarget, async () => {
        if (!destination) return;
        const target = destination.find(slot => slot.meal === meal);
        if (target && !window.confirm("« " + mealTitle(target) + " » est déjà prévu. Remplacer ce repas par une copie de « " + mealTitle(source) + " » ?")) return;
        await writeMeal(date, meal, target, newSlot(date, meal, {
            ...source, people: source.people || user.preferences.people, locked: true,
        }, target));
        notice("Repas copié · " + dateLabel(date) + " · " + meals[meal]);
    }), "primary", true);
    const retry = button("Réessayer", () => checkDestination());
    retry.hidden = true;
    const body = [
        node("p", {}, mealTitle(source) + " · " + dateLabel(source.date)),
        dateControl("Date de destination", date, minimum, maximum, value => { date = value; checkDestination(); }),
        mealControl(Object.entries(meals), meal, value => { meal = value; showDestination(); }),
        message, retry,
    ];
    const version = showPanel("Copier un repas passé", body, copy);
    function showDestination() {
        if (!destination) return;
        const target = destination.find(slot => slot.meal === meal);
        copy.disabled = false;
        copy.textContent = target ? "Remplacer le repas" : "Copier ce repas";
        message.className = target ? "warning" : "muted";
        message.textContent = target ? "« " + mealTitle(target) + " » est déjà prévu. Confirmez pour le remplacer." : "Créneau libre.";
    }
    async function checkDestination() {
        const check = ++revision;
        destination = null;
        copy.disabled = true;
        retry.hidden = true;
        message.className = "muted";
        message.textContent = "Vérification du créneau…";
        try {
            const menu = await range(date, date);
            if (check !== revision || version !== dialogVersion) return;
            if (date <= menu.today) throw new Error("Choisissez un jour à venir.");
            destination = menu.slots || [];
            showDestination();
        } catch (error) {
            if (check !== revision || version !== dialogVersion) return;
            message.textContent = error.message;
            retry.hidden = false;
        }
    }
    checkDestination();
}

function openRecipes() {
    page = "recipes";
    markNavigation();
    edgeObserver?.disconnect();
    todayObserver?.disconnect();
    closePanel();
    const { controls, results, load } = recipeSearch(recipeTile, "recipe-grid");
    const heading = node("header", { class: "page-heading" }, node("h1", {}, "Recettes"),
        writable() ? button([icon("plus"), "Ajouter une recette"], () => editRecipe(), "primary") : null);
    unobserveArt(main);
    main.replaceChildren(node("section", { class: "page stack" }, heading, controls, results));
    window.scrollTo(0, 0);
    load();
}

async function openRecipe(id, context = {}) {
    const back = context.back || closePanel;
    const content = node("p", {}, "Chargement de la recette…");
    const version = showPanel("Recette", content, null, back);
    try {
        const recipe = await request(recipePath(id));
        if (version !== dialogVersion) return;
        const available = !recipe.archivedAt;
        let people = context.people || user.preferences.people;
        const ingredients = node("ul", {});
        const amount = new Intl.NumberFormat("fr-FR", { maximumFractionDigits: 2 });
        function refreshIngredients() {
            ingredients.replaceChildren(...(recipe.ingredients || []).map(item => {
                const quantity = item.quantity == null ? "" : amount.format(item.quantity * people) + " ";
                return node("li", {}, quantity + [item.unit, item.name].filter(Boolean).join(" ") + (item.note ? ", " + item.note : ""));
            }));
        }
        refreshIngredients();
        const favorite = button(recipe.favorite ? "Retirer des favoris" : "Ajouter aux favoris", event => run(event.currentTarget, async () => {
            await request(recipePath(id) + "/favorite", { method: recipe.favorite ? "DELETE" : "PUT" });
            recipe.favorite = !recipe.favorite;
            favorite.textContent = recipe.favorite ? "Retirer des favoris" : "Ajouter aux favoris";
        }));
        let wish = null;
        if (user.role === "readonly" && available) {
            wish = button("J’ai envie de cette recette", event => run(event.currentTarget, async () => {
                await post(recipePath(id) + "/wish");
                wish.textContent = "Envie envoyée";
            }));
        }
        showPanel(recipe.title, node("div", { class: "recipe-detail" },
            node("div", { class: "stack" }, art(recipe),
                node("p", { class: "muted" }, [recipeMinutes(recipe) != null ? recipeMinutes(recipe) + " min" : "",
                    types[recipe.dishType], ...(recipe.tags || [])].filter(Boolean).join(" · ")),
                recipe.notes ? node("p", { class: "card" }, recipe.notes) : null,
                node("div", { class: "actions" }, favorite, wish,
                    writable() && available && !context.back && context.people == null
                        ? button("Modifier la recette", () => editRecipe(recipe)) : null)),
            node("div", { class: "stack" },
                node("section", { class: "card stack" }, node("h3", {}, "Ingrédients"),
                    countControl("Nombre de personnes", people, 1, 20, value => { people = value; refreshIngredients(); }), ingredients),
                (recipe.steps || []).length ? node("section", { class: "card stack" }, node("h3", {}, "Préparation"),
                    node("ol", {}, recipe.steps.map(step => node("li", {}, step)))) : null)),
        writable() && available && !context.back && context.people == null
            ? button("Ajouter au planning", () => planRecipe(recipe), "primary") : null, back);
        panel.classList.add("recipe-panel");
    } catch (error) { if (version === dialogVersion) content.textContent = error.message; }
}

function planRecipe(recipe) {
    let date = today, meal = "dinner";
    showPanel("Ajouter au planning", [
        node("p", {}, recipe.title),
        dateControl("Date du repas", date, today, shiftDate(today, 20), value => { date = value; }),
        mealControl(Object.entries(meals), meal, value => { meal = value; }),
    ], button("Planifier la recette", event => run(event.currentTarget, async () => {
        const menu = await range(date, date);
        const target = (menu.slots || []).find(slot => slot.meal === meal);
        if (target && !window.confirm("Remplacer « " + mealTitle(target) + " » ?")) return;
        await writeMeal(date, meal, target, newSlot(date, meal, { recipe, people: user.preferences.people, locked: true }, target));
        notice("Recette planifiée.");
    }), "primary"), () => openRecipe(recipe.id));
}

function parseIngredient(line) {
    const match = line.match(/^(\d+(?:[.,]\d+)?(?:\/\d+)?)\s*(?:(kg|g|mg|ml|cl|l|c\. à soupe|c\. à café)\s+)?(.+)$/i);
    if (!match) return { name: line, rawText: line };
    const [a, b] = match[1].replace(",", ".").split("/").map(Number);
    if (!Number.isFinite(a) || b === 0) return { name: line, rawText: line };
    return { quantity: b ? a / b : a, unit: match[2] || null, name: match[3].trim(), rawText: line };
}

function editRecipe(recipe = null) {
    const fields = node("form", { class: "stack", onsubmit: event => {
        event.preventDefault();
        save.click();
    } });
    const field = (label, name, value, attributes = {}, long = false) => {
        const input = long ? node("textarea", { name, ...attributes }, value || "") :
            node("input", { name, value: value ?? "", ...attributes });
        return node("label", {}, label, input);
    };
    const dish = node("select", { name: "dishType", required: true },
        Object.entries(types).map(([key, label]) => node("option", { value: key, selected: recipe?.dishType === key }, label)));
    const checkboxGroup = (title, name, choices, values) => node("fieldset", {}, node("legend", {}, title),
        Object.entries(choices).map(([key, label]) =>
            node("label", {}, node("input", { type: "checkbox", name, value: key, checked: values.includes(key) }), label)));
    fields.append(field("Titre", "title", recipe?.title, { required: true }), node("label", {}, "Type de plat", dish),
        checkboxGroup("Créneaux", "mealTypes", meals, recipe?.mealTypes || Object.keys(meals)),
        checkboxGroup("Saisons", "seasons", seasons, recipe?.seasons || []),
        node("div", { class: "form-grid" }, field("Préparation en minutes", "prepMinutes", recipe?.prepMinutes, { type: "number", min: 0 }),
            field("Cuisson en minutes", "cookMinutes", recipe?.cookMinutes, { type: "number", min: 0 })),
        field("Étiquettes", "tags", (recipe?.tags || []).join(", ")),
        field("Ingrédients pour une personne (un par ligne)", "ingredients", (recipe?.ingredients || []).map(item =>
            item.rawText || [item.quantity, item.unit, item.name].filter(value => value != null).join(" ")).join("\n"), {}, true),
        field("Préparation (une étape par ligne)", "steps", (recipe?.steps || []).join("\n"), {}, true),
        field("Notes", "notes", recipe?.notes, {}, true));
    const save = button(recipe ? "Enregistrer" : "Créer la recette", event => run(event.currentTarget, async () => {
        if (!fields.reportValidity()) return;
        const values = new FormData(fields);
        const lines = name => values.get(name).split("\n").map(line => line.trim()).filter(Boolean);
        const num = name => values.get(name) === "" ? null : Number(values.get(name));
        const body = {
            title: values.get("title").trim(), dishType: values.get("dishType"),
            mealTypes: values.getAll("mealTypes"), seasons: values.getAll("seasons"),
            prepMinutes: num("prepMinutes"), cookMinutes: num("cookMinutes"),
            tags: values.get("tags").split(",").map(tag => tag.trim()).filter(Boolean),
            ingredients: values.get("ingredients") === (recipe?.ingredients || []).map(item =>
                item.rawText || [item.quantity, item.unit, item.name].filter(value => value != null).join(" ")).join("\n")
                ? recipe?.ingredients || [] : lines("ingredients").map(parseIngredient),
            steps: lines("steps"), notes: values.get("notes").trim() || null,
            sourceUrl: recipe?.sourceUrl || null, servings: recipe?.servings ?? null,
        };
        const saved = await request(recipe ? recipePath(recipe.id) : "/recipes", { method: recipe ? "PUT" : "POST", body });
        if (page === "recipes") openRecipes();
        await openRecipe(saved.id);
    }), "primary");
    showPanel(recipe ? "Modifier la recette" : "Nouvelle recette", fields, save, recipe ? () => openRecipe(recipe.id) : closePanel);
}

function openAccount() {
    page = "account";
    markNavigation();
    edgeObserver?.disconnect();
    todayObserver?.disconnect();
    closePanel();
    let people = user.preferences.people;
    const name = user.displayName || user.username;
    const preferences = writable() ? node("section", { class: "card stack" }, node("h2", {}, "Préférences"),
        countControl("Personnes par défaut", people, 1, 20, value => { people = value; }),
        button("Enregistrer les préférences", event => run(event.currentTarget, async () => {
            user = await request("/me/preferences", { method: "PUT", body: { people } });
            notice("Préférences enregistrées.");
        }), "primary")) : null;
    const identity = node("section", { class: "card stack" }, node("div", { class: "account-name" },
        node("span", { class: "avatar", "aria-hidden": "true" }, name.charAt(0).toUpperCase()),
        node("div", {}, node("h2", {}, name), node("p", { class: "muted" }, "@" + user.username + " · " + roleLabels[user.role]))),
        button("Se déconnecter", event => run(event.currentTarget, async () => {
            await post("/app/session/logout");
            showLogin();
        })));
    unobserveArt(main);
    main.replaceChildren(node("section", { class: "page" }, node("header", { class: "page-heading" }, node("h1", {}, "Compte")),
        node("div", { class: "account-grid" }, identity, preferences)));
    window.scrollTo(0, 0);
}

async function openNotices() {
    const content = node("div", { class: "stack" }, "Chargement…");
    const version = showPanel("Notifications", content);
    try {
        const data = await request("/notifications");
        if (version !== dialogVersion) return;
        const notices = data.notifications || data.items || [];
        content.replaceChildren(...notices.map(item => node("article", { class: "card stack" },
            node("strong", {}, item.recipeTitle), node("p", { class: "muted" }, item.requesterName || item.requesterDisplayName || ""),
            item.recipeAvailable ? button("Voir la recette", () => openRecipe(item.recipeId)) : null,
            button("Effacer", event => run(event.currentTarget, async () => {
                await request("/notifications/" + encodeURIComponent(item.id), { method: "DELETE" });
                await openNotices();
            })))));
        if (!notices.length) content.textContent = "Aucune notification.";
    } catch (error) { if (version === dialogVersion) content.textContent = error.message; }
}

bindLogin();
resumeSession().then(async pair => {
    if (session) return;
    authGeneration++;
    session = pair;
    user = pair.user;
    showNavigation();
    await openInitialView();
}).catch(error => {
    if (error.status !== 401) notice(error.message, true);
});
