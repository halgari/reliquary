# App Shell and Login Screen Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A real Reliquary window that opens, renders the Gilt design, and logs
you into Steam by QR — the first vertical slice of the UI.

**Architecture:** cljfx over one state atom. `ui/theme.clj` holds the Gilt tokens
and the bundled fonts; `ui/app.clj` is the window frame (title bar, content,
legal footer); `ui/login.clj` is the first screen. Login runs `auth/login-qr!`
on a background thread and marshals its events to the FX thread. Nothing in the
UI knows what a depot is; nothing in the engine knows JavaFX exists.

**Tech Stack:** cljfx 1.10.10, JavaFX 26.0.2, JDK 26, Clojure 1.12.5. Packaging
is the existing jlink + jpackage pipeline.

**Spec:** `docs/superpowers/specs/2026-08-15-reliquary-design.md` — §5 is the
authority for this plan, and §1 describes the screen.

## Why this scope

The spec's build order lists four screens. This plan builds **one**, plus the
shell they all sit in, because that is the first slice that proves the whole
architecture end to end: theme, bundled fonts, the state atom, background-thread
marshalling, a `Canvas` drawn by hand, and a real Steam login. Library, download
and done screens are additive once this stands, and each gets its own plan.

## Global Constraints

- **Secrets never surface.** The refresh token is never rendered, logged,
  printed, or placed in an ex-info data map. It goes from `auth` straight to
  `config/save-token!`. The Steam challenge URL is deliberately shown — that is
  what the user scans — but the token never is.
- **Gilt, exactly.** bg `#0C0C0C`, surface `#161616`, line `#292929`,
  line-strong `#383838`, text `#F2F0EE`, text-muted `#9A9A9A`, gold `#C2A35F`,
  amethyst `#7D6B91`. Hanken Grotesk for interface, DM Mono for every number,
  path, hash, version string and percentage. 3px radius on controls, 6px on
  cards and windows. **No shadows, no gradients on surfaces.** One gold element
  per screen region. Errors are gold text on surface with a mono code — never a
  red banner.
- **Fonts are bundled, never fetched at runtime.** A downloader that needs
  Google Fonts to render is a downloader that looks broken offline.
- **The legal footer is verbatim, on every screen**: `Not associated with or
  endorsed by Valve Corporation or Steam.`
- Error categories `:unauthenticated`/`:unavailable`/`:io`/`:incorrect` under
  `:reliquary/error`.
- No core.async. No new dependencies beyond what `deps.edn` already pins.
- GPL-3.0-or-later header on every new file.
- The suite is at **260 tests / 628 assertions / 0 failures** and must be green
  at every commit.

## File Structure

| Path | Responsibility |
|---|---|
| `resources/fonts/` | Hanken Grotesk + DM Mono, OFL, committed |
| `src/reliquary/ui/theme.clj` | Gilt tokens, font loading, shared style fragments |
| `src/reliquary/ui/shot.clj` | render any view to a PNG — the UI's only real gate |
| `src/reliquary/ui/app.clj` | window frame: title bar, content slot, legal footer |
| `src/reliquary/ui/login.clj` | the login screen |
| `src/reliquary/main.clj` | entry point; decides login vs. signed-in |
| `test/reliquary/ui/theme_test.clj` | tokens and font loading |
| `test/reliquary/ui/login_test.clj` | the view function is pure — test it as data |

## How UI work is verified here

There is no browser on this machine, so there is no pixel diff against the
mockup. The gate is **a PNG you look at**:

1. `ui/shot.clj` renders a view to a PNG under xvfb.
2. Every UI task ends by producing that PNG.
3. **The implementer must Read the PNG and describe what is actually in it**
   before claiming the task is done — not infer it from a green test.
4. The reviewer Reads it too, against the Gilt constraints above.

A cljfx view function is a pure function from state to a data structure. That
makes structure testable without a window: assert that a `:label`'s `:text` is
what it should be, that a disabled button is disabled. Those tests are real and
cheap — but they cannot tell you the screen looks right, which is why the PNG is
the gate and the tests are the safety net.

---

### Task 1: Fonts and the Gilt theme

