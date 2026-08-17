#!/usr/bin/env python3
"""Merge the three sources into resources/catalog.json (schema version 1).

  games/<domain>.json               appid, title, studio, art, quotes   (agents)
  versions/<domain>.json            branches Steam publishes            (PICS)
  versions-historical/<domain>.json community-sourced older builds      (agents)

reliquary.catalog/parse rejects the WHOLE document if ANY game fails
validation, so this script validates every game the same way the app does and
refuses to emit a catalog that the app would then silently ignore.
"""
import json, glob, os, re, sys, datetime

ROOT = "/home/tbaldrid/oss/reliquary"
TOOL = f"{ROOT}/tool/catalog"
SCHEMA = 1


def to_edn(v, indent=0):
    """Emit EDN. The catalog is Clojure data, so it is stored as Clojure data:
    read by clojure.edn/read-string with no parser dependency, and keywords
    instead of stringly-typed keys."""
    pad = " " * indent
    if isinstance(v, dict):
        if not v:
            return "{}"
        return "{" + ("\n" + pad + " ").join(
            f":{k} {to_edn(val, indent + 2 + len(str(k)))}" for k, val in v.items()) + "}"
    if isinstance(v, list):
        if not v:
            return "[]"
        return "[" + ("\n" + pad + " ").join(to_edn(x, indent + 1) for x in v) + "]"
    if isinstance(v, bool):
        return "true" if v else "false"
    if v is None:
        return "nil"
    if isinstance(v, (int, float)):
        return str(v)
    return '"' + str(v).replace("\\", "\\\\").replace('"', '\\"') + '"'

U64 = 18446744073709551615

# Games that are primarily online/multiplayer. Reliquary archives a build so it
# can be played later; a game whose servers are the product does not survive
# that way, and offering an old build of one is a promise we cannot keep.
ONLINE_ONLY = {"helldivers2", "starwarsbattlefront22017"}


def clean_label(vid, raw):
    """A version's name, as a version number wherever one exists.

    Steam branch descriptions are free text written by publishers, so they
    arrive as anything from a bare number to a full sentence:

        "Patch 2.21 [Windows]"
        "1.10.163 - pre-next-gen (F4SE-stable)"
        "The legacy 1.6.8 version of Stardew Valley."
        "" (BG3 gives none, leaving the raw branch id release_patch_7_hf3)

    A version picker wants the number, not the prose. Extract it; fall back to
    a tidied branch id only when there is genuinely no number to find."""
    if vid == "public":
        return "Latest"

    text = (raw or "").strip()
    hay = f"{text} {vid}"

    qualifier = ""
    if re.search(r"\blegacy\b", hay, re.I):
        qualifier = " (legacy)"
    if re.search(r"32.?bit|\bxna\b", hay, re.I):
        qualifier = " (32-bit)"

    # a dotted version number anywhere in the description
    m = re.search(r"\b(\d+\.\d+(?:\.\d+)*)\b", text)
    if m:
        return m.group(1) + qualifier
    # or an underscored one in the branch id: legacy_1_6_8, 1_63_legacy_patch
    m = re.search(r"(\d+(?:[._]\d+)+)", vid)
    if m:
        return m.group(1).replace("_", ".") + qualifier

    # when the qualifier is the whole story, do not repeat it: Steam's
    # "compatibility" branch with a 32-bit qualifier is just "32-bit".
    if qualifier and vid in ("compatibility", "previous_version"):
        return qualifier.strip(" ()").capitalize()

    # no number anywhere -- tidy the branch id: release_patch_7_hf3 -> Patch 7 hf3
    name = re.sub(r"^(release|branch)[_-]", "", vid).replace("_", " ")
    name = re.sub(r"\bpatch\s*(\d)", r"Patch \1", name, flags=re.I)
    return (name[:1].upper() + name[1:]) + qualifier


def clean_title(title):
    """Steam's store title, minus the legal furniture.

    Steam titles carry trademark marks and SKU suffixes that say nothing to
    someone picking a game off a shelf: "The Elder Scrolls IV: Oblivion(R) Game
    of the Year Edition (2009)". The appid is shown alongside, so the edition is
    still identifiable; the title is for reading."""
    t = title
    for mark in ("\u2122", "\u00ae", "\u00a9"):
        t = t.replace(mark, "")
    t = re.sub(r"\s*[:-]?\s*Game of the Year Edition\b", "", t, flags=re.I)
    t = re.sub(r"\s*\((?:19|20)\d\d\)\s*$", "", t)
    return re.sub(r"\s{2,}", " ", t).strip(" -:")


def nexus_rank():
    """{domain -> mods count} from the Nexus games list, for ordering."""
    try:
        top = json.load(open(f"{TOOL}/top100.json"))
        return {g["domain"]: g.get("mods", 0) for g in top}
    except Exception:
        return {}


def load(p):
    return json.load(open(p)) if os.path.exists(p) else None

def norm_depots(depots):
    out = []
    for d in depots:
        gid = str(d["manifest-gid"])
        if not (gid.isdigit() and int(gid) <= U64):
            raise ValueError(f"manifest-gid is not a uint64: {gid!r}")
        out.append({"depot-id": int(d["depot-id"]), "manifest-gid": gid})
    return out

