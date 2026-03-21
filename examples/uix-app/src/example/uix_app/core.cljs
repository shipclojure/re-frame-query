(ns example.uix-app.core
  "Entry point for the re-frame-query UIx demo app.
   MSW (Mock Service Worker) is loaded as a separate ESM bundle
   (mocks-bundle.js) and sets window.__mswReady when the service
   worker is active. We wait for that promise before rendering."
  (:require
   ;; Side-effect requires — register effects, queries, and UI state
   [example.uix-app.http-fx]
   [example.uix-app.queries]
   [example.uix-app.ui]
   [example.uix-app.views :as views]
   [re-frame.query :as rfq]
   [uix.core :refer [$]]
   [uix.dom]))

;; Log all rfq events to the browser console during development
(when ^boolean goog.DEBUG
  (rfq/enable-debug-logging!))

(defonce root
  (uix.dom/create-root (.getElementById js/document "root")))

(defn render []
  (uix.dom/render-root ($ views/app) root))

(defn ^:export init
  "Called once on page load. Waits for MSW then mounts the app."
  []
  (if-let [ready (.-__mswReady js/window)]
    ;; MSW bundle loaded — wait for worker to be active
    (.then ready (fn [] (render)))
    ;; No MSW bundle (production?) — render immediately
    (render)))

(defn ^:dev/after-load refresh
  "Called by shadow-cljs after hot-reload."
  []
  (render))
