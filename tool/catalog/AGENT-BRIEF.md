# Catalog entry brief (shared by every game agent)

Reliquary downloads a chosen *version* of a Steam game. While a download runs it
shows a rotating screenshot with a quote about the game. This catalog supplies
all of that.

## Output

ONE file per game at `tool/catalog/games/<nexus_domain>.json`, this exact shape:

```json
{
  "nexus_domain": "fallout4",
  "nexus_name": "Fallout 4",
  "appid": 377160,
  "title": "Fallout 4",
  "studio": "Bethesda Game Studios",
  "art": {
    "capsule": "https://cdn.cloudflare.steamstatic.com/steam/apps/377160/library_600x900.jpg",
    "screenshots": ["https://…"]
  },
  "quotes": [{"text": "…", "attrib": "…"}],
  "notes": "anything a human should know about this entry"
}
```

- `appid` — the BASE GAME. Not a Creation Kit, not DLC, not a Deluxe/Definitive
  SKU, not a soundtrack, not a demo. Verify with
  `curl -s "https://store.steampowered.com/api/appdetails?appids=<ID>"` and check
  `"type":"game"`. Resolve candidates with
  `curl -s -G --data-urlencode "term=<name>" --data "cc=us&l=en" https://store.steampowered.com/api/storesearch/`.
  If the game genuinely is not on Steam, set `"appid": null` and say why in
  `notes`. **Never guess an appid.**
- `title`, `studio` — verbatim from the appdetails response for that appid.
- `art.capsule` — exactly `https://cdn.cloudflare.steamstatic.com/steam/apps/<appid>/library_600x900.jpg`
- `art.screenshots` — 4-6 `path_full` URLs from appdetails, with any `?t=…`
  cache-buster stripped. Verify at least one returns HTTP 200.
- `quotes` — exactly 8.
- NO `versions` key. Manifest IDs come from a separate source.

## The quotes

These scroll past while someone waits on a 60 GB download. Make that person
smile. Register: a well-written game-wiki trivia section — dry, specific,
affectionate. Mix roughly evenly:

- **Real in-game lines** players would recognise, attributed to the character.
- **True development trivia** that is funny *because* it is true.
- **Wry observations about how people actually play**, attributed like
  `every player, eventually`.

### Hard rules

- **Everything factual must be TRUE.** Web-search anything you are not sure of.
  Invented-but-plausible trivia is worse than no entry at all.
- **A quoted character line must be VERBATIM.** If you cannot confirm the exact
  wording, either quote it exactly or reframe it as trivia in your own words —
  never put an approximation in quote marks and attribute it to a character.
  (This is the one flaw the pilot had: a paraphrased M'aiq line presented as a
  quote.)
- Under ~120 characters each. Every quote needs a non-empty `attrib`.
- No slurs, nothing punching at real people, nothing that reads as marketing.
- Never attribute a fabricated quote to a real named developer.

### Calibration — the register to hit

- `{"text": "Do you get to the Cloud District very often? Oh, what am I saying—of course you don't.", "attrib": "Nazeem, Whiterun's most beloved citizen"}`
- `{"text": "Bethesda's E3 joke about a Skyrim game for Amazon Alexa turned out to be real. You can still play it by voice.", "attrib": "development trivia"}`
- `{"text": "Every player swears they'll be a mage this time. Most end up a stealth archer within the hour.", "attrib": "every player, eventually"}`

## Known snags

- **UESP 403s on WebFetch.** Use `curl` with a browser User-Agent instead:
  `curl -s -A "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0 Safari/537.36" <url>`
- Some Nexus entries are not Steam games at all (`Modding Tools`,
  `Daggerfall Unity`, console-only titles). `appid: null` + `notes` is the
  correct answer, not a near-miss appid.
- Several franchises have remaster/legacy/enhanced splits (`Grand Theft Auto V
  Legacy` vs `Enhanced`, `Resident Evil 3 (2020)`). Match the Nexus name to the
  right SKU deliberately, and say in `notes` how you decided.
