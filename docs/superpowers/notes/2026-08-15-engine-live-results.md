# The engine against real Steam — live results

Account logged in via `reliquary login` (QR). Target: **Stardew Valley**, appid
413150, `public` branch — the smallest owned game in the catalog, and the only
small one with non-public branches.

## Clean download

| | |
|---|---|
| Bytes | 658 MB reported, **669 MB on disk** |
| Files | **3,817** |
| Wall time | **< 25 s** |
| Throughput | ~130–150 MB/s sustained, **166 MB/s peak** |
| Exit | 0, stages `:preparing` → `:downloading` → `:copying` → `:done` |

`Stardew Valley.dll` on disk is a genuine `PE32+ executable … Mono/.Net assembly`.

## Resume after a kill

Killed at **189 MB (28%)**; the progress file held **3,206 chunks across 3,104
files**. The resumed run started at **27.3%**, not zero, and finished in **6.9 s**.

`diff -rq --exclude=.reliquary` against a clean download: **every one of 3,817
files identical.**

## Resume after a real SIGINT

Sent `SIGINT` to the live JVM at **559 MB**. It **exited on its own 1,205 ms
later with no SIGKILL**, having flushed **4,353 chunks**. Resuming completed the
install, and the tree again diffed **identical** to the clean download.

This is the Ctrl-C path that previously deadlocked the JVM (hook joined the main
thread; main called `System/exit`; `System/exit` blocks while hooks run). The
`CountDownLatch` fix holds against real network I/O in flight, not only against
the stubbed subprocess it was first verified with.

## What the live gate caught that 246 green tests did not

Both bugs were invisible offline, and **both had been predicted in review and
deferred as Minor** — by me.

**Steam marks a directory with an all-zero SHA-1.** `Content/Characters` arrives
with size 0, no chunks, and `sha-content
"0000000000000000000000000000000000000000"`. The rule "has chunks or has a
content SHA ⇒ regular file" read that sentinel as a real hash and planned a
zero-byte *file* where its own children needed a *directory*. `:dirs` came out
**0 of 3,784 entries**. A reviewer predicted this exact sentinel two tasks
earlier, when the empty-string case was fixed.

**Steam never emits intermediate directories.** `Content/Characters/Dialogue`
(624 files), `Content/Characters/Monsters` (73), and `Content/Strings` (373)
appear in **no** manifest entry. Preallocation must build each file's parent
chain rather than trusting `:dirs`.

Verified fixed on disk: `Content/Characters` is a directory, and all three
never-named directories exist and are populated.

## Open defect found during this run

**A cancelled download exits 0.** After a real SIGINT the process exits with code
0 — identical to a completed download — so a wrapping script cannot tell the two
apart. The stubbed subprocess test reported 130, so this only surfaced against a
real run. Cancel being "not an error" internally is right; reporting it to the
shell as success is not.

## Numbers worth carrying forward

- The catalog declares `public` at **0.6 GB**; the engine moved **658 MB**. The
  catalog's figure sums every Windows depot including all language packs, so it
  is an upper bound — for Stardew the gap is small, but Skyrim SE reads 27.7 GB
  against a real install nearer 12 GB.
- Preallocation is sparse (`setLength` is `ftruncate`), so all 3,817 files exist
  at full size from the first moment. Disk-usage figures during a download are
  therefore not a progress indicator.