**Files:**
- Create: `resources/fonts/HankenGrotesk-Regular.ttf`, `-SemiBold.ttf`, `-Bold.ttf`
- Create: `resources/fonts/DMMono-Regular.ttf`, `-Medium.ttf`
- Create: `src/reliquary/ui/theme.clj`
- Create: `test/reliquary/ui/theme_test.clj`

**Interfaces:**
- Produces: `theme/color` → `{:bg :surface :line :line-strong :text :text-muted
  :gold :amethyst}` as hex strings; `theme/ui-font`, `theme/mono-font` →
  loaded JavaFX family names; `theme/load-fonts!` (idempotent);
  `theme/style` → a helper composing `-fx-` style strings from tokens

- [ ] **Step 1: Fetch the fonts**

Both families are SIL Open Font License, so committing them is permitted and is
what the spec requires. Fetch from the Google Fonts GitHub repository, which
serves the actual TTFs (the CSS API serves WOFF2, which JavaFX cannot load):

```bash
mkdir -p resources/fonts
BASE=https://raw.githubusercontent.com/google/fonts/main
curl -fsSL "$BASE/ofl/hankengrotesk/HankenGrotesk%5Bwght%5D.ttf" -o resources/fonts/HankenGrotesk.ttf
curl -fsSL "$BASE/ofl/dmmono/DMMono-Regular.ttf" -o resources/fonts/DMMono-Regular.ttf
curl -fsSL "$BASE/ofl/dmmono/DMMono-Medium.ttf"  -o resources/fonts/DMMono-Medium.ttf
ls -lh resources/fonts/
```

Hanken Grotesk ships as a **variable font** (`[wght]`). JavaFX's `Font.loadFont`
handles variable TTFs by exposing the default instance only — if weights 600 and
700 do not render distinctly, fall back to the static instances under
`ofl/hankengrotesk/static/` and commit those instead. **Check this in Step 4's
PNG rather than assuming**, and record which you used.

Also fetch each family's `OFL.txt` to `resources/fonts/` — redistributing an OFL
font requires its license to travel with it.

- [ ] **Step 2: Write the failing tests**

```clojure
(ns reliquary.ui.theme-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [reliquary.ui.theme :as theme]))

(deftest every-gilt-token-is-present-and-exact
  (testing "these hex values are the brand spec; a drift here is a brand bug"
    (is (= {:bg "#0C0C0C" :surface "#161616" :line "#292929" :line-strong "#383838"
            :text "#F2F0EE" :text-muted "#9A9A9A" :gold "#C2A35F" :amethyst "#7D6B91"}
           theme/color))))

(deftest fonts-load-from-the-bundle-not-the-network
  (theme/load-fonts!)
  (is (some? (theme/ui-font)))
  (is (some? (theme/mono-font)))
  (testing "the loaded families are the bundled ones, not a system fallback"
    (is (str/includes? (str/lower-case (theme/ui-font)) "hanken"))
    (is (str/includes? (str/lower-case (theme/mono-font)) "dm mono"))))

(deftest loading-fonts-twice-is-harmless
  (theme/load-fonts!)
  (is (= (theme/ui-font) (do (theme/load-fonts!) (theme/ui-font)))))

(deftest style-composes-fx-declarations
  (is (= "-fx-background-color: #161616; -fx-background-radius: 6;"
         (theme/style {:-fx-background-color (:surface theme/color)
                       :-fx-background-radius 6}))))
```

- [ ] **Step 3: Run them and watch them fail**

Run: `clojure -M:test -n reliquary.ui.theme-test`
Expected: FAIL — the namespace does not exist.

- [ ] **Step 4: Implement `theme.clj`, and LOOK at the result**

Load fonts with `javafx.scene.text.Font/loadFont` from a resource stream, once,
guarded by a `delay`. Then render a swatch card — every token as a labelled
block, plus a line of each font at 13px, 19px and 25px — to
`/tmp/reliquary-shots/theme.png` under xvfb.

**Read that PNG.** Confirm: the two font families are visibly different, the
weights are distinguishable, and the eight colours match the table. Say in your
report what you actually saw, not that a test passed.

- [ ] **Step 5: Run the tests, then commit**

