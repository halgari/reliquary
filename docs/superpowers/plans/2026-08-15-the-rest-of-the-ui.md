# The Rest of the UI

**Goal:** Reliquary's remaining three screens, so the app is usable end to end
without a terminal: browse the library, pick a version, watch it download,
open the folder.

**Spec:** `docs/superpowers/specs/2026-08-15-reliquary-design.md` §1 and §5.
The mockup is `Reliquary.dc.html` in the Claude Design project; its exact
values are reproduced per screen below.

**What already exists and must not be rebuilt:** `ui/theme.clj` (Gilt tokens,
bundled fonts), `ui/shot.clj` (PNG gate), `ui/app.clj` (window frame),
`ui/login.clj`, `ui/signed_in.clj` (the placeholder this work replaces),
`main.clj`, and the whole engine + catalog + CLI.

## Global Constraints

- **Gilt exactly**: bg `#0C0C0C`, surface `#161616`, line `#292929`,
  line-strong `#383838`, text `#F2F0EE`, text-muted `#9A9A9A`,
  gold `#C2A35F`, amethyst `#7D6B91`.
- Hanken Grotesk for interface, **DM Mono for every number, path, hash,
  version string and percentage**. `theme/ui-semibold-font` for 600 weight —
  `-fx-font-weight: 600` silently renders at 400.
- 3px radius on controls, 6px on cards/windows. **No shadows, no gradients on
  surfaces.** One gold element per screen region.
- **Errors are gold text on `surface` with a mono code — never a red banner.**
- The legal footer is verbatim on every screen (already in `app/view`).
- Secrets never surface. No core.async. No new dependencies.
- GPL-3.0-or-later header on every new file.
- `shot/render!` **crops** oversized content silently — size renders to the
  real 1100×720 window.
- Suite is 290 tests / 694 assertions and must stay green.

## The verification that counts

Every screen ends with a PNG rendered through `ui/shot.clj` that the
implementer **opens and describes**. A green test proves the description has
the right shape; only the image proves it renders. Real component
instantiation must also be tested (`fx/create-component`) — `pr-str` tests do
not catch a nil handler or a bad prop, which has already bitten this project
twice.

---

## Task A — `ui/art.clj`: capsule and screenshot images

Catalog games carry `:art {:capsule url :screenshots [url]}`. Fetch, cache to
disk, and hand JavaFX an `Image`.

- Cache under `(config/data-dir)/art/<sha1-of-url>.jpg`. **`config/data-dir`
  is redirected in tests by JVM property — never build a path from `user.home`.**
- Fetching is off the FX thread, always. A missing or failed image resolves to
  `nil`, never an exception and never a broken-image glyph — the caller renders
  the mockup's placeholder instead.
