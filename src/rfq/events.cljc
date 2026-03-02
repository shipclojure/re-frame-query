(ns rfq.events
  "Re-frame event handlers for query and mutation lifecycle."
  (:require
   [re-frame.core :as rf]
   [rfq.gc :as gc]
   [rfq.registry :as registry]
   [rfq.util :as util]))

;; ---------------------------------------------------------------------------
;; Query Events
;; ---------------------------------------------------------------------------

(rf/reg-event-fx
 :rfq/ensure-query
 (fn [{:keys [db]} [_ k params]]
   (let [qid    (util/query-id k params)
         query  (get-in db [:rfq/queries qid])
         now    (util/now-ms)]
     (if (and (util/stale? query now)
              (not (:fetching? query)))
       (let [query-config (registry/get-query k)]
         (when-not query-config
           (throw (ex-info (str "No query registered for key: " k) {:key k})))
         {:db (update-in db [:rfq/queries qid] merge
                         {:status     :loading
                          :fetching?  true
                          :stale?     false})
          :fx [[:dispatch [:rfq/execute-query-effect k params]]]})
       {:db db}))))

(rf/reg-event-fx
 :rfq/refetch-query
 (fn [{:keys [db]} [_ k params]]
   (let [qid          (util/query-id k params)
         query-config (registry/get-query k)]
     (when-not query-config
       (throw (ex-info (str "No query registered for key: " k) {:key k})))
     {:db (update-in db [:rfq/queries qid] merge
                     {:status    :loading
                      :fetching? true
                      :stale?    false})
      :fx [[:dispatch [:rfq/execute-query-effect k params]]]})))

(rf/reg-event-fx
 :rfq/execute-query-effect
 (fn [_ [_ k params]]
   (let [query-config (registry/get-query k)
         query-fn     (:query-fn query-config)]
     (query-fn params))))

(rf/reg-event-db
 :rfq/query-success
 (fn [db [_ k params data]]
   (let [qid          (util/query-id k params)
         query-config (registry/get-query k)
         now          (util/now-ms)
         tags-fn      (or (:tags query-config) (constantly []))
         tags         (set (tags-fn params))]
     (update-in db [:rfq/queries qid] merge
                {:status        :success
                 :data          data
                 :error         nil
                 :fetching?     false
                 :fetched-at    now
                 :stale?        false
                 :tags          tags
                 :stale-time-ms (:stale-time-ms query-config)
                 :cache-time-ms (or (:cache-time-ms query-config)
                                    gc/default-cache-time-ms)}))))

(rf/reg-event-db
 :rfq/query-failure
 (fn [db [_ k params error]]
   (let [qid (util/query-id k params)]
     (update-in db [:rfq/queries qid] merge
                {:status    :error
                 :error     error
                 :fetching? false}))))

;; ---------------------------------------------------------------------------
;; Mutation Events
;; ---------------------------------------------------------------------------

(rf/reg-event-fx
 :rfq/execute-mutation
 (fn [{:keys [db]} [_ k params]]
   (let [mutation-config (registry/get-mutation k)]
     (when-not mutation-config
       (throw (ex-info (str "No mutation registered for key: " k) {:key k})))
     (let [mutation-fn (:mutation-fn mutation-config)
           mid         (util/query-id k params)
           effects     (mutation-fn params)]
       (merge
        {:db (assoc-in db [:rfq/mutations mid]
                       {:status :loading
                        :error  nil})}
        effects)))))

(rf/reg-event-fx
 :rfq/mutation-success
 (fn [{:keys [db]} [_ k params data]]
   (let [mutation-config (registry/get-mutation k)
         mid             (util/query-id k params)
         invalidates-fn  (or (:invalidates mutation-config) (constantly []))
         tags            (invalidates-fn params)]
     {:db (assoc-in db [:rfq/mutations mid]
                    {:status :success
                     :data   data
                     :error  nil})
      :fx (when (seq tags)
            [[:dispatch [:rfq/invalidate-tags tags]]])})))

(rf/reg-event-db
 :rfq/mutation-failure
 (fn [db [_ k params error]]
   (let [mid (util/query-id k params)]
     (assoc-in db [:rfq/mutations mid]
               {:status :error
                :error  error}))))

;; ---------------------------------------------------------------------------
;; Invalidation
;; ---------------------------------------------------------------------------

(rf/reg-event-fx
 :rfq/invalidate-tags
 (fn [{:keys [db]} [_ tags]]
   (let [queries  (get db :rfq/queries {})
         ;; Mark all matching queries as stale
         updated  (reduce-kv
                   (fn [acc qid q]
                     (if (util/tag-match? (:tags q) tags)
                       (assoc acc qid (assoc q :stale? true))
                       (assoc acc qid q)))
                   {}
                   queries)
         ;; Refetch queries that are currently active
         refetch-fx (->> updated
                         (filter (fn [[_ q]] (:active? q)))
                         (mapv (fn [[qid _]]
                                 [:dispatch
                                  [:rfq/refetch-query (first qid) (second qid)]])))]
     {:db (assoc db :rfq/queries updated)
      :fx refetch-fx})))

;; ---------------------------------------------------------------------------
;; Active Tracking
;; ---------------------------------------------------------------------------

(rf/reg-event-fx
 :rfq/mark-active
 (fn [{:keys [db]} [_ k params]]
   (let [qid (util/query-id k params)]
     {:db          (assoc-in db [:rfq/queries qid :active?] true)
      :rfq/cancel-gc {:query-id qid}})))

(rf/reg-event-fx
 :rfq/mark-inactive
 (fn [{:keys [db]} [_ k params]]
   (let [qid        (util/query-id k params)
         query      (get-in db [:rfq/queries qid])
         cache-time (or (:cache-time-ms query) gc/default-cache-time-ms)]
     {:db            (assoc-in db [:rfq/queries qid :active?] false)
      :rfq/schedule-gc {:query-id      qid
                         :cache-time-ms cache-time}})))

;; ---------------------------------------------------------------------------
;; Garbage Collection
;; ---------------------------------------------------------------------------

(rf/reg-event-db
 :rfq/remove-query
 (fn [db [_ qid]]
   (let [query (get-in db [:rfq/queries qid])]
     (if (and query (not (:active? query)))
       ;; Query is still inactive — safe to remove
       (update db :rfq/queries dissoc qid)
       ;; Query became active again — leave it alone (timer was a no-op)
       db))))

(rf/reg-event-db
 :rfq/garbage-collect
 (fn [db [_ now]]
   (let [now (or now (util/now-ms))]
     (update db :rfq/queries
             (fn [queries]
               (reduce-kv
                (fn [acc qid q]
                  (let [cache-ms  (:cache-time-ms q)
                        fetched   (:fetched-at q 0)
                        expired?  (and cache-ms
                                       fetched
                                       (> (- now fetched) cache-ms))]
                    (if (and expired? (not (:active? q)))
                      acc
                      (assoc acc qid q))))
                {}
                queries))))))
