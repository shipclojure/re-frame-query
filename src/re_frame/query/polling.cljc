(ns re-frame.query.polling
  "Polling timer management for re-frame-query.

  Polling timers live outside app-db (like GC timers) to keep the re-frame
  store fully serializable.

  Multiple subscribers to the same query can each request a different polling
  interval. The effective interval is the minimum of all active, positive
  subscriber intervals — matching RTK Query's behaviour.

  When a query subscription includes :polling-interval-ms (or the query
  definition has one), a repeating timer dispatches
  :re-frame.query/refetch-query at the effective interval."
  (:require
   [re-frame.core :as rf]))

;; ---------------------------------------------------------------------------
;; Side-channel timer state (outside app-db — not serializable by design)
;; ---------------------------------------------------------------------------

(defonce ^:private poll-state
  ;; {query-id → {:subscribers {sub-id → interval-ms}
  ;;              :handle      <interval-handle or nil>
  ;;              :k           keyword
  ;;              :params      map}}
  (atom {}))

;; ---------------------------------------------------------------------------
;; Interval operations (platform-specific)
;; ---------------------------------------------------------------------------

(defn- set-interval
  "Schedule `f` to run every `ms` milliseconds. Returns an interval handle."
  [f ms]
  #?(:cljs (js/setInterval f ms)
     :clj (let [running (atom true)
                fut (future
                      (while @running
                        (Thread/sleep ms)
                        (when @running (f))))]
            {:future fut :running running})))

(defn- clear-interval
  "Cancel a previously scheduled interval."
  [handle]
  #?(:cljs (js/clearInterval handle)
     :clj (do (reset! (:running handle) false)
              (future-cancel (:future handle)))))

;; ---------------------------------------------------------------------------
;; Internal helpers
;; ---------------------------------------------------------------------------

(defn effective-interval
  "Compute the minimum positive interval across all subscribers for a query.
   Returns nil if no subscriber has a positive interval."
  [subscribers]
  (let [intervals (->> (vals subscribers)
                       (filter (fn [ms] (and ms (pos? ms)))))]
    (when (seq intervals)
      (apply min intervals))))

(defn- sync-timer!
  "Ensure the running timer for `query-id` matches the effective interval.
   Starts, restarts, or stops the timer as needed."
  [query-id]
  (let [{:keys [subscribers handle k params]} (get @poll-state query-id)
        target-ms (effective-interval subscribers)]
    ;; Stop the current timer (if any)
    (when handle
      (clear-interval handle))
    (if target-ms
      ;; Start a new timer at the effective interval
      (let [new-handle (set-interval
                        (fn [] (rf/dispatch [:re-frame.query/poll-refetch k params]))
                        target-ms)]
        (swap! poll-state assoc-in [query-id :handle] new-handle))
      ;; No positive intervals → stop polling, clean up entry
      (swap! poll-state dissoc query-id))))

;; ---------------------------------------------------------------------------
;; Public API
;; ---------------------------------------------------------------------------

(defn add-subscriber!
  "Register a subscriber's polling interval for `query-id`.
   Recomputes the effective interval and restarts the timer if needed.
   No-op if `interval-ms` is nil, zero, or negative."
  [query-id sub-id k params interval-ms]
  (when (and interval-ms (pos? interval-ms))
    (swap! poll-state
           (fn [state]
             (-> state
                 (assoc-in [query-id :subscribers sub-id] interval-ms)
                 (assoc-in [query-id :k] k)
                 (assoc-in [query-id :params] params))))
    (sync-timer! query-id)))

(defn remove-subscriber!
  "Unregister a subscriber's polling interval for `query-id`.
   Recomputes the effective interval and restarts or stops the timer."
  [query-id sub-id]
  (when (get-in @poll-state [query-id :subscribers sub-id])
    (swap! poll-state update-in [query-id :subscribers] dissoc sub-id)
    (sync-timer! query-id)))

(defn cancel-all!
  "Cancel all active polling timers. Used when resetting state (e.g., in tests)."
  []
  (doseq [[_ {:keys [handle]}] @poll-state]
    (when handle
      (clear-interval handle)))
  (reset! poll-state {}))

(defn active-polls
  "Returns the set of query-ids with active polling timers. For testing/debugging."
  []
  (->> @poll-state
       (filter (fn [[_ v]] (:handle v)))
       (map key)
       set))

(defn subscriber-count
  "Returns the number of active polling subscribers for `query-id`. For testing."
  [query-id]
  (count (get-in @poll-state [query-id :subscribers])))

(defn current-interval
  "Returns the effective polling interval for `query-id`, or nil. For testing."
  [query-id]
  (effective-interval (get-in @poll-state [query-id :subscribers])))

;; ---------------------------------------------------------------------------
;; Re-frame Effects
;; ---------------------------------------------------------------------------

(rf/reg-fx
  :re-frame.query/start-poll
  (fn [{:keys [query-id sub-id k params interval-ms]}]
    (add-subscriber! query-id sub-id k params interval-ms)))

(rf/reg-fx
  :re-frame.query/stop-poll
  (fn [{:keys [query-id sub-id]}]
    (remove-subscriber! query-id sub-id)))