```bash
clojure -M:test -n reliquary.ui.theme-test && clojure -M:test
git add resources/fonts src/reliquary/ui/theme.clj test/reliquary/ui/theme_test.clj
git commit -m "Bundle the Gilt fonts and tokens, so the app renders offline"
```

---

### Task 2: `ui/shot.clj` — the screenshot gate

Built before any screen, because it is how every screen afterwards is verified.

**Files:**
- Create: `src/reliquary/ui/shot.clj`
- Create: `test/reliquary/ui/shot_test.clj`

**Interfaces:**
- Produces: `(shot/render! desc out-file {:keys [width height]})` — renders a
  cljfx description to a PNG and returns the `File`. Runs the FX toolkit,
  takes a `Scene` snapshot, writes with `ImageIO`, and shuts down cleanly.

- [ ] **Step 1: Write the failing test**

```clojure
(deftest renders-a-description-to-a-real-png
  (let [out (io/file (System/getProperty "java.io.tmpdir") "reliquary-shot-test.png")]
    (.delete out)
    (shot/render! {:fx/type :v-box
                   :style "-fx-background-color: #C2A35F;"
                   :children [{:fx/type :label :text "hello"}]}
                  out {:width 200 :height 100})
    (is (.isFile out))
    (is (pos? (.length out)))
    (let [img (javax.imageio.ImageIO/read out)]
      (is (= 200 (.getWidth img)))
      (is (= 100 (.getHeight img)))
      (testing "the fill actually rendered -- a blank PNG is the classic false pass"
        (let [argb (.getRGB img 100 50)
              hex  (format "#%06X" (bit-and argb 0xFFFFFF))]
          (is (= "#C2A35F" hex)))))))
```

That last assertion matters more than it looks: a snapshot harness that silently
produces a blank or transparent image will make every later screen "pass".

- [ ] **Step 2: Run it and watch it fail** — namespace missing.

- [ ] **Step 3: Implement `shot.clj`**

Start the FX toolkit once (`Platform/startup`, guarded — it throws if already
running), build a `Scene` from the description via cljfx's renderer or
`fx/instance` on an advanced lifecycle, call `.snapshot`, write with
`SwingFXUtils/fromFXImage` + `ImageIO/write`. Ensure the fonts are loaded first.

The test must run headless — the CI path is `xvfb-run -a clojure -M:test`. If
`Platform/startup` fails without a display, that is a real constraint to record,
not to work around by skipping the test.

- [ ] **Step 4: Run under xvfb**

Run: `xvfb-run -a clojure -M:test -n reliquary.ui.shot-test`
Expected: PASS, and `/tmp/reliquary-shot-test.png` is a 200×100 gold square.
**Read the PNG.**

- [ ] **Step 5: Commit**

```bash
git add src/reliquary/ui/shot.clj test/reliquary/ui/shot_test.clj
git commit -m "Render any view to a PNG, because that is how UI gets checked"
```

---

### Task 3: `ui/app.clj` — the window frame

**Files:**
- Create: `src/reliquary/ui/app.clj`
- Create: `test/reliquary/ui/app_test.clj`

**Interfaces:**
- Consumes: `theme`, `shot`
- Produces: `(app/view state)` → a `:stage` description. State keys used here:
  `{:screen :status-line :signed-in?}`. `(app/title-bar state)` and
  `(app/footer)` are separate so they can be asserted independently.

**The frame, from the design:** a 52px title bar on `surface` with a 1px `line`
bottom border, carrying the logo mark (a 13px circle, 3px gold ring, amethyst
fill) and the wordmark `RELIQUARY` in mono, 12px, uppercase, `.2em` tracking.
The status line sits right-aligned in mono 11px `text-muted`. A "Sign out"
control appears only when signed in. The content area fills. A 34px footer on
`bg` with a 1px `line` top border carries the legal sentence centred, mono 10px
`text-muted`, alongside the `Created by halgari` credit.

- [ ] **Step 1: Write the failing tests**

