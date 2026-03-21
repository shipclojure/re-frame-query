(ns re-frame.query.events
  "Re-frame event handlers for query and mutation lifecycle."
  (:require
   [re-frame.core :as rf]
   [re-frame.query.gc :as gc]
   [re-frame.query.polling :as polling]
   [re-frame.query.registry :as registry]
   [re-frame.query.util :as util]))

;; ---------------------------------------------------------------------------
;; Query Events
;; ---------------------------------------------------------------------------

(defn- build-query-effects [query-config k params]
  (let [query-fn     (:query-fn query-config)
        effect-fn    (or (:effect-fn query-config)
                         (registry/get-default-effect-fn))
        request      (query-fn params)]
    (if effect-fn
      (effect-fn request
                 [:re-frame.query/query-success k params]
                 [:re-frame.query/query-failure k params])
      request)))

(rf/reg-event-fx
  :re-frame.query/ensure-query
  (fn [{:keys [db]} [_ k params]]
    (let [query-config (registry/get-query k)]
      (when-not query-config
        (throw (ex-info (str "No query registered for key: " k) {:key k})))
      (let [qid    (util/query-id k params)
            query  (get-in db [:re-frame.query/queries qid])
            now    (util/now-ms)]
        (if (and (util/stale? query now)
                 (not (:fetching? query)))
          (let [refreshing?  (and (= :success (:status query))
                                  (some? (:data query)))]
            (merge {:db (update-in db [:re-frame.query/queries qid] util/merge-with-default
                          {:status    (if refreshing? :success :loading)
                           :fetching? true
                           :stale?    false})}
                   (build-query-effects query-config k params)))
          {:db db})))))

(rf/reg-event-fx
  :re-frame.query/refetch-query
  (fn [{:keys [db]} [_ k params]]
    (let [qid          (util/query-id k params)
          query        (get-in db [:re-frame.query/queries qid])
          refreshing?  (and (= :success (:status query))
                            (some? (:data query)))
          query-config (registry/get-query k)]
      (when-not query-config
        (throw (ex-info (str "No query registered for key: " k) {:key k})))
      (merge {:db (update-in db [:re-frame.query/queries qid] merge
                    {:status    (if refreshing? :success :loading)
                     :fetching? true
                     :stale?    false})}
             (build-query-effects query-config k params)))))

(rf/reg-event-db
  :re-frame.query/query-success
  (fn [db [_ k params data]]
    (let [qid            (util/query-id k params)
          query-config   (registry/get-query k)
          now            (util/now-ms)
          tags-fn        (or (:tags query-config) (constantly []))
          tags           (set (tags-fn params))
          transform-fn   (:transform-response query-config)]
      (update-in db [:re-frame.query/queries qid]
                 util/merge-with-default
                 {:status        :success
                  :data          (cond-> data (fn? transform-fn) (transform-fn params))
                  :error         nil
                  :fetching?     false
                  :fetched-at    now
                  :stale?        false
                  :tags          tags
                  :stale-time-ms (:stale-time-ms query-config)
                  :cache-time-ms (or (:cache-time-ms query-config)
                                     gc/default-cache-time-ms)}))))

(rf/reg-event-db
  :re-frame.query/query-failure
  (fn [db [_ k params error]]
    (let [qid          (util/query-id k params)
          query-config (registry/get-query k)
          transform-fn (:transform-error query-config)]
      (update-in db [:re-frame.query/queries qid] util/merge-with-default
                 {:status    :error
                  :error     (cond-> error (fn? transform-fn) (transform-fn params))
                  :fetching? false}))))

;; ---------------------------------------------------------------------------
;; Mutation Events
;; ---------------------------------------------------------------------------

(rf/reg-event-fx
  :re-frame.query/execute-mutation
  (fn [{:keys [db]} [_ k params]]
    (let [mutation-config (registry/get-mutation k)]
      (when-not mutation-config
        (throw (ex-info (str "No mutation registered for key: " k) {:key k})))
      (let [mutation-fn (:mutation-fn mutation-config)
            effect-fn   (or (:effect-fn mutation-config)
                            (registry/get-default-effect-fn))
            mid         (util/query-id k params)
            request     (mutation-fn params)
            effects     (if effect-fn
                          (effect-fn request
                                     [:re-frame.query/mutation-success k params]
                                     [:re-frame.query/mutation-failure k params])
                          ;; mutation-fn returns a full effects map
                          request)]
        (merge
          {:db (assoc-in db [:re-frame.query/mutations mid]
                         {:status :loading
                          :error  nil})}
          effects)))))

