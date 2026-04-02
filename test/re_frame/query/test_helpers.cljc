(ns re-frame.query.test-helpers
  "Shared test helpers, fixtures, and utilities for re-frame-query tests."
  (:require
   [clojure.test :refer [use-fixtures]]
   [re-frame.core :as rf]
   [re-frame.db :as rf-db]
   [re-frame.query :as rfq]
   [re-frame.query.gc :as gc]
   [re-frame.query.polling :as polling]))

(defn reset-db! []
  (reset! rf-db/app-db {})
  (rfq/clear-registry!)
  (gc/cancel-all!)
  (polling/cancel-all!))

;; Each test namespace must call (use-fixtures :each {:before reset-db! :after reset-db!})
;; directly — use-fixtures registers on the calling namespace via *ns* (CLJ)
;; or the compile-time namespace (CLJS), so wrapping it in a helper function
;; would register fixtures on *this* namespace instead of the caller's.

(defn app-db [] @rf-db/app-db)

(defn process-event
  "Dispatch an event synchronously for testing."
  [event]
  (rf/dispatch-sync event))

(def noop-effect-fn
  "No-op effect adapter for tests that need an effect-fn
   but don't care about the actual HTTP effects."
  (fn [_ _ _] {}))
