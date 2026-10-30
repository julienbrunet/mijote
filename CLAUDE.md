# Mijote — Project rules

Mijote is a self-hosted family weekly menu planner (mobile app + Go-favored backend + PostgreSQL). See `requirements.md`.

## Language
- All code, code comments, documentation and commit messages are written in **English**.
- Conversation with the user happens in **French**.

## Commits
- Use Conventional Commits: `feat:`, `fix:`, `docs:`, `refactor:`, `test:`, `chore:`, `build:`, `ci:`…

## Scope and decisions
- `requirements.md` is the source of truth for scope. Check it before implementing a feature; update it when a decision changes.
- Record each significant technical decision as a short ADR in `docs/adr/` (`NNNN-title.md`: Context, Decision, Consequences).
- Never treat open questions (requirements.md §7) as decided. Ask the user first.

## Constraints
- Low resources: justify any new dependency or service; prefer the standard library when reasonable.
- No WebSockets / real-time; REST only, versioned API.
- Keep the backend stateless apart from PostgreSQL and the image directory, so the instance stays easy to back up and redeploy.
- Never store images in the database.
- The backend never scrapes recipe web pages; extraction happens on the device.
