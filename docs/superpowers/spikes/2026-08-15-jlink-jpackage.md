# Spike: packaging cljfx with jlink + jpackage

Date: 2026-08-15
Toolchain: system OpenJDK **26.0.1** (`/usr/lib/jvm/java-26-openjdk`), Clojure
1.12.5, cljfx 1.10.10, JavaFX **26.0.2** (Maven jars for the classpath, Gluon
jmods for jlink), tools.build 0.10.14.
Subject: `reliquary.spike.fx-window` — the same window Task 3 tried to compile
with native-image, unchanged.
Host: Linux, headless; every run under Xvfb.

## Verdict

| Question | Answer |
| --- | --- |
| Does cljfx run on JavaFX 26.0.2 under JDK 26? | **Yes**, first try, no code change. |
| Does the uberjar run? | **Yes** — `java -jar` prints `SPIKE-OK ticks=5`, exit 0. |
| Does the jlink + jpackage app image run? | **Yes** — `SPIKE-OK ticks=5`, exit 0. |
| Was `--add-modules` needed at runtime? | **No.** jpackage's generated launcher resolves JavaFX from the runtime image on its own. |
| Is it one self-contained directory needing no installed JDK? | **Yes** — verified by running it under `env -i` with no `PATH` and no `JAVA_HOME`. |
| Total packaged app image | **73,399,309 bytes — 70.0 MiB** |
| vs. Task 3's native binary | **Smaller.** 81.1 MB / 82 MiB-with-`.so`s becomes 70.0 MiB, *and it actually runs.* |

The replacement toolchain wins on both axes Task 3 lost on. That is the whole
finding; everything below is the arithmetic behind it and the traps found on the
way.

## The numbers

All sizes are `du -sb`, i.e. bytes on disk.

| Artifact | Bytes | MiB |
| --- | ---: | ---: |
| Uberjar, `clojure -T:build uber` (bundles JavaFX) | 59,314,631 | 56.6 |
| Uberjar, `clojure -T:build uber :omit-javafx true` | 8,565,418 | 8.2 |
| jlink runtime for the slim jar's module list | 62,172,090 | 59.3 |
| jlink runtime for the full module list (see below) | 64,349,436 | 61.4 |
| **App image from the slim jar** | **73,399,309** | **70.0** |
| App image from the fat jar | 126,325,868 | 120.5 |
| App image, slim jar + full module list (projected) | 75,576,655 | 72.1 |

The app image breaks down as: the jlink runtime, plus the uberjar copied
verbatim into `lib/app/`, plus a 2,614,696-byte `libapplauncher.so` and a
46,784-byte launcher in `bin/`. There is no compression step — jpackage copies
the jar in as-is — so every byte saved in the jar is a byte saved in the image.

### Why there are two uberjars

`bin/package.sh` is happy with either, but the fat one costs 48 MB for nothing.

cljfx depends on `javafx-web` and `javafx-media` transitively. It loads their
lifecycles lazily (`cljfx.fx/lazy-load`), and Reliquary will use neither, but
Maven resolution still drags them onto the classpath — and `libjfxwebkit.so`
alone is **39.5 MB compressed**, the single largest thing in the build. Together
with `javafx-base`/`graphics`/`controls`, JavaFX accounts for 48.9 MB of the
56.6 MiB fat jar.

Every byte of that is *inert in a packaged build*. The jlink runtime supplies
`javafx.base`, `javafx.graphics` and `javafx.controls` as real named modules,
and the module system resolves those packages from the boot layer in preference
to any classpath copy. This is observable rather than theoretical — the fat
build's own log line reads:

```
java.lang.System::load has been called by com.sun.glass.utils.NativeLibLoader in module javafx.graphics (jrt:/javafx.graphics)
```

`jrt:/javafx.graphics`, not the jar. The classpath copies were never touched.

So `build/uber` takes `:omit-javafx true`, which drops every `org.openjfx`
artifact from the basis before `b/uber` explodes it. The default stays fat,
because a fat jar is the one that runs under a plain `java -jar` on a stock JDK,
and that is a useful thing to keep. `bin/package.sh` warns if it is handed a fat
jar rather than silently producing a 120 MiB image.

### The module list

`bin/package.sh` re-derives the module list from the jar on every run:

```
jdeps --ignore-missing-deps --print-module-deps --multi-release 26 target/lib/reliquary.jar
```

For today's spike jar that returns:

```
java.base, java.desktop, java.sql, jdk.unsupported
```

That is **not** the list the finished application will need, and the difference
is worth stating because a short module list is exactly how a packaged app dies
six months later on a code path nobody exercised. A probe uberjar built with all
20 `reliquary.steam.*` namespaces AOT-compiled resolves to:

```
java.base, java.desktop, java.net.http, java.sql, jdk.jfr, jdk.unsupported, jdk.xml.dom
```

`java.net.http` is the one that matters — the Steam client's whole transport.
It is absent from the spike's list only because the spike jar contains no
Reliquary code beyond the window. Since `package.sh` runs `jdeps` fresh each
time, this corrects itself the moment the app has a real entry point; no list is
hard-coded anywhere.

