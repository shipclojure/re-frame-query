(ns re-frame.query.subs
  "Re-frame subscriptions for accessing query and mutation state.
   The primary :re-frame.query/query subscription uses reg-sub-raw to
   automatically track active queries via the Reagent Reaction lifecycle."
  (:require
   [re-frame.core :as rf]
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

(rf/reg-sub-raw
  :re-frame.query/query
  (fn [app-db [_ k params]]
    (let [qid (util/query-id k params)]
      ;; Automatically fetch the query and mark it active when subscribed.
      ;; This is the core ergonomic win — subscribing is all you need.
      (rf/dispatch [:re-frame.query/ensure-query k params])
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
                         (assoc query :stale? stale))
                       {:status    :idle
                        :data      nil
                        :error     nil
                        :fetching? false
                        :stale?    true}))))
               ;; CLJ fallback — no Reaction lifecycle, just compute
               :clj
               (let [db      @app-db
                     queries (:re-frame.query/queries db)
                     query   (get queries qid)]
                 (atom
                   (if query
                     (let [now   (util/now-ms)
                           stale (util/stale? query now)]
                       (assoc query :stale? stale))
                     {:status    :idle
                      :data      nil
                      :error     nil
                      :fetching? false
                      :stale?    true}))))]
        ;; Mark inactive when the Reaction is disposed
        ;; (all subscribing components have unmounted)
        #?(:cljs (ratom/add-on-dispose!
                   reaction
                   (fn [] (rf/dispatch [:re-frame.query/mark-inactive k params]))))
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
