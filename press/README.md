# press

Material for the Nexus Mods page. Nothing here is used by the app.

| File | What it is |
|---|---|
| `cover.png` | 1280×720 page image |
| `description.bbcode` | the mod description, in Nexus BBCode |
| `screenshots/*.png` | 1100×720 screenshots, the app's real window size |
| `reliquary-logo-full.png` | the logo at source resolution, 1099×259 |
| `make-cover.py` | regenerates `cover.png` |
| `render-screenshots.clj` | regenerates `screenshots/` |

## Uploading

`description.bbcode` has four `IMAGE_URL_n` placeholders. Upload the screenshots
to the mod page first, then replace each placeholder with the URL Nexus gives
back. Suggested order, matching where they sit in the text:

1. `01-library-installed.png` — the library, and a game already on disk
2. `03-change-install.png` — the change-install screen, before it runs
3. `07-sign-in.png` — the QR sign-in
4. `04-change-install-progress.png` — a switch in progress

`02-library-owned.png`, `05-downloading.png` and `06-done.png` are not
referenced inline; they are worth having in the gallery.

If you would rather not embed images in the text at all, delete the four
`[img]` lines and let the gallery do the work.

## Regenerating

```
python3 press/make-cover.py
clojure -M:dev -i press/render-screenshots.clj
```

The screenshots use a fixed install fixture (a `D:\SteamLibrary\...` path and a
made-up build label) rather than whatever the machine running them happens to
have, so the same images come out of any checkout and no one's real folder
layout ends up on a public page. The library artwork is real, fetched from
Steam's CDN by the app's own art code.

Rendering at 1100×720 is deliberate: it is the app's window size, and
`shot/render!` crops rather than shrinks, so anything that does not fit in a
screenshot does not fit in the running app either.

## The cover

Built from `reliquary-logo-full.png`, not from `resources/reliquary-logo.png` —
that one is deliberately downscaled to 330×78 for the 26px title bar and is soft
at page size. Palette is Gilt from the brand spec: `#0C0C0C` ground, `#C2A35F`
gold, `#F2F0EE` text, with the flat surfaces the spec asks for and a single
accent glow behind the mark.