**jdeps' blind spot, checked rather than assumed.** `jdeps` reads bytecode
references, so it cannot see modules reached through `ServiceLoader` — most
importantly the JCE/JSSE security providers that TLS needs. That was the obvious
candidate for a silent runtime failure, so it was tested directly: a jlink
runtime built from the full list above (which contains no `jdk.crypto.*` module
at all) was used to run an HTTPS request against `api.steampowered.com`. The
handshake completed and the server answered. On JDK 26 the providers TLS needs
are inside `java.base`, and no hand-picked additions were required. **No module
was hand-picked; the shipped list is entirely jdeps-derived.**

### Cold start

Measured against a persistent `Xvfb :99` rather than `xvfb-run`, which adds a
flat ~3.01 s of its own and would have swamped the measurement.

| What | Wall time |
| --- | --- |
| Packaged app image, 5 runs | 2.99 / 2.97 / 2.97 / 2.97 / 2.97 s |
| Fat uberjar on the system JDK, 3 runs | 2.95 / 2.96 / 2.96 s |
| `clojure -M:dev` from source, 2 runs | ~3.36 s |

The spike deliberately sleeps 2.6 s (5 × 400 ms of ticks, then 600 ms) before it
prints and exits, so **time to a live, rendering window is ~0.37 s**. The
packaged image is indistinguishable from running the jar on an installed JDK,
which is the expected result: same HotSpot, same bytecode, one fewer directory
to search.

## Traps found

**1. A stray `module-info.class` made `jdeps` report `java.base` and nothing
else.** `org.tukaani:xz` ships `META-INF/versions/9/module-info.class`. With
`--multi-release 26`, `jdeps` read that as the *whole uberjar's* module
descriptor and dutifully reported the jar's only requirement as `java.base`. The
jlink runtime built from that list would have been missing `java.desktop` — i.e.
all of AWT, which JavaFX needs — and nothing in the pipeline would have
complained until the app was run.

This is the most dangerous thing in the task, because it fails *quietly and
plausibly*: `java.base` is a legal answer, just a wrong one. `b/uber` does strip
`module-info.class`, but its exclusions are matched with `re-matches` against the
full path and its pattern only covers a root-level one, so the versioned copy
slipped through. `build.clj` now adds `".*/module-info\\.class"`.

If the module list ever comes back implausibly short, look here first.

**2. `b/compile-clj`'s `:src-dirs` does not affect the compile classpath.** The
brief's `build.clj` passed `:src-dirs ["src" "spike"]` and failed with
`Could not locate reliquary/spike/fx_window.clj on classpath`. `:src-dirs` is
used for namespace *discovery* only; the classpath comes from the basis, and
`spike` is not in `:paths`. Fixed by synthesising an alias into the basis rather
than by adding `spike` to `:paths`, which would have shipped the spike in every
build.

**3. The uberjar does not contain `src/`.** `b/uber` copies the class-dir plus
the dependency jars — nothing else. Reliquary's own namespaces reach the jar
only via `b/compile-clj`, which AOT-compiles the `:ns-compile` set and whatever
it transitively requires. Today that is the spike window alone, so the Steam
client is genuinely absent from the measured image (see the caveat below). Once
a real entry point requires it, it will be pulled in automatically — but a
future `main` that loads namespaces dynamically would not be, and that would
show up as a missing namespace at runtime, not at build time.

## What these numbers do not yet carry

The same caveat Task 3's 81.1 MB carried, so the comparison stays honest. The
70.0 MiB image contains:

- **Yes:** the JDK runtime, JavaFX base/graphics/controls, Clojure, cljfx, and
  the dependency jars for protobuf, zxing, xz and data.json — those are libs, so
  `b/uber` includes them whether or not anything calls them.
- **No:** any `reliquary.steam.*` code (~20 namespaces of it), any application
  UI beyond one label and one canvas, and **no bundled fonts** — the spike
  renders with whatever fontconfig hands it.

Compiling the Steam client in costs about 2.65 MB: 2,177,346 bytes for the
`java.net.http`, `jdk.jfr` and `jdk.xml.dom` modules it pulls into the runtime
(that is the measured 75,576,655-byte projection in the table), plus 473,247
bytes of AOT-compiled classes for its 20 namespaces. Real UI and any bundled
fonts come on top of that. The
design spec's 40–80 MB band is still live under this toolchain, with maybe 10 MB
of headroom — where native-image had already blown through it while doing
nothing.

Untrimmed levers if that headroom runs out, roughly in order of return:
`--compress=zip-9`, dropping `jdk.jfr`, `jpackage --strip-native-commands`, and
excluding the unused `javafx-swing`/`javafx-fxml` jars the same way as web and
media.

## Reproducing

```bash
./bin/setup-toolchain.sh                        # JavaFX 26.0.2 jmods from Gluon
clojure -T:build uber :omit-javafx true         # target/lib/reliquary.jar
./bin/package.sh                                # target/app/Reliquary/
xvfb-run -a ./target/app/Reliquary/bin/Reliquary   # SPIKE-OK ticks=5
```

`FX_VERSION` overrides the JavaFX version fetched; `JAVA_HOME` overrides the JDK
`bin/package.sh` links against.
