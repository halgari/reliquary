# Catalog generator

Builds `resources/catalog.edn` from three sources. **EDN, not JSON** — the
catalog is Clojure data consumed by Clojure, so it is stored as Clojure data:
`clojure.edn/read-string`, no parser dependency, keywords rather than
stringly-typed keys. (`edn/read-string`, never `read` — the document arrives
off the network and must not be able to eval anything.)

    python3 tool/catalog/fetch_versions.py <domain>:<appid> ...   # -> versions/
    clojure -M:catalog-tool -m resolve-sizes <domain>:<appid> ... # -> real sizes
    python3 tool/catalog/assemble.py                             # -> resources/catalog.edn

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
