# Catalog generator

Builds `resources/catalog.edn` from three sources. **EDN, not JSON** — the
catalog is Clojure data consumed by Clojure, so it is stored as Clojure data:
`clojure.edn/read-string`, no parser dependency, keywords rather than
stringly-typed keys. (`edn/read-string`, never `read` — the document arrives
off the network and must not be able to eval anything.)

    python3 tool/catalog/fetch_versions.py <domain>:<appid> ...   # -> versions/
    clojure -M:catalog-tool -m verify-depots <domain>:<appid> ... # -> license-denied-depots.json
    python3 tool/catalog/fetch_versions.py <domain>:<appid> ...   # again, now excluding those
    clojure -M:catalog-tool -m resolve-sizes <domain>:<appid> ... # -> real sizes
    python3 tool/catalog/assemble.py                             # -> resources/catalog.edn

`verify_depots` needs a Steam session; the other steps do not. Its output
persists, so it only has to be re-run when a game is added or Valve changes a
depot table. `fetch_versions` runs twice because the first pass is what tells
`verify_depots` which depots to ask about.

| Source | What it gives | How |
|---|---|---|
| `games/<domain>.json` | appid, title, studio, art, quotes | agents, per AGENT-BRIEF.md |
| `versions/<domain>.json` | every branch Steam publishes | `api.steamcmd.net`, a public PICS proxy |
| `versions-historical/<domain>.json` | older builds Steam no longer lists | agents, from community downgrade guides |

## Why not SteamDB

Not needed. `api.steamcmd.net/v1/info/<appid>` returns full PICS product info —
depot ids, manifest GIDs, sizes, branches, build ids — with no auth. Verified
against a manifest GID captured from live Steam by mauvi and committed as a test
fixture: app 489830 depot 489831 -> `8442952117333549665`, exact match.

## Depots that are in the depot table but not in the game

Three kinds, and only the first two are visible in PICS.

**Localizations.** Steam publishes one depot per language and we install
English. These were 90% of Skyrim's download (36.5 GB of 40.7), 83% of
Fallout: New Vegas's and 61% of Fallout 4's. They also COLLIDE -- a
localization overwrites the base game's files by design, so Skyrim Special
Edition ships `Skyrim_Default.ini` in its core depot and in all eight language
depots, and `plan/build` refuses a download whose depots disagree about what
belongs at a path. Dropping every depot with a language would be wrong:
English is not always the unlabelled one. Fallout 4 keeps 3.8 GB of English
voice in depot 377164. The rule is no language, OR english.

**DLC and redistributables.** Marked plainly -- `dlcappid` + `optional`, or
`depotfromapp` + `sharedinstall` -- and dropped on sight.

**Separately-licensed extras with no marker at all.** This is what
`verify_depots.clj` is for. `22320` lists depot `451410`, which is the
Morrowind Soundtrack, a `type=Music` app; `22380` lists depot `22493`, which
belongs to `Fallout: New Vegas PCR`, a different SKU. The first is detectable
statically because the depot id is also its appid. The second is NOT: its PICS
record is `{config: {language: english}, manifests: {...}}`, the same shape as
depots 22382 and 72732, which are ordinary base-game content that works. Valve
left it unmarked, so the only authority is Steam's answer to a key request.

A refusal only counts when another depot on the SAME app was granted. All
depots refused means the account does not own the game, and recording that
would delete the game from the catalog for everybody.

## Two things that bite

**No confidence labels reach the UI.** Weakly-attested manifest IDs are marked
`confidence` in `versions-historical/`, and that is where it stays. A
"(single source)" suffix in a version picker reads as a warning about the game
rather than about our provenance metadata, and the user cannot act on it.

**A historical version identical to the current build is dropped.** Skyrim SE's
1.6.1170 is byte-identical to `Latest — public` on all three shared depots;
offering both asks the user to choose between two identical downloads.

**Manifest GIDs are uint64, not "19-20 digits."** About one in twenty is 18
digits or fewer. An earlier version of the agent brief said otherwise and cost
us a whole Skyrim version (1.6.342) before it was caught.

**The appid must agree across sources.** The agents pick the SKU (base vs GOTY
vs remaster); `fetch_versions.py` is pointed at an appid by hand. Fallout 3
diverged — agents chose GOTY (22370), the fetch used base (22300). `assemble.py`
now refuses to merge a mismatch rather than shipping another app's manifests.

## Sizes are resolved, not guessed

Downgrade guides publish `download_depot` commands, not byte counts, so
community-sourced versions arrive with no size. `resolve_sizes.clj` fixes that
once: it opens a Steam session, fetches each depot manifest, and sums the file
table. A manifest is immutable, so the answer never changes and the app never
pays for it again.

These numbers are also more accurate than PICS's. PICS reports every Windows
depot including all eleven language packs — Skyrim SE's current build reads
27.7 GB against a real English install of 12.7 GB. A resolved version sums only
the depots the catalog names.

## Known gaps in the data

- `build` is still empty for community-sourced versions — downgrade guides do
  not publish Steam build ids, and unlike size there is no way to recover one
  from a manifest. The UI renders *build unknown*.
- `bytes` for PICS-sourced current builds is still the all-language upper
  bound. Running `resolve_sizes.clj` over them would fix that too.
- `bytes` for PICS versions sums every Windows depot including all eleven
  language packs, so it is an upper bound. Skyrim SE reads 27.7 GB against a
  real install nearer 12 GB. Depot selection by language belongs in the engine.
