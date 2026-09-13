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
;; Shared query resolution
;; ---------------------------------------------------------------------------

(def ^:private idle-state
  "Shape returned by query subscriptions when no data exists in app-db.
   Derived from util/default-query, excluding internal bookkeeping keys
   (:active?, :tags) that are not relevant to subscription consumers."
  (select-keys util/default-query [:status :data :error :fetching? :stale?]))

(def ^:private idle-infinite-state
  "Shape returned by infinite query subscriptions when no data exists.
   Extends idle-state with infinite-query-specific fields."
  (assoc idle-state
         :data {:pages [] :page-params [] :has-next? false :has-prev? false}
         :fetching-next? false
         :fetching-prev? false))

(defn- resolve-query
  "Look up a query by qid, compute stale?, strip the internal :request-id
   (a bare refetch must not re-render subscribers), return idle-state if absent."
  [queries qid]
  (if-let [query (get queries qid)]
    (let [now (util/now-ms)
          stale (util/stale? query now)]
      (-> query
          (assoc :stale? stale)
          (dissoc :request-id)))
    idle-state))

(defn- resolve-infinite-query
  "Like resolve-query but also strips internal :refetch-state."
  [queries qid]
  (if-let [query (get queries qid)]
    (let [now (util/now-ms)
          stale (util/stale? query now)]
      (-> query
          (assoc :stale? stale)
          (dissoc :request-id :refetch-state)))
    idle-infinite-state))

;; ---------------------------------------------------------------------------
;; Layer 3: Query subscriptions (with automatic active tracking)
;; ---------------------------------------------------------------------------

(rf/reg-sub-raw
  :re-frame.query/query
  (fn [app-db [_ & args]]
    (let [{k :query params :params skip? :skip? :as opts}
          (-> (util/normalize-payload :re-frame.query/query
                                      args
                                      [:query :params :opts]
                                      {:reject-keys util/mutation-only-hook-keys})
              util/flatten-opts)
          qid (util/query-id k params)
          sub-id (gensym "poll-sub-")
          mark-active-payload (assoc (dissoc opts :skip?) :sub-id sub-id)]
      (when-not skip?
        (rf/dispatch [:re-frame.query/ensure-query {:query k :params params}])
        (rf/dispatch [:re-frame.query/mark-active mark-active-payload]))
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
                      (rf/dispatch [:re-frame.query/mark-inactive
                                    {:query k
                                     :params params
                                     :sub-id sub-id}])))))
        reaction))))

;; ---------------------------------------------------------------------------
;; Infinite Query subscription (with automatic active tracking)
;; ---------------------------------------------------------------------------

(rf/reg-sub-raw
  :re-frame.query/infinite-query
  (fn [app-db [_ & args]]
    (let [{k :query params :params}
          (util/normalize-payload :re-frame.query/infinite-query
                                  args
                                  [:query :params]
                                  {:reject-keys util/mutation-only-hook-keys})
          qid (util/query-id k params)]
      (rf/dispatch [:re-frame.query/ensure-infinite-query {:query k :params params}])
      (rf/dispatch [:re-frame.query/mark-active {:query k :params params}])
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
                    (rf/dispatch [:re-frame.query/mark-inactive {:query k
                                                                 :params params}]))))
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
  (fn [queries [_ & args]]
    (let [{k :query params :params}
          (util/normalize-payload :re-frame.query/query-state
                                  args
                                  [:query :params]
                                  {:reject-keys util/mutation-only-hook-keys})]
      (resolve-query queries (util/query-id k params)))))

(rf/reg-sub
  :re-frame.query/infinite-query-state
  :<- [:re-frame.query/queries]
  (fn [queries [_ & args]]
    (let [{k :query params :params}
          (util/normalize-payload :re-frame.query/infinite-query-state
                                  args
                                  [:query :params]
                                  {:reject-keys util/mutation-only-hook-keys})]
      (resolve-infinite-query queries (util/query-id k params)))))

;; ---------------------------------------------------------------------------
;; Derived query subscriptions
;; ---------------------------------------------------------------------------

(rf/reg-sub
  :re-frame.query/query-data
  (fn [[_ & args] _]
    (let [{k :query params :params}
          (util/normalize-payload :re-frame.query/query-data
                                  args
                                  [:query :params]
                                  {:reject-keys util/mutation-only-hook-keys})]
      (rf/subscribe [:re-frame.query/query-state k params])))
  (fn [query _]
    (:data query)))

(rf/reg-sub
  :re-frame.query/query-status
  (fn [[_ & args] _]
    (let [{k :query params :params}
          (util/normalize-payload :re-frame.query/query-status
                                  args
                                  [:query :params]
                                  {:reject-keys util/mutation-only-hook-keys})]
      (rf/subscribe [:re-frame.query/query-state k params])))
  (fn [query _]
    (:status query)))

(rf/reg-sub
  :re-frame.query/query-fetching?
  (fn [[_ & args] _]
    (let [{k :query params :params}
          (util/normalize-payload :re-frame.query/query-fetching?
                                  args
                                  [:query :params]
                                  {:reject-keys util/mutation-only-hook-keys})]
      (rf/subscribe [:re-frame.query/query-state k params])))
  (fn [query _]
    (:fetching? query)))

(rf/reg-sub
  :re-frame.query/query-error
  (fn [[_ & args] _]
    (let [{k :query params :params}
          (util/normalize-payload :re-frame.query/query-error
                                  args
                                  [:query :params]
                                  {:reject-keys util/mutation-only-hook-keys})]
      (rf/subscribe [:re-frame.query/query-state k params])))
  (fn [query _]
    (:error query)))

;; ---------------------------------------------------------------------------
;; Derived infinite query subscriptions
;; ---------------------------------------------------------------------------

(rf/reg-sub
  :re-frame.query/infinite-query-data
  (fn [[_ & args] _]
    (let [{k :query params :params}
          (util/normalize-payload :re-frame.query/infinite-query-data
                                  args
                                  [:query :params]
                                  {:reject-keys util/mutation-only-hook-keys})]
      (rf/subscribe [:re-frame.query/infinite-query-state k params])))
  (fn [query _]
    (:data query)))

;; ---------------------------------------------------------------------------
;; Mutation subscriptions
;; ---------------------------------------------------------------------------

(rf/reg-sub
  :re-frame.query/mutation
  :<- [:re-frame.query/mutations]
  (fn [mutations [_ & args]]
    (let [{k :mutation params :params}
          (util/normalize-payload :re-frame.query/mutation
                                  args
                                  [:mutation :params])
          mid (util/query-id k params)]
      (get mutations mid
           {:status :idle
            :error nil}))))

(rf/reg-sub
  :re-frame.query/mutation-status
  (fn [[_ & args] _]
    (let [{k :mutation params :params}
          (util/normalize-payload :re-frame.query/mutation-status
                                  args
                                  [:mutation :params])]
      (rf/subscribe [:re-frame.query/mutation k params])))
  (fn [mutation _]
    (:status mutation)))
