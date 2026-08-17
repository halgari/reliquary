# Design delta — glows, animation, logo (2026-08-17)

Source: Claude Design project `dc999a84-093d-4d3d-87c7-34b3c008a16f`,
`Reliquary.dc.html`. This records what changed from the version the UI was
built against, translated into JavaFX terms.

**A note on the brand spec.** Gilt says "No gradients, glows or drop shadows on
surfaces." This revision adds all three — but almost entirely on *accents*:
buttons, dots, rings, the progress bar, the selected card. The surfaces
themselves stay flat except the title bar's near-invisible `#1a1a1a → #161616`
and a 7%-white sheen over card art. Treat the accent glows as the new
intent and keep surfaces flat.

## Keyframes (CSS) → JavaFX

CSS animations do not exist in JavaFX. Each becomes a `Timeline` /
`FadeTransition` / `TranslateTransition` attached to the node via
`fx/ext-on-instance-lifecycle`'s `:on-created`. **Every animation must be
`Animation/INDEFINITE` only where the CSS says `infinite`, and every one must be
stopped on `:on-deleted`** or it keeps running against a dead node.

| CSS | What it does | JavaFX |
|---|---|---|
| `haloBreathe 4.5s ∞` | opacity .35↔.75, scale 1↔1.12 | Timeline on `opacity` + `scaleX/Y`, autoReverse |
| `scan 3.4s ∞` | translateY -100% → 2100% | TranslateTransition on the QR scan bar |
| `sheen 7s ∞` | translateX -140% → 320%, skew -18° | TranslateTransition on the stage-panel highlight |
| `riseIn .4–.5s` | opacity 0→1, translateY 10→0, scale .985→1 | parallel Fade + Translate + Scale, plays once |
| `ringIn .55s` | scale .6 → 1.06 → 1, opacity 0→1 | ScaleTransition with an overshoot keyframe |
| `pulseDot 1.4s ∞` | opacity .35↔1 | FadeTransition, autoReverse |
| `fadeUp .45s` | opacity 0→1, translateY 6→0 | already approximated on the quote block |
| `markSpin` | declared but unused in the mockup | do not implement |

## Glows → `-fx-effect: dropshadow(gaussian, <color>, <radius>, <spread>, <x>, <y>)`

CSS `box-shadow: 0 6px 22px -10px rgba(194,163,95,.9)` has no direct JavaFX
equivalent (no spread-as-inset). Approximate: radius ≈ the blur, offsets as
given, and fold the negative spread into a lower alpha.

| Element | CSS | Intent |
|---|---|---|
| Primary buttons | `0 6px 22px -10px rgba(194,163,95,.9)` | gold bloom under the button |
| Primary button hover | `0 8px 30px -8px …1`, `brightness(1.06)` | stronger on hover |
| Selected card | `0 0 0 1px rgba(194,163,95,.35), 0 10px 34px -14px rgba(194,163,95,.6)` + `translateY(-2px)` | ring + lift |
| Selected version row | `inset 0 0 0 1px rgba(194,163,95,.12), 0 0 20px -12px rgba(194,163,95,.9)` | soft gold |
| Selected version dot | `0 0 12px -1px rgba(194,163,95,.9)` | point light |
| Percent text | `text-shadow: 0 0 22px rgba(194,163,95,.45)` | gold haze behind the number |
| Progress bar | `0 0 22px -4px rgba(194,163,95,.85)`, amethyst at 100% | bar glows |
| Sparkline tip bars | `0 0 10px -1px rgba(194,163,95,.8)` on the last ~3 | leading edge glows |
| Done ring | `0 0 26px -6px rgba(125,107,145,.9)` + breathing halo | amethyst bloom |
| QR frame | gold `0 0 34px -8px` waiting, amethyst `0 0 42px -6px` approved | state by colour |
| Title bar | `inset 0 1px 0 rgba(242,240,238,.04)`, `0 12px 30px -24px #000` | hairline + lift |
| Stage panel | same inset hairline + `0 24px 60px -34px #000` | seated in the page |

## Gradients

| Element | Value |
|---|---|
| Primary buttons | `linear-gradient(180deg,#D3BA82,#C2A35F)` |
| Progress bar | `linear-gradient(90deg,#a8874a,#C2A35F 55%,#D3BA82)` |
| Progress bar at 100% | `linear-gradient(90deg,#6b5b7d,#7D6B91)` |
| Title bar | `linear-gradient(180deg,#1a1a1a,#161616)` |
| Card art sheen | `linear-gradient(160deg,rgba(242,240,238,.07),transparent 42%)` overlay |
| Logo halo | `radial-gradient(circle,rgba(194,163,95,.3),transparent 68%)`, 44px |

## The logo

`reliquary-logo.png` replaces the drawn ring-and-dot mark AND the `RELIQUARY`
wordmark in the title bar. Rendered at **height 26px, width auto** (source is
1099×259, aspect 4.24, so ≈110×26), with
`filter: drop-shadow(0 3px 9px rgba(125,107,145,.5))` — an amethyst glow — and
the breathing gold halo behind it.

**The file is now at `resources/reliquary-logo.png`** (289,470 bytes, 1099x259
RGBA, verified decodable by ImageIO). `DesignSync/get_file` caps at 256 KiB and
returned it truncated, so the user supplied it directly.

The artwork is an amethyst chest with a gold clasp and a download arrow, beside
a cream `RELIQUARY` wordmark -- so it carries the Gilt palette itself and
replaces BOTH the drawn ring-and-dot mark and the tracked text wordmark.
Verified legible at 110x26.

Keep a **fallback to the drawn mark + wordmark when the resource is missing or
unreadable**. It costs a few lines, and it means a packaging mistake that drops
the image degrades to the old title bar rather than to an empty rectangle or a
crash.

## Other deltas

- Version dots and shot dots gain a transition; the selected/active one glows.
- Sparkline bars gain `transition: height .16s linear` — in JavaFX, animate the
  bar height rather than setting it, or accept the step (note which).
- The QR overlay opacity moved `.92 → .94` and gained `fadeUp`.
- Percent and ETA both gain `font-variant-numeric: tabular-nums`
  (`-fx-font-features: "tnum"`), so the digits stop jittering.
