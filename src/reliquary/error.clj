;; Reliquary — Copyright (C) 2026 Timothy Baldridge
;; Licensed under the GNU General Public License v3 or later. See LICENSE.
;;
;; Copied from the mauvi mod manager, whose Steam client this is. Copied
;; deliberately rather than depended on: Reliquary shares no runtime, no store
;; and no build with mauvi, and this code is Reliquary's to change.
(ns reliquary.error
  "One raise for the whole codebase. Every escape path carries :reliquary/error so
   cli/-main can map it to an exit code instead of stack-tracing at a user.

   Categories: :unauthenticated (no usable Steam token -- exit 4),
   :unavailable (a resource is held or unreachable -- exit 2), :io (exit 3),
   :incorrect (bad input or a rejected request -- exit 1).

   NEVER put a password or a refresh token in `data`. Error maps get printed,
   logged, and pasted into bug reports.")

(defn raise
  "Throw an ex-info categorized for the CLI's exit-code contract."
  ([category msg] (raise category msg {}))
  ([category msg data]
   (throw (ex-info msg (assoc data :reliquary/error category)))))
