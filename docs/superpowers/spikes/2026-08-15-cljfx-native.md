# Spike: does cljfx survive GraalVM native-image, and at what size?

Date: 2026-08-15
Toolchain: GraalVM CE 25.0.2+10.1 (JDK 25.0.2), Clojure 1.12.5, cljfx 1.10.10,
JavaFX 25.0.4 (linux classifier), clj-easy/graal-build-time 1.0.6.
Host: Linux, 32 cores, 28.45 GB of build memory made available to native-image.

## Verdict

| Question | Answer |
| --- | --- |
| Did the native image build? | **Yes** — but only with an extra `--initialize-at-run-time` flag and a fix to the build script. |
| Did the resulting binary run? | **No.** It aborts during static initialisation of the main class, before any window appears. |
| Binary size | **81.1 MB** (77.4 MiB; `ls -lh` reports `78M`) |
| Wall-clock build time | **29 s** end to end (`bin/native.sh`); native-image itself accounted for 24.1 s |
| Total size of `native/config` | **183,919 bytes (180 KiB)** — a single file, `reachability-metadata.json` |
| Is it a single file? | **No** — see "The binary is not self-contained" below. |

**The design spec's 40–80 MB expectation held, but only just, and only at the top
of the range.** 81.1 MB is marginally *over* the 80 MB ceiling for a spike that
draws one label, one canvas and one more label. It carries no Steam client code,
no protobuf, no zxing, no application logic — all of that is still to come. The
size premise should be treated as failed-in-spirit even though the number is
within a rounding error of the stated band. Real UI, fonts and the Steam stack
will not fit under 80 MB.

**The runnability premise failed outright.** cljfx did not survive native-image.

## The blocking finding: cljfx cannot be class-initialised at build time, and
## cannot be moved to run time either

This is a pincer, and both jaws are properties of cljfx's source, not of our
configuration.

`clj-easy/graal-build-time`'s `InitClojureClasses` feature registers every
Clojure package on the classpath — here `clojure`, `clj_easy.graal_build_time`,
`cljfx`, `reliquary.spike` — for **build-time** class initialisation. That is the
standard, and effectively mandatory, way to get Clojure through native-image.

**Jaw 1 — cljfx cannot initialise at build time.** Two independent reasons, both
surfaced by the builder:

1. `cljfx.api` calls `javafx.application.Platform/startup` as a *namespace load
   side effect* (`cljfx.api:90` → `cljfx.platform/initialize` →
   `cljfx.jdk.platform/initialize` → `Platform/startup`). Running that inside the
   native-image builder fails with
   `java.lang.UnsupportedOperationException: Unable to open DISPLAY`.
   Running the whole builder under `xvfb-run` does **not** fix it — the builder
   still reports `Unable to open DISPLAY` (tested; see Attempt 4).
2. Several cljfx namespaces (`cljfx.mutator`, `cljfx.prop`, `cljfx.lifecycle`)
   call `(set! *warn-on-reflection* true)` at the top level. Under build-time
   class initialisation there is no thread binding frame, so this throws
   `java.lang.IllegalStateException: Can't change/establish root binding of:
   *warn-on-reflection* with set`. This one has nothing to do with displays and
   would fail on a machine with a monitor attached.

**Jaw 2 — moving it to run time breaks AOT loading.** Passing
`--initialize-at-run-time=cljfx,reliquary.spike` makes the build succeed. But at
run time the `:gen-class` stub's `<clinit>` calls `clojure.lang.Util.loadWithClass`,
which no longer resolves the AOT-compiled `reliquary.spike.fx_window__init` class
and falls back to loading `reliquary/spike/fx_window.clj` **from source**. That
drags in the Clojure compiler, which drags in `clojure.spec.alpha` (also from
source), which tries to define a class at run time:

```
Exception in thread "main" java.lang.ExceptionInInitializerError
Caused by: Syntax error macroexpanding clojure.core/ns at (reliquary/spike/fx_window.clj:1:1).
Syntax error compiling fn* at (clojure/spec/alpha.clj:9:1).
...
Caused by: com.oracle.svm.core.jdk.UnsupportedFeatureError: Classes cannot be
  defined at runtime by default when using ahead-of-time Native Image compilation.
  Tried to define class 'clojure.core$eval1'
```

