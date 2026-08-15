# Reliquary — design

A desktop app that downloads a chosen *version* of a Steam game to a folder of
your choosing. Steam's own client gives you exactly one build: whatever is
current on a branch you have access to. Reliquary gives you the archive.

- **Status**: design approved, ready for an implementation plan
- **Design source**: Claude Design project `dc999a84-093d-4d3d-87c7-34b3c008a16f`,
  file `Reliquary.dc.html`; brand from `uploads/Gilt Brand Spec.dc.html`
- **License**: GPL-3.0-or-later (see [Licensing](#licensing))

---

## 1. What it does

Four screens, in order:

1. **Login** — QR code on the left (scan with the Steam mobile app), account
   name + password on the right. Sign-in completes on its own; there is no
   submit button on the QR side.
2. **Library** — a grid of capsule-art cards filtered by a text box, with a
   400px side panel for the selected game: its versions, the install folder,
   and the download button.
3. **Download** — throughput sparkline, time remaining, percent complete, a
   6px progress bar, a byte/speed/eta/stage line, and a rotating screenshot
   with a quote from the game. Plus an interrupted state that resumes.
4. **Done** — the install path, an open-folder button, and a way back.

The set of games and the set of versions come from a **catalog** — a JSON
document bundled with the app and refreshed at startup from a GitHub raw URL.
Steam itself is not asked what versions exist; it is only asked to hand over
the bytes.

### Why a catalog

Steam's PICS product-info tells you the *current* manifest GID for each branch
your account can see. It does not tell you what last month's build was. The
manifest GIDs for historical builds are knowable — they are published, indexed,
and stable — but they must come from somewhere other than Steam's live
metadata. That somewhere is the catalog.

This inverts the usual shape: the catalog is the source of truth for *what
exists*, and the live Steam session is only used for *what this account is
allowed to fetch*. Those live calls cannot be pre-baked into the catalog —
depot decryption keys and manifest request codes are issued per account, on
demand, over an authenticated connection.

---

## 2. Architecture

```
                    ┌──────────────────────────────────┐
   catalog.json     │  reliquary.catalog               │
   (bundled)   ───► │  bundled → cached → fetched      │ ──┐
   GitHub raw  ───► │  newest wins, never blocks UI    │   │
                    └──────────────────────────────────┘   │
                                                           ▼
   ┌──────────────┐     ┌───────────────┐          ┌────────────────┐
   │ reliquary.ui │◄───►│ state atom    │◄─────────│ reliquary.     │
   │  (cljfx)     │     │ (one, global) │          │   download     │
   └──────────────┘     └───────────────┘          └────────┬───────┘
          │                                                 │
          │ login events                                    │ work plan
          ▼                                                 ▼
   ┌──────────────┐     ┌───────────────┐          ┌────────────────┐
   │ steam.auth   │────►│ steam.session │◄────────►│ steam.cm.*     │
   │ QR / creds   │     │ token → logon │          │ keys, codes,   │
   └──────────────┘     └───────────────┘          │ cdn host list  │
                                                   └────────┬───────┘
                                                            │
                                                   ┌────────▼───────┐
                                                   │ steam.manifest │
                                                   │ steam.chunk    │
                                                   │ steam.cdn      │
                                                   └────────────────┘
```

Every arrow crossing into the UI goes through one state atom. Nothing in the
download engine knows JavaFX exists; nothing in the UI knows what a depot is.

### Module layout

```
src/reliquary/
  error.clj              copied — one raise, categorized
  steam/                 copied from mauvi-mod-manager, trimmed
    api.clj  auth_api.clj  auth.clj  apps.clj  cdn.clj  chunk.clj
    crypto.clj  depots.clj  kv.clj  manifest.clj  proto.clj  qr.clj  vzip.clj
    cm/{client,connection,content,discovery,envelope,multi}.clj
  catalog.clj            load, refresh, validate, merge
  config.clj             ~/.config/reliquary/config.edn
  session.clj            NEW — refresh token → CM logon
  plan.clj               NEW — pure: manifest → chunk work list
  download.clj           NEW — the engine
  art.clj                capsule + screenshot fetch, disk cache
  ui/
    theme.clj  app.clj  login.clj  library.clj  download.clj  done.clj
  main.clj
resources/
  catalog.json           bundled fallback
  steam/steam.desc       protobuf descriptor set (8KB)
  fonts/                 Hanken Grotesk, DM Mono (OFL, bundled not fetched)
tool/catalog/            the generator, behind its own deps alias
```

### The copied Steam layer

`mauvi-mod-manager/src/mauvi/steam/` is copied in wholesale under the
`reliquary.steam.*` namespace, **not** referenced as a dependency. No
`:local/root`, no shared library, no cross-repo coupling. It is Reliquary's
code from the moment it lands.

What comes across (~1,500 lines, with its offline fixture tests):

| Namespace | Job |
|---|---|
| `proto` | protobuf ↔ Clojure over a committed descriptor set, via `DynamicMessage` |
| `api`, `auth_api` | `IAuthenticationService` HTTP calls |
| `auth` | both login flows to a refresh token, event-driven |
| `crypto` | JWT claims, RSA password encrypt, Steam symmetric decrypt |
| `cm/envelope`, `cm/multi` | CM message framing, batched-message expansion |
| `cm/discovery`, `cm/connection`, `cm/client` | WebSocket CM session + logon |
| `cm/content` | depot keys, manifest request codes, CDN host list |
| `apps`, `kv`, `depots` | PICS product info, Valve KeyValues, depot selection |
| `manifest` | depot manifest parse, filename decrypt, chunk table |
| `cdn` | one GET policy: host rotation, bounded retry, 4xx aborts |
| `chunk` | one chunk: fetch → AES → vzip → SHA-1 verify |
| `qr` | zxing matrix (the ANSI terminal renderer is dropped) |

What does **not** come across:

- `slice.clj` — block-offset math for FUSE streaming. Reliquary writes whole
  files; there is no block store to map onto.
- `session.clj` — its entire body is reading a token out of pulsar/RocksDB.
  Rewritten in ~20 lines against `config.clj`.

**Dropping pulsar and RocksDB is load-bearing.** A JNI native library inside
the image would have made the small-binary goal unreachable. Reliquary's
persistent state is one EDN file and one progress file per download.

One change to copied code: `cm/content/manifest-request-code` hardcodes
`:app-branch "public"`. It takes a branch argument; the catalog supplies it
per version.

Each copied file keeps a header noting its origin in mauvi, for the record.

---

## 3. The catalog

### Schema

```json
{
  "schema-version": 1,
  "generated": "2026-08-15T00:00:00Z",
  "games": [
    {
      "appid": 412990,
      "title": "Hollow Signal",
      "studio": "Vantage Interactive",
      "art": {
        "capsule": "https://…/library_600x900.jpg",
        "screenshots": ["https://…", "https://…"]
      },
      "quotes": [
        {"text": "Signal's clean. That's the part that worries me.",
         "attrib": "Corporal Vance, relay station four"}
      ],
      "versions": [
        {
          "id": "v1",
          "label": "Latest — public",
          "branch": "public",
          "build": "14882031",
          "date": "2026-07-30",
          "bytes": 36730000000,
          "depots": [{"depot-id": 412991, "manifest-gid": "8471…"}]
        }
      ]
    }
  ]
}
```

`bytes` is the uncompressed install size, used for the version list's size
label and the download screen's denominator before the manifests are parsed.
Once manifests are in hand the engine uses the real total and corrects the
display.

### Loading

Three sources, newest `generated` wins:

1. `resources/catalog.json`, compiled into the binary — always present.
2. `~/.local/share/reliquary/catalog.json`, the last good fetch.
3. The GitHub raw URL, fetched at startup.

The fetch is asynchronous with a short timeout and **never blocks the UI**. A
fetched document is used only if it parses and its `schema-version` is one this
build understands; on success it replaces the cache. A newer `schema-version`
than the build knows is ignored, not an error — an old binary keeps working.

The status line reports which source is live: `catalog · 2026-08-15` for a real
document, `catalog · bundled` when both network and cache are unavailable.

**Open item.** The raw URL is a build-time constant and the repository hosting
it does not exist yet. It must be decided before phase 3, and the choice is not
cosmetic: it is the one hostname the app contacts that is neither Steam nor a
CDN, so it wants to be a repo you control and can rotate. Until it is set,
phase 3 develops against a hand-written `resources/catalog.json` with the fetch
disabled.

### Ownership

After logon, one `apps/owned-apps` call yields the set of appids this account
licenses. Catalog games outside that set still appear in the grid — discovery
is the point — but render muted, with the download button disabled and a plain
reason rather than a failure ten seconds into a download.

This is a courtesy, not a gate. The real authority is Steam's answer to the
depot-key request, which the engine still handles as a categorized failure.

---

## 4. The download engine

### Resolving a version to work

For each depot in the selected version:

1. `cm.content/depot-key` — per account, over the CM connection.
2. `cm.content/manifest-request-code` with the version's branch.
3. `manifest/fetch` from the CDN, then `manifest/parse` with the key.

A depot Steam denies at either step is a hard failure here, unlike mauvi's
over-inclusive sync where a denial was expected. The catalog names exactly the
depots this version needs; a denial means the account cannot have this build,
and saying so immediately is better than delivering a partial install.

**Language depots are selected, not swept.** The catalog's `bytes` sums every
Windows depot including all language packs — Skyrim SE reads 27.7 GB against a
real install nearer 12 GB. The engine picks the base depots plus one language
rather than fetching eleven.

**A catalog version may not know its size or build id.** Versions sourced from
community downgrade guides carry `bytes` 0 and `build` "" because those are not
published. Render that as *unknown*; never as "0.0 GB".

### Planning (`plan.clj`, pure)

*Amended 2026-08-15 after the foundation branch's final review — the shape below
supersedes the original sketch.*

Manifest file entries flatten into a work list:

```clojure
{:download-bytes 8000        ; unique content actually fetched
 :disk-bytes     9000        ; everything written, duplicates included
 :total-chunks   12
 :dirs   ["Data"]
 :files  [{:path "Data/textures.bsa"
           :size 4194304000
           :depot-id 489831
           :sha-content "a94a8f…"
           :chunks [{:index 0 :id "3f2b…" :offset 0
                     :cb-original 1048576 :cb-compressed 812004}]}]
 :copies [{:path "b.bsa" :source "a.bsa" :size 4096}]}
```

Two byte totals, not one: `:download-bytes` is what crosses the network,
`:disk-bytes` is what lands. They differ whenever depots repeat content, and
conflating them makes a progress bar that lies.

Directories become `:dirs`. Files sharing a content SHA-1 are downloaded once
and copied. `:index` is the stable chunk identity the resume file records.

**No depot keys in the plan.** The plan map is what the engine serializes into
progress files and error snapshots; a depot key is a secret under §9's rule and
must not ride along. Keys travel separately as a `{depot-id -> key-hex}` map.

**`:cb-compressed` is retained.** `chunk/fetch-decoded` reports wire bytes while
the plan counts decompressed bytes; without the compressed size the two cannot
be reconciled and the progress bar cannot reach 100%.

**`build` validates its own invariants.** Chunks must tile each file exactly —
no gap, no overlap, nothing past the file's size — and `build` raises
`:incorrect` when they do not. Properties alone proved insufficient: a generator
that constructs offsets by accumulation cannot produce a violation, so the
invariant went unenforced and a gapped manifest planned a file with a hole in
it. The properties remain, over a generator that shuffles chunk order so an
implementation ignoring declared offsets is detectable.

**Entry classification reads observable structure, not flag constants.**
`manifest.clj` deliberately refuses to define `EDepotFileFlag` values, and a
symlink entry (no chunks, no content SHA, a link target) misclassified by a
`flags`-only test becomes an empty regular file. Classify on what the entry
actually carries.

**A path may appear once.** Two entries claiming one destination path is
malformed remote input with no safe resolution, and raises `:incorrect` — a
copy whose source equals its destination would truncate the source before
reading it.

### Executing

Files are created and preallocated with `setLength` before any chunk is
fetched, so a full disk fails immediately rather than at 94%.

A fixed `ExecutorService` (default 8 workers, configurable) pulls chunk jobs
from a queue. Each worker calls `chunk/fetch-decoded` — CDN fetch, AES decrypt,
vzip decompress, SHA-1 verify — and writes the result at the chunk's offset
with positional `FileChannel.write`, which is thread-safe across threads
without locking.

No `core.async`. A queue and an executor is the whole requirement, and every
dependency omitted is one less native-image risk.

**There is no separate verification pass.** A Steam chunk's id *is* the SHA-1
of its decoded plaintext, and `chunk/fetch-decoded` already refuses any chunk
that fails it or decodes to the wrong length. Re-hashing whole files afterward
would verify nothing new at considerable cost. The mock's `verifying` stage
label at >96% is replaced by text describing what is actually happening.

### Resume

`<dest>/.reliquary/<appid>-<version>.progress` records completed chunk indices
per file, flushed atomically (write-temp-then-rename) every few seconds and on
clean cancel. On restart the plan is rebuilt from the same manifests and
completed chunks are skipped.

This is what makes the interrupted screen's claim — *nothing needs to be
re-fetched* — literally true. It is a claim the app should not make unless the
format guarantees it, so the progress file is written before the bytes it
describes are acknowledged, never after.

### Progress and control

One atom holds `{:bytes-done :bytes-total :chunks-done :chunks-total :stage
:error :samples}`. A scheduled sampler at 250ms computes instantaneous MB/s,
maintains the 48-sample ring the sparkline draws, and pushes a snapshot to the
UI through `Platform/runLater`. The UI reads snapshots; it never reads engine
internals.

Cancel is an `AtomicBoolean` checked between chunks — in-flight chunks finish
rather than being torn out, so the progress file stays truthful. A worker
exception sets `:error`, drains the queue, and leaves everything on disk for
resume.

---

## 5. The UI

cljfx, driven by one state atom, styled from the Gilt tokens in
`ui/theme.clj`. Fonts are bundled, not fetched — a downloader that needs Google
Fonts to render is a downloader that looks broken offline.

### Gilt tokens

| Token | Hex | Role |
|---|---|---|
| bg | `#0C0C0C` | app background, content areas |
| surface | `#161616` | title bar, status bar, cards, active rows |
| line | `#292929` | dividers, empty progress track, inputs |
| line-strong | `#383838` | secondary button borders, focus rings |
| text | `#F2F0EE` | body and headings |
| text-muted | `#9A9A9A` | metadata, log output, inactive |
| gold | `#C2A35F` | primary action, in progress |
| amethyst | `#7D6B91` | completed, the logo mark |

Hanken Grotesk for interface, DM Mono for every number, path, hash, version
string and percentage. 3px radius on controls, 6px on cards and windows. No
shadows, no gradients on surfaces. One gold element per screen region. Errors
are gold text on surface with a mono code — never a red banner.

### Where the mock and reality disagree

Two places, both requiring a deliberate departure:

**The QR is 21×21 in the mock.** That is QR version 1, which holds about 25
alphanumeric characters — far less than a Steam challenge URL. Real ones render
at 29–37 modules. We draw zxing's actual matrix scaled to the same physical box
via `qr/module-matrix`, onto a JavaFX `Canvas`. It looks identical and it
scans.

**The password panel has no code field**, and its caption says two-factor is
handled in the mobile app. That is true for confirmation types 4 and 5, which
are approved elsewhere. It is not true for types 2 and 3 — an emailed code or
an authenticator code must be *typed*, and `auth/login-credentials!` blocks
until it gets one. So the panel swaps its password field for a code field when
Steam asks, in the same visual language. Without this, credential login hangs
with no explanation.

### Login

`auth/login-qr!` and `auth/login-credentials!` are blocking and fire events.
They run on a background thread; the `on-event` callback marshals to the FX
thread to update state. The credential flow's `:guard-needed` event must
*return* the code, so it blocks on a promise the code field delivers.

On success the refresh token is written to `config.edn` at mode 0600 and the
CM logon proceeds. The token is never logged, never put in an error map, and
never rendered.

### Library and download

The library grid is the catalog list filtered by the search box, with
ownership marking. Selecting a game opens the side panel: versions from the
catalog, the install folder, and the download button labelled with the
version's size.

The download screen renders from progress snapshots. Screenshots rotate from
the catalog's art URLs, cached to disk by `art.clj`; the caption carries a
catalog quote. When artwork is unavailable — offline, or a game with no art in
the catalog — the screen falls back to the mock's flat-surface path rather than
showing a broken image.

---

## 6. Native image

The premise is a small single-file app. It is worth stating the expectation
plainly: a JavaFX native image embeds the JavaFX native libraries, and
realistic Linux binaries land around **40–80 MB**, against roughly 60–90 MB for
a jlink'd JRE bundle. The wins that are unambiguous are startup time, memory
footprint, and shipping one file with no JRE. The size win is real but modest,
and the design should not be built on the assumption that it is dramatic.

Nothing is installed on this machine today: Java 26 OpenJDK, no GraalVM, no
JavaFX. Native images cannot cross-compile, so Linux and Windows each need
their own build host.

**Toolchain**: Liberica NIK Full (which bundles OpenJFX) pinned by
`bin/setup-toolchain.sh`, with `org.graalvm.buildtools` native config. AOT with
`-Dclojure.compiler.direct-linking=true` and
`--features=clj_easy.graal_build_time.InitClojureClasses`, plus
`--enable-url-protocols=https` and reflection config collected by the tracing
agent.

**Risk register** — each of these is a thing that has broken someone's native
image before, and each is checked by the spike:

- cljfx's multimethod registry and dynamic resolution
- protobuf `DynamicMessage` descriptor reflection
- `java.net.http` WebSocket over TLS
- zxing, and the XZ/LZMA decoder in `vzip`
- JavaFX's own reflection and native library loading

**Fallback ladder** if the spike fails: cljfx → raw JavaFX interop → jlink +
jpackage, dropping the single-binary goal but keeping everything else. The
spike exists so we find out while the fallback is still cheap.

---

## 7. Testing

The copied Steam tests come along unchanged — they are offline fixture tests
against real captured Steam data, and they are the reason this layer can be
trusted without a live account.

New coverage:

- `plan.clj` — property tests on the tiling invariants above
- `catalog.clj` — parse, validate, three-source merge, unknown schema version,
  malformed fetch, offline
- resume — progress-file round trip, and a resumed download producing bytes
  identical to an uninterrupted one
- `download.clj` — against a local HTTP fixture server serving captured chunks,
  including a mid-download failure and its resume
- UI — JavaFX `snapshot` under xvfb, compared against renders of the design
  mockup, in the spirit of mauvi's screenshot gate

Two phases need a real Steam account and a small owned game: the login screen
and the first real download. **Pick that game before starting phase 2.**

---

## 8. Build order

| # | Phase | Done when |
|---|---|---|
| 0 | **Spike** | A native binary opens a cljfx window *and* a native binary logs on to Steam and prints licenses. Binary size measured and recorded. |
| 1 | Skeleton | deps.edn, copied Steam layer, `error`, `config`; copied tests green |
| 2 | Login | QR and credential flows both reach a stored refresh token |
| 3 | Catalog + library | Grid renders from catalog with ownership marking; side panel lists versions |
| 4 | Engine (headless) | A CLI entry downloads one small game to disk, resumes after a kill, cancels cleanly |
| 5 | Download screen | Wired to engine snapshots: sparkline, ETA, stage, art, quotes, interrupted state |
| 6 | Done screen | Open-folder, settings for worker count and folder list |
| 7 | Catalog generator | `tool/catalog` emits a catalog.json for the GitHub repo |
| 8 | Packaging | Linux binary, then Windows |

Phase 0 gates everything. Phases 1–6 keep a native build green continuously so
drift never exceeds one commit.

---

## 9. Licensing

GPL-3.0-or-later. `LICENSE` holds the canonical FSF text.

**One open decision.** Clojure is EPL-1.0, which is not GPL-compatible under a
strict reading. Many GPL'd Clojure projects ship regardless, and as sole
copyright holder you can settle it outright by adding a linking exception to
the notice:

> As a special exception, you may link this program with the Clojure runtime
> and distribute the result, without those libraries falling under the terms of
> the GPL.

This design assumes that clause is included. Strike it if you would rather not.

The rest of the stack is clean: protobuf is BSD-3, zxing Apache-2.0, XZ public
domain, and GraalVM, Liberica NIK and JavaFX are GPLv2-with-Classpath-Exception,
which explicitly permits the linking a native image performs.

The copied Steam code is your own work from mauvi, so relicensing it here is
yours to do.

---

## 10. Legal footer

The design carries it and the app must keep it, verbatim, on every screen:

> Not associated with or endorsed by Valve Corporation or Steam.

Reliquary downloads content the signed-in account already owns, using that
account's own credentials, through Steam's own CDN. It does not circumvent
licensing, and the depot-key call is the enforcement point — Steam refuses
accounts that do not own the content, and Reliquary surfaces that refusal
rather than working around it.