- Bound the response size (the catalog's URLs are third-party). 8 MB is ample
  for a capsule.
- Produces: `(art/capsule game)` and `(art/screenshot game n)` → a JavaFX
  `Image` or `nil`; `(art/prefetch! game)` → starts background fetches.

## Task B — `ui/library.clj`: the grid and the side panel

**Grid** (left, fills): a filter box (280×36, `surface`, 1px `line`, 3px
radius, placeholder `Filter library`) beside a mono 11px `text-muted` count
label reading `N of M titles`. Below, a scrolling grid of cards,
`minmax(168px, 1fr)` equivalent, 18px gaps.

**Card**: 6px radius, `surface` fill, 1px `line` border — `gold` border when
selected. Capsule art at aspect 2/3 with a 1px `line` bottom border; when art
is unavailable, the mockup's diagonal hatch placeholder with a mono 10px
`capsule art` chip. Below: title (13px, semibold, `text`, ellipsised) and a
mono 11px row with `app <appid>` left and size right, both `text-muted`.

**Ownership**: games the account does not own render muted and are not
selectable, with a plain reason. Owned appids come from
`session/owned-appids`; when there is no session, treat every game as owned
rather than blocking the UI.

**Side panel** (400px, `surface`, 1px `line` left border), shown only when a
game is selected: title 19px bold; mono 12px meta `studio · app N · K builds
retained`. Then `Version` in mono 11px uppercase tracked `text-muted`, and a
version row per catalog version — 3px radius, `surface`, 1px `line`, gold-ish
border when selected, a 9px dot (`gold` selected / `line-strong` not), label
13px semibold, mono 11px `build N · date`, size right. **A version with
`bytes` 0 or `build` "" renders `size unknown` / `build unknown`, never
"0.0 GB"** — community-sourced versions genuinely lack those.

Footer of the panel: `Install to` in mono 11px uppercase tracked, the chosen
path in mono 12px ellipsised, a `Change…` secondary button, and the primary
download button — full width, 44px, gold with `#0C0C0C` label, reading
`Download <size>`, disabled and `surface`/`text-muted` when no version is
selected.

## Task C — `ui/download.clj`: progress, and the interrupted state

Renders **only** from a `download/snapshot` map:
`{:stage :bytes-done :bytes-total :chunks-done :chunks-total :wire-bytes
:bytes-per-sec :wire-bytes-per-sec :samples :error}`. All rates are B/s;
`:samples` is up to 48 B/s values. The UI scales for display.

**Header row**: kicker (`Downloading` / `Paused`) mono 11px uppercase tracked
`text-muted`; game title 25px bold; mono 12px `label · build N · size`.

**Sparkline**: 260×44, 48 bars, 2px gaps, bottom-aligned on a 1px `line`
baseline, heights scaled to the peak. The most recent ~5 bars `gold`, the rest
`rgba(194,163,95,.38)`; all `line-strong` when paused. Above it, mono 10px
uppercase `Throughput` and `N MB/s peak`.

**Right of it**: `Time remaining` over a 34px mono clock (`mm:ss`, tabular,
`text`; `--:--` and `text-muted` when paused), and `Complete` over a 34px mono
`gold` percentage.

**Bar**: 6px, 2px radius, `line` track, `gold` fill, `amethyst` at 100%.
Beneath, a mono 12px `text-muted` row: bytes done/total, speed, eta, stage.

**Stage panel**: fills the rest — 6px radius, 1px `line`. Shows a rotating
screenshot from `art`, with a bottom gradient scrim and, over it, the quote:
mono 11px uppercase `gold` `Overheard in <title>`, the quote 21px `text` (max
64ch), and mono 12px `text-muted` attribution. Top-left a mono 10px shot
label chip; top-right one 6px dot per screenshot, current one `gold`. **When
art is unavailable, fall back to the flat `surface` panel with the quote
still readable** — never a broken image. A game with no quotes shows the
stage text alone.

**Interrupted state** (`:error` present): replace the stage panel with a
panel — 1px `line-strong`, 6px radius, `surface` — carrying `Download
interrupted` in mono 11px uppercase **`gold`**, the message at 18px `text`
(max 60ch), a mono 12px line `<code> · <N> kept on disk · nothing needs to be
re-fetched`, then `Resume download` (gold primary) and `Back to library`
(secondary). **Never a red banner.**

A `Cancel` secondary button sits bottom-right while running.

## Task D — `ui/done.clj`

Centred: a 54px circle, 2px `amethyst` border, `amethyst` ✓ at 24px; `<title>
is ready` at 24px bold; mono 12px `label · build N · size verified`; the
install path in mono 12px inside a 3px-radius `surface` box with a 1px `line`
border; then `Open folder` (gold primary, 40px) and `Back to library`
(secondary). After opening, a 12px `text-muted` line `Opened in your file
browser.`

`Open folder` uses `java.awt.Desktop` when supported, else `xdg-open`; a
failure surfaces as a gold `:error` line, never a crash.

## Task E — wire it together

`main.clj` gains real navigation: `:screen` drives `view`
(`:login | :library | :download | :done`), replacing `signed_in.clj`, which is
deleted. Selecting a game and version, choosing a folder (a `DirectoryChooser`),
and pressing Download starts `reliquary.download/execute!` on a background
thread with a `ScheduledExecutorService` polling `download/snapshot` at 250ms
into the state atom via `fx-run!`. Cancel sets the engine's flag. Completion
moves to `:done`; an engine error populates `:error` and shows the interrupted
state. The last-used folder persists via `config`.

**Live gate:** run the app, sign in (a valid token already exists), pick
Stardew Valley, download a real version to a temp folder, and confirm the
progress screen animates, the done screen appears, and the files are on disk.
Screenshot every screen from the RUNNING app and read them.
