#!/usr/bin/env python3
"""Pull real depot ids + manifest GIDs from api.steamcmd.net (a public PICS proxy).

No auth, no Cloudflare, no SteamDB. Cross-validated against a manifest GID
captured from live Steam: app 489830 depot 489831 -> 8442952117333549665.

Every branch Steam publishes becomes a version. Branches with pwdrequired are
skipped -- we cannot fetch a manifest we have no password for, and offering a
version that always fails is worse than not offering it.
"""
import json
import os, sys, urllib.request, datetime

UA = {"User-Agent": "reliquary-catalog/0.1"}

def pics(appid):
    req = urllib.request.Request(f"https://api.steamcmd.net/v1/info/{appid}", headers=UA)
    with urllib.request.urlopen(req, timeout=40) as r:
        return json.load(r)["data"][str(appid)]

#: Steam ships one depot per localization. We install English, so every OTHER
#: language is dead weight -- and worse than dead weight, because localization
#: depots collide. Measured across this catalog, the non-English depots were
#: 90% of Skyrim's download (36.5 GB of 40.7), 83% of Fallout: New Vegas's,
#: 80% of Fallout 3's, 61% of Fallout 4's and 43% of Cyberpunk's.
#:
#: The collision is not incidental. Skyrim Special Edition ships
#: `Skyrim_Default.ini` in its CORE depot AND in every one of its eight
#: language depots -- each localization overwrites the same file with its own
#: language setting. Selecting them all makes the same destination path appear
#: nine times, which plan/build rejects outright (it will not plan a copy of a
#: path onto itself). So this is what produced BOTH the absurd sizes and the
#: "lists the same path twice" failure.
#:
#: English is NOT always in the unlabelled core depot, so "drop every depot
#: with a language" is wrong and would silently ship broken installs: Fallout 4
#: keeps 3.8 GB of English voice in depot 377164, Skyrim 1.5 GB in 72853
#: against a 4.2 GB core, and Fallout: New Vegas 4.8 GB across seven
#: english-tagged DLC depots. Hence: no language, OR english.
def wanted_language(cfg):
    """Is this depot's localization one we install? Unlabelled depots are
       shared content and always wanted; labelled ones only if English."""
    lang = (cfg.get("language") or "").strip().lower()
    return (not lang) or lang == "english"

#: Depots Steam itself refuses to hand a key for, recorded by verify_depots.clj
#: -- see its docstring. These are listed in a game's depot table with no
#: marker of any kind and still belong to something the player did not buy: the
#: Morrowind Soundtrack (a type=Music app), a Fallout: New Vegas depot owned by
#: the separate `New Vegas PCR` SKU. One of the two has no static tell at all,
#: so PICS cannot answer this and only Steam can.
def license_denied(appid):
    p = f"{os.path.dirname(os.path.abspath(__file__))}/license-denied-depots.json"
    if not os.path.exists(p):
        return set()
    return set(json.load(open(p)).get(str(appid)) or [])

#: Depots marked `optional` that are nonetheless required to have a game that
#: runs. `optional` on its own is not a DLC marker -- real DLC carries
#: `dlcappid` too -- and Valve uses a bare `optional` depot for the CEG-wrapped
#: executable of some older titles. Skyrim's 72852 holds exactly one file,
#: TESV.exe, 18 MB; dropping it built a 6 GB install of Skyrim with no
#: executable in it at all.
#:
#: Curated by hand and deliberately narrow, because the same flag genuinely
#: does mean optional elsewhere in this catalog: New Vegas 72731 is Dead
#: Money's audio, and New Vegas 22387 and Fallout 3 22376 are refused by Steam
#: outright. Keeping every `optional` depot would pull all of those into a base
#: install. Only depots verified to hold required content belong here.
REQUIRED_OPTIONAL_DEPOTS = {
    72850: {72852},   # Skyrim -- TESV.exe
}

def windows_depots(depots, branch, denied=frozenset(), required=frozenset()):
    """Depots this app installs on Windows that carry a manifest for `branch`."""
    out = []
    for k, v in depots.items():
        if not (k.isdigit() and isinstance(v, dict)):
            continue
        if v.get("dlcappid"):
            continue
        if v.get("optional") and int(k) not in required:
            continue
        # A shared redistributable belongs to ANOTHER app, so its key has to be
        # requested under that app's id. Selected here, it would be requested
        # under the game's -- and denied. Both README.md and verify_depots.clj
        # already said these were "dropped on sight"; only the code disagreed.
        # Latent rather than live: no currently-selected version lists one.
        if v.get("depotfromapp") or v.get("sharedinstall"):
            continue
        cfg = v.get("config") or {}
        oslist = cfg.get("oslist")
        if oslist and "windows" not in oslist:
            continue
        if not wanted_language(cfg):
            continue
        if int(k) in denied:
            continue
        m = (v.get("manifests") or {}).get(branch)
        if not m or not m.get("gid"):
            continue
        out.append({"depot-id": int(k),
                    "manifest-gid": str(m["gid"]),
                    "bytes": int(m.get("size") or 0)})
    return out

def versions_for(appid):
    app = pics(appid)
    denied = license_denied(appid)
    depots = app.get("depots") or {}
    branches = depots.get("branches") or {}
    vs = []
    for name, b in sorted(branches.items(), key=lambda kv: -int(kv[1].get("timeupdated") or 0)):
        if b.get("pwdrequired"):
            continue
        sel = windows_depots(depots, name, denied,
                             REQUIRED_OPTIONAL_DEPOTS.get(appid, frozenset()))
        if not sel:
            continue
        ts = b.get("timeupdated")
        date = (datetime.datetime.fromtimestamp(int(ts), datetime.UTC).strftime("%Y-%m-%d")
                if ts else None)
        desc = (b.get("description") or "").strip()
        label = ("Latest — public" if name == "public"
                 else desc or name.replace("_", " "))
        vs.append({"id": name, "label": label, "branch": name,
                   "build": str(b.get("buildid") or ""), "date": date,
                   "bytes": sum(d.pop("bytes") for d in sel),
                   "depots": sel})
    # Every depot we are DELIBERATELY leaving out for being a non-English
    # localization, recorded so assemble.py can apply the same rule to the
    # hand-curated versions-historical/ lists. Those name their depots by id
    # only, with no PICS metadata attached, so without this they would keep
    # shipping the language depots this function drops -- which is exactly
    # what happened: Fallout 4's 1.10.163 arrived from a downgrade guide with
    # 27 depots, fourteen of them French, German, Italian, Spanish, Russian,
    # Brazilian or Japanese.
    foreign = sorted(int(k) for k, v in depots.items()
                     if k.isdigit() and isinstance(v, dict)
                     and not wanted_language(v.get("config") or {}))
    return {"appid": appid, "name": app["common"]["name"],
            "foreign-language-depots": foreign, "versions": vs}

if __name__ == "__main__":
    for arg in sys.argv[1:]:
        domain, appid = arg.split(":")
        try:
            r = versions_for(int(appid))
        except Exception as e:
            print(f"  {domain:22} FAILED {e}", file=sys.stderr); continue
        p = f"{os.path.dirname(os.path.abspath(__file__))}/versions/{domain}.json"
        json.dump(r, open(p, "w"), indent=1)
        print(f"  {domain:22} {r['name'][:34]:34} {len(r['versions'])} version(s): "
              + ", ".join(f"{v['id']}({v['bytes']/2**30:.1f}G)" for v in r["versions"]))