```clojure
(deftest the-legal-footer-is-verbatim-and-unconditional
  (testing "this sentence is a legal requirement, not copy to be improved"
    (doseq [screen [:login :library :download :done]]
      (is (str/includes? (pr-str (app/view {:screen screen}))
                         "Not associated with or endorsed by Valve Corporation or Steam.")))))

(deftest sign-out-appears-only-when-signed-in
  (is (not (str/includes? (pr-str (app/title-bar {:signed-in? false})) "Sign out")))
  (is (str/includes? (pr-str (app/title-bar {:signed-in? true})) "Sign out")))

(deftest the-status-line-renders-what-state-says
  (is (str/includes? (pr-str (app/title-bar {:status-line "not signed in"}))
                     "not signed in")))

(deftest the-window-carries-the-app-name
  (is (= "Reliquary" (:title (app/view {:screen :login})))))
```

- [ ] **Step 2: Run and watch them fail.**

- [ ] **Step 3: Implement `app.clj`**

```clojure
;; Reliquary — Copyright (C) 2026 Timothy Baldridge
;; Licensed under the GNU General Public License v3 or later. See LICENSE.
(ns reliquary.ui.app
  "The window frame every screen sits in: title bar, content slot, legal footer."
  (:require [reliquary.ui.theme :as theme]))

(def ^:private c theme/color)

(defn logo-mark
  "A 13px circle with a 3px gold ring and an amethyst centre. The brand spec
   requires it to stay legible at 16px, so it is drawn, not an image."
  []
  {:fx/type :region
   :min-width 13 :min-height 13 :max-width 13 :max-height 13
   :style (theme/style {:-fx-background-color (:amethyst c)
                        :-fx-background-radius 7
                        :-fx-border-color (:gold c)
                        :-fx-border-width 3
                        :-fx-border-radius 7})})

(defn title-bar [{:keys [status-line signed-in? on-sign-out]}]
  {:fx/type :h-box
   :alignment :center-left
   :spacing 10
   :min-height 52 :max-height 52
   :padding {:left 22 :right 22}
   :style (theme/style {:-fx-background-color (:surface c)
                        :-fx-border-color (str "transparent transparent " (:line c) " transparent")
                        :-fx-border-width "0 0 1 0"})
   :children (cond-> [(logo-mark)
                      {:fx/type :label :text "RELIQUARY"
                       :style (theme/style {:-fx-font-family (theme/mono-font)
                                            :-fx-font-size 12
                                            :-fx-text-fill (:text c)})}
                      {:fx/type :region :h-box/hgrow :always}
                      {:fx/type :label :text (or status-line "")
                       :style (theme/style {:-fx-font-family (theme/mono-font)
                                            :-fx-font-size 11
                                            :-fx-text-fill (:text-muted c)})}]
               signed-in? (conj {:fx/type :button :text "Sign out"
                                 :on-action on-sign-out
                                 :style (theme/style {:-fx-background-color "transparent"
                                                      :-fx-border-color (:line c)
                                                      :-fx-border-radius 3
                                                      :-fx-background-radius 3
                                                      :-fx-text-fill (:text-muted c)
                                                      :-fx-font-size 12})}))})

(def legal
  "Verbatim, on every screen. A legal requirement, not copy to be improved."
  "Not associated with or endorsed by Valve Corporation or Steam.")

(defn footer []
  {:fx/type :h-box
   :alignment :center
   :spacing 8
   :min-height 34 :max-height 34
   :style (theme/style {:-fx-background-color (:bg c)
                        :-fx-border-color (str (:line c) " transparent transparent transparent")
                        :-fx-border-width "1 0 0 0"})
   :children [{:fx/type :label :text legal
               :style (theme/style {:-fx-font-family (theme/mono-font)
                                    :-fx-font-size 10
                                    :-fx-text-fill (:text-muted c)})}
              {:fx/type :label :text "·"
               :style (theme/style {:-fx-text-fill (:line-strong c)})}
              {:fx/type :label :text "Created by halgari"
               :style (theme/style {:-fx-font-family (theme/mono-font)
                                    :-fx-font-size 10
                                    :-fx-text-fill (:text-muted c)})}]})

(defn view
  "`:content` is the screen's own description; this frames it."
  [{:keys [content] :as state}]
  {:fx/type :stage
   :showing true
   :title "Reliquary"
   :width 1100 :height 720
   :scene {:fx/type :scene
           :fill (:bg c)
           :root {:fx/type :v-box
                  :style (theme/style {:-fx-background-color (:bg c)})
                  :children [(title-bar state)
                             (assoc (or content {:fx/type :region}) :v-box/vgrow :always)
                             (footer)]}}})
```

