(ns re-frame.query.gc
  "Garbage collection timer management for re-frame-query.

  GC timers live outside app-db to keep the re-frame store fully serializable.
  This follows the same pattern as TanStack Query (private field on Removable)
  and RTK Query (local object in middleware closure).

  When a query becomes inactive (all subscribers gone), a per-query timer is
  scheduled. After `cache-time-ms` elapses, the query is removed from the
  cache — unless a new subscriber appeared in the meantime, which cancels
  the timer."
  (:require
   [re-frame.core :as rf]))

;; ---------------------------------------------------------------------------
;; Defaults
;; ---------------------------------------------------------------------------

(def default-cache-time-ms
  "Default cache time (5 minutes) used when a query definition does not
  specify its own :cache-time-ms. Matches TanStack Query's client-side default."
  (* 5 60 1000))

;; ---------------------------------------------------------------------------
;; Side-channel timer state (outside app-db — not serializable by design)
;; ---------------------------------------------------------------------------

(defonce ^:private gc-timers
  (atom {})) ;; {query-id → timeout-handle}

;; ---------------------------------------------------------------------------
;; Timer operations (platform-specific)
;; ---------------------------------------------------------------------------

(defn- set-timeout
  "Schedule `f` to run after `ms` milliseconds. Returns a timeout handle."
  [f ms]
  #?(:cljs (js/setTimeout f ms)
     :clj (let [fut (future
                      (Thread/sleep ms)
                      (f))]
            fut)))

(defn- clear-timeout
  "Cancel a previously scheduled timeout."
  [handle]
  #?(:cljs (js/clearTimeout handle)
     :clj (future-cancel handle)))

;; ---------------------------------------------------------------------------
;; Public API
;; ---------------------------------------------------------------------------

(defn cancel-gc!
  "Cancel any pending GC timer for `query-id`."
  [query-id]
  (when-let [handle (get @gc-timers query-id)]
    (clear-timeout handle)
    (swap! gc-timers dissoc query-id)))

(defn schedule-gc!
  "Start a GC timer for `query-id`. After `cache-time-ms`, dispatches
   :re-frame.query/remove-query to evict the entry if it's still inactive.
   Cancels any existing timer for this query-id first."
  [query-id cache-time-ms]
  (when (and cache-time-ms
             (number? cache-time-ms)
             (pos? cache-time-ms)
             (not= cache-time-ms ##Inf))
    ;; Cancel any existing timer for this query
    (cancel-gc! query-id)
    (let [handle (set-timeout
                  (fn [] (rf/dispatch [:re-frame.query/remove-query query-id]))
                  cache-time-ms)]
      (swap! gc-timers assoc query-id handle))))

(defn cancel-all!
  "Cancel all pending GC timers. Used when resetting state (e.g., in tests)."
  []
  (doseq [[_ handle] @gc-timers]
    (clear-timeout handle))
  (reset! gc-timers {}))

(defn active-timers
  "Returns the set of query-ids with active GC timers. For testing/debugging."
  []
  (set (keys @gc-timers)))

;; ---------------------------------------------------------------------------
;; Re-frame Effects
;; ---------------------------------------------------------------------------

(rf/reg-fx
  :re-frame.query/schedule-gc
  (fn [{:keys [query-id cache-time-ms]}]
    (schedule-gc! query-id cache-time-ms)))

(rf/reg-fx
  :re-frame.query/cancel-gc
  (fn [{:keys [query-id]}]
    (cancel-gc! query-id)))