def drop_foreign(depots, foreign):
    """Strip non-English localization depots from a hand-curated depot list.

    versions-historical/ entries come from community downgrade guides, which
    list depot ids with no PICS metadata, so the language filter in
    fetch_versions.py cannot see them. Fallout 4's 1.10.163 arrived with 27
    depots, fourteen of them French, German, Italian, Spanish, Russian,
    Brazilian or Japanese. Beyond the wasted gigabytes those depots collide:
    localizations overwrite each other's files by design, and plan/build
    refuses a download whose depots disagree about what belongs at a path."""
    return [d for d in depots if int(d["depot-id"]) not in foreign]

def build_game(domain):
    g = load(f"{TOOL}/games/{domain}.json")
    if not g or not g.get("appid"):
        return None, "no appid (not on Steam, or entry missing)"
    appid = int(g["appid"])
    # Guard the seam between the two sources. The agents pick the SKU (base vs
    # GOTY vs remaster); the PICS fetch was run against a separately-chosen
    # appid. If those disagree, merging them ships manifest GIDs belonging to a
    # DIFFERENT application -- silently downloading the wrong game.
    for src in ("versions", "versions-historical"):
        v = load(f"{TOOL}/{src}/{domain}.json")
        if v and int(v["appid"]) != appid:
            raise ValueError(
                f"appid mismatch: games/ says {appid}, {src}/ says {v['appid']}")
    pics_doc = load(f"{TOOL}/versions/{domain}.json") or {}
    foreign = set(pics_doc.get("foreign-language-depots") or [])
    versions = []
    for v in pics_doc.get("versions", []):
        versions.append({"id": v["id"], "label": clean_label(v["id"], v["label"]),
                         "branch": v["branch"],
                         "build": str(v.get("build") or ""), "date": v.get("date"),
                         "bytes": int(v.get("bytes") or 0),
                         "depots": norm_depots(v["depots"])})
    # A historical entry whose depots match the current build IS the current
    # build: Skyrim SE's 1.6.1170 is byte-identical to "Latest - public" on
    # all three shared depots. Offering both asks the user to choose between
    # two identical downloads.
    current = {int(d["depot-id"]): str(d["manifest-gid"])
               for ver in versions if ver["id"] == "public"
               for d in ver["depots"]}
    for v in (load(f"{TOOL}/versions-historical/{domain}.json") or {}).get("versions", []):
        dep = {int(d["depot-id"]): str(d["manifest-gid"]) for d in v["depots"]}
        if dep and all(current.get(k) == gid for k, gid in dep.items()):
            continue
        # Do NOT silently drop weakly-sourced versions. The catalog has no
        # confidence field, and the label is the only channel that reaches the
        # user at the point of choice -- so uncertainty is spelled out there.
        # Cross-check that justifies keeping them: this source's 1.6.1170 entry
        # matches Steam's own PICS data byte-for-byte on all three depots.
        conf = v.get("confidence")
        # The version row in the UI is one line beside a size and a build id --
        # keep the label to the version itself and drop the agents' explanatory
        # tails, which belong in a report rather than a 400px side panel.
        short = re.split(r"\s+(?:--|—)\s+", v["label"], maxsplit=1)[0].strip()
        # No confidence suffix. It was well-intentioned -- those manifest IDs
        # really do come from one source -- but "(single source)" in a version
        # picker reads as a warning about the GAME, and the user cannot act on
        # it either way. Provenance stays in versions-historical/, where the
        # catalog's maintainer can see it and the player is not asked to.
        label = clean_label(v["id"], short)
        versions.append({"id": v["id"], "label": label,
                         # a historical manifest still lives on the public branch --
                         # that is the branch its request code must be asked for
                         "branch": "public",
                         "build": str(v.get("build") or ""), "date": v.get("date"),
                         "bytes": int(v.get("bytes") or 0),
                         "depots": norm_depots(drop_foreign(v["depots"], foreign))})
    if not versions:
        return None, "no versions from any source"
    return {"appid": appid, "title": clean_title(g["title"]), "studio": g.get("studio"),
            "art": {"capsule": g["art"]["capsule"],
                    "screenshots": list(g["art"]["screenshots"])},
            "quotes": [{"text": q["text"], "attrib": q["attrib"]} for q in g["quotes"]],
            "versions": versions}, None

def main():
    rank = nexus_rank()
    domains = [os.path.basename(p)[:-5] for p in glob.glob(f"{TOOL}/games/*.json")]
    dropped = sorted(d for d in domains if d in ONLINE_ONLY)
    domains = [d for d in domains if d not in ONLINE_ONLY]
    # Most-modded first: this is a tool for people who mod games, and the Nexus
    # mod count is the closest thing to a measure of that.
    domains.sort(key=lambda d: (-rank.get(d, 0), d))
    games, skipped = [], []
    for d in domains:
        try:
            g, why = build_game(d)
        except Exception as e:
            g, why = None, f"{type(e).__name__}: {e}"
        (games.append(g) if g else skipped.append((d, why)))
    cat = {"schema-version": SCHEMA,
           "generated": datetime.datetime.now(datetime.UTC).strftime("%Y-%m-%dT%H:%M:%SZ"),
           "games": games}
    out = f"{ROOT}/resources/catalog.edn"
    open(out, "w").write(to_edn(cat) + "\n")
    print(f"wrote {out}: {len(games)} games, {sum(len(g['versions']) for g in games)} versions")
    for g in games:
        print(f"  {g['title'][:38]:38} {len(g['versions'])} version(s), {len(g['quotes'])} quotes")
    if dropped:
        print("\nEXCLUDED (primarily online/multiplayer):")
        for d in dropped:
            print(f"  {d}")
    if skipped:
        print("\nSKIPPED (would have made the whole catalog invalid):")
        for d, why in skipped:
            print(f"  {d}: {why}")

main()