- [ ] **Step 4: Shoot the frame and LOOK at it**

Render `(app/view {:screen :login :status-line "not signed in"})` with a
placeholder content area to `/tmp/reliquary-shots/frame.png`.

**Read it.** Confirm against the design: 52px bar, correct greys, the mark is a
gold ring around an amethyst centre, the wordmark is mono and tracked, the
footer sentence is present and centred. Report what you saw.

- [ ] **Step 5: Tests, then commit.**

---

### Task 4: `ui/login.clj` — the screen

**Files:**
- Create: `src/reliquary/ui/login.clj`
- Create: `test/reliquary/ui/login_test.clj`

**Interfaces:**
- Consumes: `theme`, `reliquary.steam.qr/module-matrix`, `dark-at?`, `module-span`
- Produces: `(login/view state)` → the split panel. State keys:
  `{:challenge-url :qr-state :account :password :guard-code :guard-type :error}`
  where `:qr-state` is `:waiting | :approved`.

**The screen, from the design:** two halves either side of a vertical hairline
that fades at both ends. Left: the caption `Scan to sign in` in mono 12px
uppercase tracked `text-muted`, the QR on an `#F2F0EE` card with 18px padding
and 6px radius, a pulsing dot beside a status line, and the explanatory
sentence — *Approve the request in the mobile app. Sign-in completes on its own
— there is no button here.* Right: `Or use your account`, an account-name field,
a password field, and the sign-in button, which is gold and enabled only when
both fields are non-empty and `surface`/`text-muted`/not-clickable otherwise.

**The two deliberate departures from the mockup** (spec §5):

1. **The QR is drawn from the real matrix, not 21×21.** A Steam challenge URL
   needs 29–37 modules. Draw `qr/module-matrix` onto a `Canvas` sized to the
   same physical box the mock uses, computing the module size from
   `qr/module-span` so the card stays the same size whatever version zxing
   picks.
2. **The password panel swaps to a Guard-code field when Steam asks.** When
   `:guard-type` is 2 (emailed) or 3 (authenticator), the password field is
   replaced by a code field, in the same visual language, with a caption naming
   which. Without this the credential flow blocks forever with no explanation.

- [ ] **Step 1: Write the failing tests**

```clojure
(deftest the-sign-in-button-is-disabled-until-both-fields-are-filled
  (is (:disable (find-node (login/view {:account "" :password ""}) :button)))
  (is (:disable (find-node (login/view {:account "someone" :password ""}) :button)))
  (is (not (:disable (find-node (login/view {:account "someone" :password "x"}) :button)))))

(deftest the-guard-code-field-replaces-the-password-field-when-steam-asks
  (testing "spec 5: without this the credential flow blocks with no explanation"
    (let [s (pr-str (login/view {:guard-type 3}))]
      (is (str/includes? s "authenticator"))
      (is (not (str/includes? s "Password"))))
    (is (str/includes? (pr-str (login/view {:guard-type 2})) "emailed"))))

(deftest the-qr-panel-never-renders-a-token
  (testing "the challenge URL is shown on purpose; nothing else secret may be"
    (let [s (pr-str (login/view {:challenge-url "https://s.team/q/1/2"
                                 :qr-state :waiting}))]
      (is (str/includes? s "https://s.team/q/1/2"))
      (is (str/includes? s "Sign-in completes on its own")))))

(deftest the-approved-state-says-so
  (is (str/includes? (pr-str (login/view {:qr-state :approved})) "Approved")))
```

`find-node` is a small test helper that walks the description tree for the first
node of a given `:fx/type` — write it in the test namespace.

- [ ] **Step 2: Run and watch them fail.**

- [ ] **Step 3: Implement `login.clj`**

The skeleton below fixes the structure, the two departures and the styling
decisions; fill in the remaining copy from the mockup.