So: build time is impossible because of cljfx's load-time side effects, and run
time is impossible because it defeats AOT. Escaping the pincer means changing
cljfx itself (removing the `Platform/startup` side effect and the top-level
`set!` calls), which is explicitly outside this spike's scope.

## The binary is not self-contained

Even the build that succeeded did not produce one file. native-image emitted
eight JDK shared libraries next to the executable, totalling 4,276,024 bytes:

```
libawt.so  libawt_headless.so  libawt_xawt.so  libfontmanager.so
libjava.so  libjavajpeg.so  libjvm.so  liblcms.so
```

`target/` as a whole is 85,412,032 bytes (82 MiB). The "small single-file binary"
half of the premise is therefore not met on its own terms regardless of the
runtime failure — a distributable would need to carry these alongside, or be
relinked statically. Note also that JavaFX's own natives (`libglass`,
`libprism_es2`, `libjavafx_font`) were never reached, because the binary never
got far enough to extract them.

## What the tracing agent produced

Step 3 worked cleanly and quickly. Under `xvfb-run`, the spike ran on the JVM in
7.5 s, printed `SPIKE-OK ticks=5`, and exited 0 — so **cljfx itself, including
`ext-on-instance-lifecycle`, `Canvas` 2D drawing and `Platform/runLater` from a
plain thread, works correctly on the JVM**. The problem is native-image alone.

The brief expected `native/config/reflect-config.json`. GraalVM for JDK 25 no
longer emits the split `reflect-config.json` / `jni-config.json` / etc. family;
it emits a single unified **`reachability-metadata.json`**, 183,919 bytes. This
is the modern format and `-H:ConfigurationFileDirectories=native/config` consumes
it correctly. Nothing is wrong — the brief's filename is simply out of date.

## Changes made to `bin/native.sh` beyond the brief

1. **`(System/exit 0)` appended to the AOT compile expression, and the compile
   wrapped in `xvfb-run -a`.** Without this the build hangs forever — not fails,
   *hangs*. Loading `cljfx.api` starts a non-daemon `JavaFX Application Thread`
   running `GtkApplication._runLoop`, so the compiler JVM never exits after
   `compile` returns. The first build attempt sat idle for 337 s before it was
   killed. `xvfb-run` is needed for the same reason a display is needed at all:
   the toolkit starts during compilation, so a headless build machine would fail
   here without it.
2. **An `EXTRA_NATIVE_IMAGE_ARGS` passthrough.** The spike-specific
   `--initialize-at-run-time` flags are not baked into the script, because the
   configuration they enable produces a binary that does not run and because
   Task 4 should not inherit them silently.

## Every flag added beyond the brief's set, and why

Only one, and it is the flag the whole finding turns on:

| Flag | Why | Result |
| --- | --- | --- |
| `--initialize-at-run-time=cljfx,reliquary.spike` | The only way found to get past cljfx's build-time initialisation failures | Build succeeds in 24.1 s; binary does not run (Jaw 2 above) |

The brief's own flag set is otherwise unchanged. Two of its flags drew
deprecation/experimental warnings worth noting for later cleanup:
`--report-unsupported-elements-at-runtime` is deprecated and now the default, and
`-H:IncludeResources` is experimental with `META-INF/native-image/.../resource-config.json`
as the suggested replacement.

## Attempt log

| # | Configuration | Outcome |
| --- | --- | --- |
| 1 | Brief's `bin/native.sh` verbatim | **Hung** in the AOT compile step for 337 s; never reached native-image. Cause: non-daemon JavaFX thread. Fixed by `(System/exit 0)`. |
| 2 | Brief's flags, compile fixed | `Class initialization of cljfx.api__init failed` → `Unable to open DISPLAY`. Failed in 4.7 s. |
| 3 | `--initialize-at-run-time=cljfx.api__init` (exactly what the builder suggested) | Moved the failure one namespace down: `cljfx.lifecycle__init` → `Can't change/establish root binding of: *warn-on-reflection* with set`. Failed in 2.8 s. |
| 4 | `--initialize-at-run-time=cljfx,reliquary.spike` | **Built** in 26 s. Binary aborts at startup: `UnsupportedFeatureError: Classes cannot be defined at runtime`. |
| 5 | `--initialize-at-run-time=cljfx` (whole package only, to keep the spike ns AOT-loaded) | `Class initialization of reliquary.spike.fx_window__init failed` → `Unable to open DISPLAY`. The spike ns still initialises at build time and requires cljfx.api. Failed in 4 s. |
| 6 | Brief's flags, entire builder run under `xvfb-run` | Still `Unable to open DISPLAY`, *plus* the `*warn-on-reflection*` failures in `cljfx.mutator` and `cljfx.prop`. Proves a display at build time is not the fix. Failed in 5 s. |
| 7 | Attempt 4's configuration, re-run end to end through the finished `bin/native.sh` | **Built.** 81.1 MB, 24.1 s native-image / 29 s total. Same startup abort. These are the numbers reported above. |

