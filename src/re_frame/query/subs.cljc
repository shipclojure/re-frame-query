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
;; Shared query resolution
;; ---------------------------------------------------------------------------

(def ^:private idle-state
  {:status :idle
   :data nil
   :error nil
   :fetching? false
   :stale? true})

(def ^:private idle-infinite-state
  {:status :idle
   :data {:pages [] :page-params [] :has-next? false :has-prev? false}
   :error nil
   :fetching? false
   :fetching-next? false
   :fetching-prev? false
   :stale? true})

(defn- resolve-query
  "Look up a query by qid, compute stale?, return idle-state if absent."
  [queries qid]
  (if-let [query (get queries qid)]
    (let [now (util/now-ms)
          stale (util/stale? query now)]
      (assoc query :stale? stale))
    idle-state))

(defn- resolve-infinite-query
  "Like resolve-query but strips internal :refetch-state."
  [queries qid]
  (if-let [query (get queries qid)]
    (let [now (util/now-ms)
          stale (util/stale? query now)]
      (-> query
          (assoc :stale? stale)
          (dissoc :refetch-state)))
    idle-infinite-state))

;; ---------------------------------------------------------------------------
;; Layer 3: Query subscriptions (with automatic active tracking)
;; ---------------------------------------------------------------------------

(rf/reg-sub-raw
  :re-frame.query/query
  (fn [app-db [_ k params opts]]
    (let [qid (util/query-id k params)
          skip? (:skip? opts)
          sub-id (gensym "poll-sub-")
          query-config (registry/get-query k)
          interval-ms (or (:polling-interval-ms opts)
                          (:polling-interval-ms query-config))]
      (when-not skip?
        (rf/dispatch [:re-frame.query/ensure-query k params])
        (rf/dispatch [:re-frame.query/mark-active k params])
        (polling/add-subscriber! qid sub-id k params interval-ms))
      (let [reaction
            #?(:cljs
               (ratom/make-reaction
                (fn []
                  (if skip?
                    idle-state
                    (resolve-query (:re-frame.query/queries @app-db) qid))))
               :clj
               (atom
                (if skip?
                  idle-state
                  (resolve-query (:re-frame.query/queries @app-db) qid))))]
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

(rf/reg-sub-raw
  :re-frame.query/infinite-query
  (fn [app-db [_ k params]]
    (let [qid (util/query-id k params)]
      (rf/dispatch [:re-frame.query/ensure-infinite-query k params])
      (rf/dispatch [:re-frame.query/mark-active k params])
      (let [reaction
            #?(:cljs
               (ratom/make-reaction
                (fn []
                  (resolve-infinite-query (:re-frame.query/queries @app-db) qid)))
               :clj
               (atom
                (resolve-infinite-query (:re-frame.query/queries @app-db) qid)))]
        #?(:cljs (ratom/add-on-dispose!
                  reaction
                  (fn []
                    (rf/dispatch [:re-frame.query/mark-inactive k params]))))
        reaction))))

;; ---------------------------------------------------------------------------
;; Passive query subscriptions (no side effects)
;; ---------------------------------------------------------------------------

;; Pure read from app-db — does NOT trigger fetching, mark-active, polling,
;; or GC. Same return shape as ::rfq/query. Use when you manage the query
;; lifecycle yourself via events (ensure-query, mark-active, mark-inactive).

(rf/reg-sub
  :re-frame.query/query-state
  :<- [:re-frame.query/queries]
  (fn [queries [_ k params]]
    (resolve-query queries (util/query-id k params))))

(rf/reg-sub
  :re-frame.query/infinite-query-state
  :<- [:re-frame.query/queries]
  (fn [queries [_ k params]]
    (resolve-infinite-query queries (util/query-id k params))))

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
            :error nil}))))

(rf/reg-sub
  :re-frame.query/mutation-status
  (fn [[_ k params] _]
    (rf/subscribe [:re-frame.query/mutation k params]))
  (fn [mutation _]
    (:status mutation)))