```clojure
;; Reliquary — Copyright (C) 2026 Timothy Baldridge
;; Licensed under the GNU General Public License v3 or later. See LICENSE.
(ns reliquary.ui.login
  (:require [reliquary.steam.qr :as qr]
            [reliquary.ui.theme :as theme])
  (:import (javafx.scene.canvas Canvas)
           (javafx.scene.paint Color)))

(def ^:private c theme/color)
(def ^:private qr-box 200.0)   ; the physical size the mock draws, in px

(defn draw-qr!
  "Draw the REAL zxing matrix scaled into a fixed box.

   The mock shows a 21x21 grid -- QR version 1, which holds about 25
   alphanumeric characters and cannot encode a Steam challenge URL. Real ones
   are 29-37 modules. Deriving the module size from qr/module-span keeps the
   card the same size whatever version zxing picks, so it looks identical to
   the design AND scans."
  [^Canvas canvas ^String url]
  (let [g    (.getGraphicsContext2D canvas)
        span (qr/module-span url)
        m    (qr/module-matrix url)
        px   (/ qr-box (double span))]
    (.setFill g (Color/web "#F2F0EE"))
    (.fillRect g 0 0 qr-box qr-box)
    (.setFill g (Color/web (:bg c)))
    (dotimes [y span]
      (dotimes [x span]
        (when (qr/dark-at? m x y)
          (.fillRect g (* x px) (* y px) px px))))))

(defn- field [{:keys [label value on-change secret?]}]
  {:fx/type :v-box :spacing 7
   :children [{:fx/type :label :text label
               :style (theme/style {:-fx-font-size 12 :-fx-text-fill (:text-muted c)})}
              {:fx/type (if secret? :password-field :text-field)
               :text value :on-text-changed on-change
               :min-height 42
               :style (theme/style {:-fx-background-color (:surface c)
                                    :-fx-border-color (:line-strong c)
                                    :-fx-border-radius 3 :-fx-background-radius 3
                                    :-fx-text-fill (:text c) :-fx-font-size 14})}]})

(defn credential-panel
  "The right half. When Steam demands a TYPED Guard code -- confirmation types
   2 (emailed) and 3 (authenticator) -- the password field is replaced by a code
   field. Types 4 and 5 are approved elsewhere and need no field. Without this
   swap the credential flow blocks forever with no explanation (spec 5)."
  [{:keys [account password guard-code guard-type on-account on-password on-guard on-submit]}]
  (let [guard? (#{2 3} guard-type)
        ready? (if guard? (seq guard-code) (and (seq account) (seq password)))]
    {:fx/type :v-box :spacing 18 :alignment :center-left
     :padding {:left 72 :right 72}
     :children
     [{:fx/type :label :text "Or use your account"
       :style (theme/style {:-fx-font-family (theme/mono-font) :-fx-font-size 12
                            :-fx-text-fill (:text-muted c)})}
      (field {:label "Account name" :value account :on-change on-account})
      (if guard?
        (field {:label (str "Steam Guard code ("
                            (case guard-type 2 "emailed to you" 3 "from your authenticator app")
                            ")")
                :value guard-code :on-change on-guard})
        (field {:label "Password" :value password :on-change on-password :secret? true}))
      {:fx/type :button :text "Sign in" :disable (not ready?) :on-action on-submit
       :min-height 42
       :style (theme/style (if ready?
                             {:-fx-background-color (:gold c) :-fx-text-fill (:bg c)
                              :-fx-background-radius 3 :-fx-font-size 14}
                             {:-fx-background-color (:surface c) :-fx-text-fill (:text-muted c)
                              :-fx-background-radius 3 :-fx-font-size 14}))}]}))

(defn qr-panel
  "The left half. The dot pulses while waiting and goes solid gold on approval;
   the explanatory sentence is load-bearing copy -- users look for a button that
   does not exist."
  [{:keys [challenge-url qr-state]}]
  (let [approved? (= :approved qr-state)]
    {:fx/type :v-box :alignment :center :spacing 22 :padding 48
     :children
     [{:fx/type :label :text "Scan to sign in"
       :style (theme/style {:-fx-font-family (theme/mono-font) :-fx-font-size 12
                            :-fx-text-fill (:text-muted c)})}
      {:fx/type :stack-pane
       :max-width (+ qr-box 36) :max-height (+ qr-box 36)
       :style (theme/style {:-fx-background-color "#F2F0EE"
                            :-fx-background-radius 6 :-fx-padding 18})
       :children (cond-> [{:fx/type fx/ext-on-instance-lifecycle
                           :on-created #(when challenge-url (draw-qr! % challenge-url))
                           :desc {:fx/type :canvas :width qr-box :height qr-box}}]
                   approved?
                   (conj {:fx/type :label :text "APPROVED"
                          :style (theme/style {:-fx-font-family (theme/mono-font)
                                               :-fx-font-size 12
                                               :-fx-text-fill (:bg c)})}))}
      {:fx/type :h-box :alignment :center :spacing 9
       :children [{:fx/type :region
                   :min-width 7 :min-height 7 :max-width 7 :max-height 7
                   :style (theme/style {:-fx-background-color (if approved? (:gold c) (:text-muted c))
                                        :-fx-background-radius 4})}
                  {:fx/type :label
                   :text (if approved? "Approved — signing in"
                             "Waiting for approval on your device")
                   :style (theme/style {:-fx-font-size 13 :-fx-text-fill (:text-muted c)})}]}
      {:fx/type :label :wrap-text true :max-width 290
       :text (str "Approve the request in the mobile app. "
                  "Sign-in completes on its own — there is no button here.")
       :style (theme/style {:-fx-font-size 12 :-fx-text-fill (:text-muted c)
                            :-fx-text-alignment "center"})}]}))

(defn view
  "The two halves, split by a hairline that fades at both ends -- a 1px region
   with a vertical gradient from transparent through `line` and back."
  [state]
  {:fx/type :h-box
   :children
   [(assoc (qr-panel state) :h-box/hgrow :always)
    {:fx/type :region
     :min-width 1 :max-width 1
     :style (theme/style
             {:-fx-background-color
              (str "linear-gradient(to bottom, transparent, " (:line c)
                   " 20%, " (:line c) " 80%, transparent)")})}
    (assoc (credential-panel state) :h-box/hgrow :always)]})
```