## Reproducing

```bash
export RELIQUARY_JAVA_HOME=$(./bin/setup-toolchain.sh)

# Step 3 — tracing agent (works, prints SPIKE-OK ticks=5)
mkdir -p native/config
JAVA_HOME=$RELIQUARY_JAVA_HOME \
xvfb-run -a $RELIQUARY_JAVA_HOME/bin/java \
  -agentlib:native-image-agent=config-output-dir=native/config \
  --enable-native-access=ALL-UNNAMED \
  -cp "$(clojure -Spath -A:dev)" clojure.main -m reliquary.spike.fx-window

# Step 4 — the build that succeeds
EXTRA_NATIVE_IMAGE_ARGS="--initialize-at-run-time=cljfx,reliquary.spike" \
  ./bin/native.sh reliquary.spike.fx-window reliquary-spike-fx

# Step 5 — the run that does not
xvfb-run -a ./target/reliquary-spike-fx     # exits 1
```

## What this does not say

This spike deliberately stops here. It does not attempt to patch cljfx, and it
does not evaluate the fallback ladder (raw JavaFX interop, then jlink+jpackage).
Choosing the next rung is a decision for the project owner, not for this task.
Two facts should feed that decision:

- cljfx works perfectly on the JVM. If the delivery mechanism can be a JVM
  bundle (jlink/jpackage), cljfx is not implicated at all.
- The size figure of 81.1 MB is for the *empty* case and already exceeds the
  spec's ceiling. Whichever rung is chosen, the 40–80 MB target needs revisiting.

---

## Addendum (added in Task 4): `cljfx.skip-javafx-initialization` exists, and
## would not have saved this

Reviewing this document turned up an escape hatch it never mentioned. `cljfx.api`
documents a `cljfx.skip-javafx-initialization` Java property aimed at exactly
this case — its own docstring says "for AOT-compilation you might need to skip
JavaFX initialization completely". The spike never tried it, and a findings
document that justifies dropping a toolchain should not claim two independent
blockers while omitting that one of them ships with a documented off-switch.

It would not have changed the outcome. **This was verified by reading cljfx
1.10.10's sources, not by experiment** — the GraalVM toolchain was retired in
Task 4 and was deliberately not resurrected to test it.

What the flag does, in `cljfx/api.clj`:

```clojure
(defonce initialized
  (when-not (Boolean/getBoolean "cljfx.skip-javafx-initialization")
    (platform/initialize)))
```

That is the whole of it. It guards **Jaw 1, reason 1 only** — the
`Platform/startup` call that produced `Unable to open DISPLAY`. Setting it would
have removed that failure.

It does nothing about **Jaw 1, reason 2**. `(set! *warn-on-reflection* true)` at
the top level is not in `cljfx.api` and is not conditional on anything; it sits
in five separate namespaces:

```
cljfx/coerce.clj:34    cljfx/renderer.clj:6    cljfx/prop.clj:9
cljfx/lifecycle.clj:19 cljfx/mutator.clj:12
```

(Attempt 3 in the log above already hit `cljfx.lifecycle` on this, and Attempt 6
hit `cljfx.mutator` and `cljfx.prop` as well — in Attempt 6 the
`*warn-on-reflection*` failures were reported *alongside* the display failure,
not behind it, so removing the display failure would simply have left them.)

Under build-time class initialisation there is no thread binding frame, so each
of those throws `Can't change/establish root binding of: *warn-on-reflection*
with set` regardless of whether JavaFX was ever started. And Jaw 2 —
`--initialize-at-run-time` defeating AOT class loading — is untouched by the
property as well, since it is a property of how native-image loads AOT'd Clojure,
not of cljfx's JavaFX startup.

So the correct reading of this spike is unchanged, with one correction: the
display half of Jaw 1 had a supported workaround the spike did not try, and the
`set!` half did not.
