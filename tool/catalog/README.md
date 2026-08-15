# Catalog generator

Builds `resources/catalog.json` from three sources.

    python3 tool/catalog/fetch_versions.py <domain>:<appid> ...   # -> versions/
    python3 tool/catalog/assemble.py                             # -> resources/catalog.json

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

**Manifest GIDs are uint64, not "19-20 digits."** About one in twenty is 18
digits or fewer. An earlier version of the agent brief said otherwise and cost
us a whole Skyrim version (1.6.342) before it was caught.

**The appid must agree across sources.** The agents pick the SKU (base vs GOTY
vs remaster); `fetch_versions.py` is pointed at an appid by hand. Fallout 3
diverged — agents chose GOTY (22370), the fetch used base (22300). `assemble.py`
now refuses to merge a mismatch rather than shipping another app's manifests.

## Known gaps in the data

- Community-sourced historical versions carry no `bytes` and no `build`; those
  are not published in downgrade guides. They serialize as `0` and `""`. The UI
  must render that as *unknown*, never as "0.0 GB".
- `bytes` for PICS versions sums every Windows depot including all eleven
  language packs, so it is an upper bound. Skyrim SE reads 27.7 GB against a
  real install nearer 12 GB. Depot selection by language belongs in the engine.