Note `fx/ext-on-instance-lifecycle` on the canvas: `:canvas` has no
`:on-created` prop of its own, and drawing needs the real `Node`. This is the
same cljfx idiom the packaging spike used — if it is written any other way the
canvas silently renders blank.

- [ ] **Step 4: Shoot THREE states and LOOK at all of them**

Render to `/tmp/reliquary-shots/`: `login-waiting.png` (a real Steam-shaped
challenge URL, so the QR is a realistic 29–37 modules), `login-approved.png`,
and `login-guard.png` (`:guard-type 3`).

**Read all three.** Confirm the QR is visually scannable (distinct dark modules
on white with a quiet zone), the disabled button reads as disabled, and the
guard panel replaced the password field. Report what you saw in each.

- [ ] **Step 5: Tests, then commit.**

---

### Task 5: `main.clj` — wire it to Steam and run it

**Files:**
- Create: `src/reliquary/main.clj`
- Modify: `deps.edn` — add an `:app` alias
- Modify: `build.clj` — default the uberjar main to `reliquary.main`
- Create: `test/reliquary/main_test.clj`

**Interfaces:**
- Consumes: everything above, plus `reliquary.steam.auth/login-qr!`,
  `reliquary.config/save-token!`, `reliquary.session`
- Produces: `-main`, and `(start-login! state)` which runs the blocking auth on
  a background thread and marshals events onto the FX thread

- [ ] **Step 1: Write the failing tests**

```clojure
(deftest a-qr-event-lands-in-state-on-the-fx-thread
  (let [state (atom {})]
    (with-redefs [auth/login-qr! (fn [on-event]
                                   (on-event {:type :qr :challenge-url "https://s.team/q/9"})
                                   {:refresh-token "SECRET" :account "someone"})
                  main/fx-run! (fn [f] (f))]   ; identity in tests
      @(main/start-login! state)
      (is (= "https://s.team/q/9" (:challenge-url @state))))))

(deftest the-token-reaches-config-and-never-state
  (let [state (atom {})]
    (with-redefs [auth/login-qr! (constantly {:refresh-token "SECRET" :account "someone"})
                  main/fx-run! (fn [f] (f))
                  config/save-token! (fn [t] (is (= "SECRET" (:refresh-token t))) t)]
      @(main/start-login! state)
      (is (not (str/includes? (pr-str @state) "SECRET"))
          "a token in the state atom is a token one render away from the screen"))))

(deftest a-failed-login-becomes-a-rendered-error-not-a-crash
  (let [state (atom {})]
    (with-redefs [auth/login-qr! (fn [_] (error/raise :unavailable "steam is down"))
                  main/fx-run! (fn [f] (f))]
      @(main/start-login! state)
      (is (str/includes? (str (:error @state)) "steam is down")))))
```

