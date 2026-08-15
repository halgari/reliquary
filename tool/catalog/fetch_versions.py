#!/usr/bin/env python3
"""Pull real depot ids + manifest GIDs from api.steamcmd.net (a public PICS proxy).

No auth, no Cloudflare, no SteamDB. Cross-validated against a manifest GID
captured from live Steam: app 489830 depot 489831 -> 8442952117333549665.

Every branch Steam publishes becomes a version. Branches with pwdrequired are
skipped -- we cannot fetch a manifest we have no password for, and offering a
version that always fails is worse than not offering it.
"""
import json, sys, urllib.request, datetime

UA = {"User-Agent": "reliquary-catalog/0.1"}

def pics(appid):
    req = urllib.request.Request(f"https://api.steamcmd.net/v1/info/{appid}", headers=UA)
    with urllib.request.urlopen(req, timeout=40) as r:
        return json.load(r)["data"][str(appid)]

def windows_depots(depots, branch):
    """Depots this app installs on Windows that carry a manifest for `branch`."""
    out = []
    for k, v in depots.items():
        if not (k.isdigit() and isinstance(v, dict)):
            continue
        if v.get("dlcappid") or v.get("optional"):
            continue
        oslist = (v.get("config") or {}).get("oslist")
        if oslist and "windows" not in oslist:
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
    depots = app.get("depots") or {}
    branches = depots.get("branches") or {}
    vs = []
    for name, b in sorted(branches.items(), key=lambda kv: -int(kv[1].get("timeupdated") or 0)):
        if b.get("pwdrequired"):
            continue
        sel = windows_depots(depots, name)
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
    return {"appid": appid, "name": app["common"]["name"], "versions": vs}

if __name__ == "__main__":
    for arg in sys.argv[1:]:
        domain, appid = arg.split(":")
        try:
            r = versions_for(int(appid))
        except Exception as e:
            print(f"  {domain:22} FAILED {e}", file=sys.stderr); continue
        p = f"/home/tbaldrid/oss/reliquary/tool/catalog/versions/{domain}.json"
        json.dump(r, open(p, "w"), indent=1)
        print(f"  {domain:22} {r['name'][:34]:34} {len(r['versions'])} version(s): "
              + ", ".join(f"{v['id']}({v['bytes']/2**30:.1f}G)" for v in r["versions"]))
