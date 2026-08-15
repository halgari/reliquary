# Steam fixtures

Captured from live Steam with `dev/capture_fixtures.clj`. Regenerate with:

    clojure -M:dev -m capture-fixtures pics [app-id]      # default 489830
    less test/resources/steam/pics-app.vdf                # find a depot id + gid by eye
    clojure -M:dev -m capture-fixtures manifest 489830 <depot-id> <gid>

| file | what it is |
|---|---|
| `pics-package.bin` | one PICS product-info *package* buffer: 4-byte prefix + binary KeyValues |
| `pics-app.vdf` | the PICS product-info *app* buffer for appid 489830 (Skyrim SE): text VDF |
| `manifest.zip` | one depot manifest exactly as the CDN serves it — a single-entry zip |

## The manifest's filenames are encrypted, on purpose

The brief this fixture set was written against assumed an unencrypted depot
manifest could be found and committed. It could not. Every candidate depot
checked on this account had `filenames_encrypted = true` in its
`ContentManifestMetadata`:

- all 11 candidate depots of appid 489830 (Skyrim SE)
- all candidate depots checked on appid 440 (Team Fortress 2)
- all candidate depots checked on appid 570 (Dota 2)
- all candidate depots checked on appid 730 (CS2)

See `.superpowers/sdd/2026-08-06-steam-metadata/task-5-report.md` for the full
per-depot table. Filename encryption looks to be universal in current Steam
depots, not a property of this one app, so committing an unencrypted fixture
was never an option and there is no reason to expect a re-capture to find one
either.

`manifest.zip` is the manifest for **appid 489830, depot 489831** (the base
English depot, no `config/oslist` or `config/language` restriction), gid
**8442952117333549665**. This is safe to commit encrypted: only the
`filename` field (and `linktarget`, for symlinks) is ciphertext. Everything
else in the manifest is plaintext regardless of `filenames_encrypted` — the
block framing (magic + length prefixes), `ContentManifestPayload`,
`ContentManifestMetadata`, per-file `size`, per-file `sha_content`/
`sha_filename`, `flags`, and the full chunk list (offsets, sizes, per-chunk
SHA-1). That covers nearly everything the manifest parser has to get right;
only filename decryption is deferred.

**No depot key is committed here, and none ever will be.** A depot key
paired with this manifest would decrypt real filenames; without a key the
encrypted `filename` bytes are opaque and carry no information. Decrypting
filenames against a real key is exercised only by the `MAUVI_STEAM_LIVE=1`
live gate, run by hand against a live account — never from a committed
fixture.

Before committing a re-capture, confirm the app VDF has no `steamid` or
account name in it, and re-run the Step 6 secret scan (see the task-5 brief)
over every fixture file.

## `chunk.vz` + `chunk.edn`

One real depot chunk, **decrypted but still LZMA-compressed**, plus its chunk
id and `cb-original`. Captured with:

    clojure -M:dev -m capture-fixtures chunk 489830 <depot-id> <gid>

Decrypted at capture time on purpose (spec 2c §8): it is what lets
`mauvi.steam.vzip-test` prove the LZMA branch fully offline, with no depot key
committed, no key read from the environment, and no skip path. The capture
script verifies `sha1(decompress(bytes)) == :id` before writing, so a bad
fixture cannot be committed.

The chunk id is the SHA-1 of the **decoded plaintext**, not of the encrypted
bytes the CDN serves — which is why the hash cannot gate decryption on the live
path (spec 2c §4).
