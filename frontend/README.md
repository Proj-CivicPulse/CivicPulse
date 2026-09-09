# frontend

CivicPulse's **React app** — both faces of the product:

- the **citizen portal**: submit a report, track your own reports
- the **officer console**: ward rail → incident queue → incident detail, on a map

It talks to `backend-spring` for everything transactional and to `backend-node`
for the AI layer. It holds no business logic of its own: priority bands, ward
resolution and incident grouping are all server-side decisions this app renders.

> **Status:** Phases 0–4. Landing, auth, submission, My reports, and the officer
> dashboard with the Leaflet map are live. The Copilot panel (Phase 6) and
> analytics views (Phase 5) are not built.

---

## Stack

| Concern | Choice |
|---|---|
| Build | Vite 7 |
| Language | TypeScript, strict |
| UI | React 19 |
| Routing | `react-router-dom`, routes lazy-loaded with automatic chunk recovery |
| Server state | `@tanstack/react-query` |
| Client state | `zustand` (auth session only) |
| Map | `leaflet` + `react-leaflet`, CARTO basemap tiles |
| Icons | `lucide-react` |
| Styling | CSS Modules, one per component |
| Lint | `oxlint` |

---

## Setup

```bash
cd frontend
cp .env.example .env.local   # nothing in it is required to boot
npm install
npm run dev                  # http://localhost:5173
```

**Both backends must be running**, or the app renders shells and errors:

```bash
cd backend-spring && ./mvnw spring-boot:run   # :8080
cd backend-node   && npm run dev              # :3001
```

There is no global "backend is down" banner yet. `services/health.service.ts`
wraps `GET /health` on both backends, but no screen calls it — each page surfaces
its own failure through `EmptyState` and a **Try again** button instead.

### Seeing real data

A fresh database has wards and departments but no complaints, so the dashboard
is empty and correct. To get something worth looking at:

1. **Seed** — `cd backend-spring && node scripts/seed-dev-data.mjs` (run
   `backend-node` alongside it for real semantic grouping).
2. **Make yourself an officer** — registration always creates a *citizen*. Sign
   up, then in the Neon SQL Editor:
   ```sql
   UPDATE users SET role='OFFICER' WHERE email='you@example.com';
   ```
   Log out and back in; the role is baked into the token.

### Scripts

| Command | Does |
|---|---|
| `npm run dev` | Vite dev server on :5173, HMR |
| `npm run build` | `tsc -b` then `vite build` → `dist/` |
| `npm run typecheck` | `tsc -b` alone — types without the bundle |
| `npm run preview` | Serve the built bundle |
| `npm run lint` | `oxlint` |

> **Type-check with `npm run typecheck`, never with `tsc --noEmit`.**
>
> `tsconfig.json` is a *solution* file — `"files": []` plus references to
> `tsconfig.app.json` and `tsconfig.node.json`. So `tsc --noEmit` resolves the
> root config, finds no files, checks **nothing**, and exits 0. It looks like a
> clean type-check and is worthless; `tsc --noEmit --listFiles` prints zero
> lines, which is the tell.
>
> Only `tsc -b` walks the references and applies the real settings — `strict`,
> `noUnusedLocals`, and `noUncheckedIndexedAccess`, which is the one most
> likely to catch you (every indexed read is `T | undefined`).

---

## Environment

All frontend env vars are `VITE_`-prefixed, which means **Vite inlines them into
the shipped bundle**. Treat every one as public. Never give a server-side secret
a `VITE_` prefix — the geocoding and embedding keys live in the backends for
exactly this reason.

Vite reads `.env.local` (gitignored) in preference to `.env`.

| Var | Required | Default | Notes |
|---|---|---|---|
| `VITE_APP_NAME` | no | `CivicPulse` | Feeds `constants.ts` and the `%VITE_APP_NAME%` placeholders in `index.html` |
| `VITE_SPRING_API_URL` | no | `http://localhost:8080` | Dev-proxy target only; app code never reads it |
| `VITE_NODE_API_URL` | no | `http://localhost:3001` | As above |
| `VITE_REQUEST_TIMEOUT_MS` | no | `10000` | Client-side fetch bound |
| `VITE_CARTO_API_KEY` | no | *(blank)* | Basemap tiles. Blank still renders a working map, just watermarked. Restrict it **by domain** in the CARTO dashboard — that, not secrecy, protects the quota |

---

## Routes