- [ ] **Step 2: Run and watch them fail.**

- [ ] **Step 3: Implement `main.clj`**

```clojure
;; Reliquary — Copyright (C) 2026 Timothy Baldridge
;; Licensed under the GNU General Public License v3 or later. See LICENSE.
(ns reliquary.main
  (:gen-class)
  (:require [cljfx.api :as fx]
            [reliquary.config :as config]
            [reliquary.steam.auth :as auth]
            [reliquary.ui.app :as app]
            [reliquary.ui.login :as login]
            [reliquary.ui.theme :as theme])
  (:import (javafx.application Platform)))

(defn fx-run!
  "Marshal onto the FX thread. A var so tests can replace it with identity."
  [f]
  (Platform/runLater f))

(defn start-login!
  "Run the BLOCKING QR login on a background thread, marshalling its events
   into `state`. Returns a future so callers (and tests) can await it.

   The refresh token deliberately never enters `state` -- a token in the state
   atom is one careless render away from being on screen. It goes straight to
   config/save-token!."
  [state]
  (future
    (try
      (let [result (auth/login-qr!
                    (fn [event]
                      (when (= :qr (:type event))
                        (fx-run! #(swap! state assoc
                                         :challenge-url (:challenge-url event)
                                         :qr-state :waiting)))
                      nil))]
        (config/save-token! result)
        (fx-run! #(swap! state assoc :qr-state :approved
                         :signed-in? true
                         :status-line (or (:account result) "signed in"))))
      (catch clojure.lang.ExceptionInfo e
        (fx-run! #(swap! state assoc :error (ex-message e)))))))

(defn view [state]
  (app/view (assoc state :content (login/view state))))

(defn -main [& _]
  (theme/load-fonts!)
  (let [state (atom {:screen :login :status-line "not signed in" :signed-in? false})
        renderer (fx/create-renderer :middleware (fx/wrap-map-desc #'view))]
    (fx/mount-renderer state renderer)
    (start-login! state)))
```

`-main` loads fonts, creates the state atom, mounts the renderer, and — if
`config/token` already holds a usable token — starts on a signed-in screen
instead of login. `fx-run!` wraps `Platform/runLater` and is a var so tests can
replace it.

Add to `deps.edn`:

```clojure
  :app {:jvm-opts  ["--enable-native-access=ALL-UNNAMED"
                    "--sun-misc-unsafe-memory-access=allow"]
        :main-opts ["-m" "reliquary.main"]}
```

- [ ] **Step 4: Run the real app and log in**

Run: `clojure -M:app`

This machine is already signed in, so **first run `clojure -M:cli logout`** to
get back to the login screen, then run the app, scan the QR with the Steam
mobile app, and confirm: the QR renders, the approved state appears, and a token
lands in `~/.config/reliquary/config.edn` at mode 0600.

**Take a screenshot of the running app and Read it.** Then run
`clojure -M:cli status` to prove the token the UI saved actually logs on.

- [ ] **Step 5: Package it and run the packaged binary**

```bash
clojure -T:build uber :main reliquary.main
./bin/package.sh
xvfb-run -a ./target/app/Reliquary/bin/Reliquary
du -sb target/app
```

Record the app-image size against the engine-era baseline of 73,399,361 bytes —
the fonts and the UI namespaces are the delta.

- [ ] **Step 6: Commit**

```bash
git add src/reliquary/main.clj test/reliquary/main_test.clj deps.edn build.clj
git commit -m "Open a real window and sign in to Steam through it"
```

---

## What this plan does not build

- The library grid, the version panel, ownership marking
- The download screen, its sparkline, artwork or quotes
- The done screen
- `art.clj` — capsule and screenshot fetching
- The credential (username/password) flow end to end — the panel and its
  Guard-code swap are built and tested as views, but only the QR flow is wired
  to Steam in Task 5
