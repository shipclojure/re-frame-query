(ns example.reagent-app.core
  "Entry point for the re-frame-query Reagent demo app.
   MSW (Mock Service Worker) is loaded as a separate ESM bundle
   (mocks-bundle.js) and sets window.__mswReady when the service
   worker is active. We wait for that promise before rendering."
  (:require
   [reagent.dom.client :as rdc]
   ;; Side-effect requires — register :http effect + query/mutation definitions
   [example.reagent-app.http-fx]
   [example.reagent-app.queries]
   [example.reagent-app.views :as views]))

(defonce root (rdc/create-root (.getElementById js/document "app")))

(defn render []
  (rdc/render root [views/app]))

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
