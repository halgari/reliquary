;; Reliquary — Copyright (C) 2026 Timothy Baldridge
;; Licensed under the GNU General Public License v3 or later. See LICENSE.
;;
;; Copied from the mauvi mod manager, whose Steam client this is. Copied
;; deliberately rather than depended on: Reliquary shares no runtime, no store
;; and no build with mauvi, and this code is Reliquary's to change.
(ns reliquary.steam.qr
  "Render a QR code (the Steam mobile-app login challenge URL) for the console:
  a scannable ANSI block grid printed inline, plus a PNG written to a temp file
  for terminals that mangle the blocks."
  (:import (com.google.zxing.qrcode.encoder Encoder QRCode ByteMatrix)
           (com.google.zxing.qrcode.decoder ErrorCorrectionLevel)
           (java.awt Color)
           (java.awt.image BufferedImage)
           (java.io File)
           (javax.imageio ImageIO)))

(def ^:private ^:const quiet 4)   ; module-wide white border scanners need
(def ^:private esc (str (char 27)))

(defn module-matrix ^ByteMatrix [^String text]
  ;; medium error correction — plenty for a short URL, keeps the grid small
  (.getMatrix ^QRCode (Encoder/encode text ErrorCorrectionLevel/M)))

(defn module-span
  "Width of the rendered QR in modules, including the quiet zone on both sides."
  ^long [^String text]
  (let [m (module-matrix text)]
    (+ (.getWidth m) (* 2 quiet))))

(defn dark-at?
  "Is module (x,y) dark, treating the quiet-zone border as light?"
  [^ByteMatrix m ^long x ^long y]
  (let [w (.getWidth m) h (.getHeight m)
        mx (- x quiet) my (- y quiet)]
    (and (>= mx 0) (< mx w) (>= my 0) (< my h)
         (= 1 (.get m (int mx) (int my))))))

(defn terminal-string
  "The QR as a string of ANSI-coloured blocks: each module is two cells wide so
  it stays square, dark modules on a black background, light on bright white.
  Reliable regardless of the terminal's own colour theme."
  ^String [^String text]
  (let [dark (str esc "[40m  ")      ; black background, two spaces
        light (str esc "[107m  ")    ; bright-white background
        reset (str esc "[0m")
        m (module-matrix text)
        span (+ (.getWidth m) (* 2 quiet))
        sb (StringBuilder.)]
    (dotimes [y span]
      (dotimes [x span]
        (.append sb ^String (if (dark-at? m x y) dark light)))
      (.append sb reset)
      (.append sb "\n"))
    (.toString sb)))

(defn write-png!
  "Render the QR to a PNG at path (8px per module, white quiet zone). Returns
  the path."
  ^String [^String text ^String path]
  (let [m (module-matrix text)
        scale 8
        span (+ (.getWidth m) (* 2 quiet))
        px (int (* span scale))
        img (BufferedImage. px px BufferedImage/TYPE_INT_RGB)
        g (.createGraphics img)]
    (try
      (.setColor g Color/WHITE)
      (.fillRect g 0 0 px px)
      (.setColor g Color/BLACK)
      (dotimes [y span]
        (dotimes [x span]
          (when (dark-at? m x y)
            (.fillRect g (int (* x scale)) (int (* y scale)) scale scale))))
      (finally (.dispose g)))
    (ImageIO/write img "png" (File. path))
    path))

(defn show!
  "Print a scannable QR for text to stdout and also drop a PNG in the temp dir,
  printing its path. Returns the PNG path."
  [^String text]
  (let [png (str (System/getProperty "java.io.tmpdir") "/mauvi-steam-qr.png")]
    (println (terminal-string text))
    (write-png! text png)
    (println "  (if the blocks don't scan, open the image:" png ")")
    (println "  or open the challenge URL on your phone:" text)
    png))
