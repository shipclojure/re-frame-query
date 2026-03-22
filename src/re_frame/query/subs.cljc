(ns re-frame.query.subs
  "Re-frame subscriptions for accessing query and mutation state.
   The primary :re-frame.query/query subscription uses reg-sub-raw to
   automatically track active queries via the Reagent Reaction lifecycle."
  (:require
   [re-frame.core :as rf]
   [re-frame.query.polling :as polling]
   [re-frame.query.registry :as registry]
   [re-frame.query.util :as util]
   #?(:cljs [reagent.ratom :as ratom])))

;; ---------------------------------------------------------------------------
;; Layer 2: Extraction subscriptions
;; ---------------------------------------------------------------------------

(rf/reg-sub
  :re-frame.query/queries
  (fn [db _]
    (:re-frame.query/queries db)))

(rf/reg-sub
  :re-frame.query/mutations
  (fn [db _]
    (:re-frame.query/mutations db)))

;; ---------------------------------------------------------------------------
;; Layer 3: Query subscriptions (with automatic active tracking)
;; ---------------------------------------------------------------------------

(def ^:private idle-state
  {:status    :idle
   :data      nil
   :error     nil
   :fetching? false
   :stale?    true})

(rf/reg-sub-raw
  :re-frame.query/query
  (fn [app-db [_ k params opts]]
    (let [qid           (util/query-id k params)
          skip?         (:skip? opts)
          sub-id        (gensym "poll-sub-")
          query-config  (registry/get-query k)
          ;; Per-subscription interval overrides query-level default
          interval-ms   (or (:polling-interval-ms opts)
                            (:polling-interval-ms query-config))]
      (when-not skip?
        ;; Automatically fetch the query and mark it active when subscribed.
        ;; This is the core ergonomic win — subscribing is all you need.
        (rf/dispatch [:re-frame.query/ensure-query k params])
        (rf/dispatch [:re-frame.query/mark-active k params])
        ;; Register this subscriber's polling interval. The polling system
        ;; computes the effective interval as min of all active subscribers.
        (polling/add-subscriber! qid sub-id k params interval-ms))
      (let [reaction
            #?(:cljs
               (ratom/make-reaction
                 (fn []
                   (if skip?
                     idle-state
                     (let [db      @app-db
                           queries (:re-frame.query/queries db)
                           query   (get queries qid)]
                       (if query
                         (let [now   (util/now-ms)
                               stale (util/stale? query now)]
                           (assoc query :stale? stale))
                         idle-state)))))
               ;; CLJ fallback — no Reaction lifecycle, just compute
               :clj
               (atom
                 (if skip?
                   idle-state
                   (let [db      @app-db
                         queries (:re-frame.query/queries db)
                         query   (get queries qid)]
                     (if query
                       (let [now   (util/now-ms)
                             stale (util/stale? query now)]
                         (assoc query :stale? stale))
                       idle-state)))))]
        ;; When the Reaction is disposed (all subscribing components unmounted):
        ;; unregister this subscriber's poll and mark the query inactive.
        #?(:cljs (ratom/add-on-dispose!
                   reaction
                   (fn []
                     (when-not skip?
                       (polling/remove-subscriber! qid sub-id)
                       (rf/dispatch [:re-frame.query/mark-inactive k params])))))
        reaction))))

;; ---------------------------------------------------------------------------
;; Infinite Query subscription (with automatic active tracking)
;; ---------------------------------------------------------------------------

(def ^:private idle-infinite-state
  {:status         :idle
   :data           {:pages [] :page-params [] :has-next? false}
   :error          nil
   :fetching?      false
   :fetching-next? false
   :stale?         true})

(rf/reg-sub-raw
  :re-frame.query/infinite-query
  (fn [app-db [_ k params]]
    (let [qid (util/query-id k params)]
      ;; Fetch first page and mark active
      (rf/dispatch [:re-frame.query/ensure-infinite-query k params])
      (rf/dispatch [:re-frame.query/mark-active k params])
      (let [reaction
            #?(:cljs
               (ratom/make-reaction
                 (fn []
                   (let [db      @app-db
                         queries (:re-frame.query/queries db)
                         query   (get queries qid)]
                     (if query
                       (let [now   (util/now-ms)
                             stale (util/stale? query now)]
                         (-> query
                             (assoc :stale? stale)
                             (dissoc :refetch-state)))
                       idle-infinite-state))))
               :clj
               (atom
                 (let [db      @app-db
                       queries (:re-frame.query/queries db)
                       query   (get queries qid)]
                   (if query
                     (let [now   (util/now-ms)
                           stale (util/stale? query now)]
                       (-> query
                           (assoc :stale? stale)
                           (dissoc :refetch-state)))
                     idle-infinite-state))))]
        #?(:cljs (ratom/add-on-dispose!
                   reaction
                   (fn []
                     (rf/dispatch [:re-frame.query/mark-inactive k params]))))
        reaction))))

;; ---------------------------------------------------------------------------
;; Derived query subscriptions
;; ---------------------------------------------------------------------------

(rf/reg-sub
  :re-frame.query/query-data
  (fn [[_ k params] _]
    (rf/subscribe [:re-frame.query/query k params]))
  (fn [query _]
    (:data query)))

(rf/reg-sub
  :re-frame.query/query-status
  (fn [[_ k params] _]
    (rf/subscribe [:re-frame.query/query k params]))
  (fn [query _]
    (:status query)))

(rf/reg-sub
  :re-frame.query/query-fetching?
  (fn [[_ k params] _]
    (rf/subscribe [:re-frame.query/query k params]))
  (fn [query _]
    (:fetching? query)))

(rf/reg-sub
  :re-frame.query/query-error
  (fn [[_ k params] _]
    (rf/subscribe [:re-frame.query/query k params]))
  (fn [query _]
    (:error query)))

;; ---------------------------------------------------------------------------
;; Mutation subscriptions
;; ---------------------------------------------------------------------------

(rf/reg-sub
  :re-frame.query/mutation
  :<- [:re-frame.query/mutations]
  (fn [mutations [_ k params]]
    (let [mid (util/query-id k params)]
      (get mutations mid
           {:status :idle
            :error  nil}))))

(rf/reg-sub
  :re-frame.query/mutation-status
  (fn [[_ k params] _]
    (rf/subscribe [:re-frame.query/mutation k params]))
  (fn [mutation _]
    (:status mutation)))