(rf/reg-event-fx
  :re-frame.query/mutation-success
  (fn [{:keys [db]} [_ k params data]]
    (let [mutation-config (registry/get-mutation k)
          mid             (util/query-id k params)
          invalidates-fn  (or (:invalidates mutation-config) (constantly []))
          tags            (invalidates-fn params)
          transform-fn    (:transform-response mutation-config)]
      (cond-> {:db (assoc-in db [:re-frame.query/mutations mid]
                             {:status :success
                              :data   (cond-> data (fn? transform-fn) (transform-fn params))
                              :error  nil})}
        (seq tags) (assoc :fx [[:dispatch [:re-frame.query/invalidate-tags tags]]])))))

(rf/reg-event-db
  :re-frame.query/mutation-failure
  (fn [db [_ k params error]]
    (let [mid             (util/query-id k params)
          mutation-config (registry/get-mutation k)
          transform-fn    (:transform-error mutation-config)]
      (assoc-in db [:re-frame.query/mutations mid]
                {:status :error
                 :error  (cond-> error (fn? transform-fn) (transform-fn params))}))))

;; ---------------------------------------------------------------------------
;; Invalidation
;; ---------------------------------------------------------------------------

(rf/reg-event-fx
  :re-frame.query/invalidate-tags
  (fn [{:keys [db]} [_ tags]]
    (let [queries  (get db :re-frame.query/queries {})
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
                                   [:re-frame.query/refetch-query (first qid) (second qid)]])))]
      {:db (assoc db :re-frame.query/queries updated)
       :fx refetch-fx})))

;; ---------------------------------------------------------------------------
;; Reset
;; ---------------------------------------------------------------------------

(rf/reg-fx
  :re-frame.query/cancel-all-timers
  (fn [_]
    (gc/cancel-all!)
    (polling/cancel-all!)))

(rf/reg-event-fx
  :re-frame.query/reset-api-state
  (fn [{:keys [db]} _]
    {:db (-> db
             (dissoc :re-frame.query/queries)
             (dissoc :re-frame.query/mutations))
     :re-frame.query/cancel-all-timers true}))

;; ---------------------------------------------------------------------------
;; Active Tracking
;; ---------------------------------------------------------------------------

(rf/reg-event-fx
  :re-frame.query/mark-active
  (fn [{:keys [db]} [_ k params]]
    (let [qid (util/query-id k params)]
      {:db                       (assoc-in db [:re-frame.query/queries qid :active?] true)
       :re-frame.query/cancel-gc {:query-id qid}})))

(rf/reg-event-fx
  :re-frame.query/mark-inactive
  (fn [{:keys [db]} [_ k params]]
    (let [qid        (util/query-id k params)
          query      (get-in db [:re-frame.query/queries qid])
          cache-time (or (:cache-time-ms query) gc/default-cache-time-ms)]
      {:db                         (assoc-in db [:re-frame.query/queries qid :active?] false)
       :re-frame.query/schedule-gc {:query-id      qid
                                    :cache-time-ms cache-time}})))

;; ---------------------------------------------------------------------------
;; Garbage Collection
;; ---------------------------------------------------------------------------

(rf/reg-event-db
  :re-frame.query/remove-query
  (fn [db [_ qid]]
    (let [query (get-in db [:re-frame.query/queries qid])]
      (if (and query (not (:active? query)))
        ;; Query is still inactive — safe to remove
        (update db :re-frame.query/queries dissoc qid)
        ;; Query became active again — leave it alone (timer was a no-op)
        db))))

(rf/reg-event-db
  :re-frame.query/garbage-collect
  (fn [db [_ now]]
    (let [now (or now (util/now-ms))]
      (update db :re-frame.query/queries
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