| Path | Access | Screen |
|---|---|---|
| `/` | public | Landing — hero, how it works, live ward strip |
| `/complaints/new` | **public** | Submit a report. Reporting is open; only *tracking* needs an account |
| `/complaints` | `citizen` | My reports |
| `/dashboard` | `officer` | Incident queue, ward rail, map, incident detail |
| `/login`, `/register` | signed-out only | `PublicRoute` bounces a signed-in user to their home |
| `/forgot-password` | signed-out only | Request a reset link. Always confirms, never says whether the account exists |
| `/verify-email?token=` | **any** | Landing page for the verification email |
| `/reset-password?token=` | **any** | Set a new password; signs every session out |
| `*` | public | Not found |

The two token routes are deliberately **not** wrapped in `PublicRoute`.
Registration signs you in immediately, so verifying while already
authenticated is the normal case — and someone resetting a password may well
be signed in on that device already, since "somebody else might be signed in"
is the usual reason to reset. `PublicRoute` would redirect both away from the
page they were sent to.

`homePathForRole()` in `lib/routes.ts` is the single answer to "where does this
role belong" — Login, Register and both guards share it so a third role cannot
drift them apart.

### The `/api/core` and `/api/ai` prefixes

A **frontend-only convention**. The Vite dev proxy (and the production reverse
proxy) strip them before forwarding, so each backend sees its own prefix-free
routes — `/health`, never `/api/ai/health`. Backend route definitions must never
contain these prefixes.

### Auth

Tokens are **httpOnly cookies** set by Spring; nothing token-shaped is ever kept
in JS-reachable storage, so an XSS bug cannot exfiltrate a session.
`services/api.ts` sends `credentials: 'include'` on every call, and
`stores/auth.store.ts` holds only the decoded user, restored on boot via
`GET /auth/me`. A 401 there means "signed out", not "error".

**Registration is one step, and there is no OTP screen.** `Register.tsx`
collects name, email and password, then signs the user straight in and routes
them home — nothing sits between submitting the form and being logged in.

`user.emailVerified` exists on the store, but it is **display state only**: no
route guard, no page, and nothing on the server gates on it. `ProtectedRoute`
checks role, never verification. `VerifyEmail.tsx` is the landing page for a
link in an email, not a step in signup — an unverified account works exactly
like a verified one. Email sending is stubbed backend-side
(`EMAIL_PROVIDER=log`), so the link is printed to the Spring console rather
than delivered.

---

## Layout

```
src/
├── App.tsx               routes + lazy loading with chunk recovery
├── main.tsx              bootstrap
├── config/constants.ts   API prefixes, app name, timeouts
├── components/
│   ├── ui/               Button, Field, Panel, DataTable, MapView,
│   │                     PriorityChip, StatusChip, Skeleton, EmptyState
│   ├── dashboard/        WardRail, IncidentDetail
│   ├── landing/          Hero, HowItWorks, WardStrip, WhyDifferent, ...
│   ├── AppHeader         wordmark, primary nav, session control
│   ├── UserMenu          signed-in identity as a monogram + account menu
│   └── ProtectedRoute / PublicRoute / ErrorBoundary / RouteFallback
├── pages/                Landing, SubmitComplaint, MyComplaints,
│                         IncidentDashboard, Login, Register, VerifyEmail,
│                         ForgotPassword, ResetPassword, NotFound
├── services/             one module per backend resource; api.ts is the
│                         only place fetch is called
├── stores/auth.store.ts  zustand session
└── lib/                  routes, priority, datetime, format, errors,
                          queryClient, queryKeys
```

---

## Notes

- **`matchingStatus` is typed but not rendered.** `Complaint` carries the Phase 2
  pipeline state (`pending | processing | matched | degraded`) so nothing
  downstream is blocked, but no screen shows it yet: what "degraded" should mean
  to a citizen is an open design question, and the similarity threshold is still
  being tuned, so a UI built against today's semantics would be the first thing
  to go stale.
- **Priority bands come from the server.** `lib/priority.ts` maps a band to a
  colour; it does not decide the band. The thresholds live in Spring so the
  dashboard, the marker colour and the Copilot cannot disagree.
- **A complaint is `pending` the moment it is submitted.** Matching runs after
  the response, so the id returned by `POST /complaints` has no `incidentId`
  yet. Anything that needs the grouping must re-fetch.
- Route chunks self-heal: a failed lazy import reloads the page once
  automatically, which is what a stale `dist/` after a redeploy looks like.
