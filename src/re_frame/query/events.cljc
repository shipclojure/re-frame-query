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

(defn- dispatch-hooks
  "Build :fx entries for dispatching lifecycle hook events.
   Each hook event vector gets `args` conj'd onto it."
  [hook-events & args]
  (when (seq hook-events)
    (mapv (fn [ev] [:dispatch (into ev args)])
          hook-events)))

(rf/reg-event-fx
  :re-frame.query/execute-mutation
  (fn [{:keys [db]} [_ k params opts]]
    (let [mutation-config (registry/get-mutation k)]
      (when-not mutation-config
        (throw (ex-info (str "No mutation registered for key: " k) {:key k})))
      (let [mutation-fn (:mutation-fn mutation-config)
            effect-fn   (or (:effect-fn mutation-config)
                            (registry/get-default-effect-fn))
            mid         (util/query-id k params)
            request     (mutation-fn params)
            hooks       (select-keys opts [:on-success :on-failure])
            effects     (if effect-fn
                          (effect-fn request
                                     [:re-frame.query/mutation-success k params hooks]
                                     [:re-frame.query/mutation-failure k params hooks])
                         ;; mutation-fn returns a full effects map
                          request)
            start-fx    (dispatch-hooks (:on-start opts) params)]
        (cond-> (merge
                 {:db (assoc-in db [:re-frame.query/mutations mid]
                                {:status :loading
                                 :error  nil})}
                 effects)
          (seq start-fx) (update :fx (fnil into []) start-fx))))))

(rf/reg-event-fx
  :re-frame.query/mutation-success
  (fn [{:keys [db]} [_ k params hooks data]]
    (let [mutation-config (registry/get-mutation k)
          mid             (util/query-id k params)
          invalidates-fn  (or (:invalidates mutation-config) (constantly []))
          tags            (invalidates-fn params)
          transform-fn    (:transform-response mutation-config)
          transformed     (cond-> data (fn? transform-fn) (transform-fn params))
          hook-fx         (dispatch-hooks (:on-success hooks) params transformed)]
      (cond-> {:db (assoc-in db [:re-frame.query/mutations mid]
                             {:status :success
                              :data   transformed
                              :error  nil})}
        (seq tags)    (update :fx (fnil into []) [[:dispatch [:re-frame.query/invalidate-tags tags]]])
        (seq hook-fx) (update :fx (fnil into []) hook-fx)))))

(rf/reg-event-fx
  :re-frame.query/mutation-failure
  (fn [{:keys [db]} [_ k params hooks error]]
    (let [mid             (util/query-id k params)
          mutation-config (registry/get-mutation k)
          transform-fn    (:transform-error mutation-config)
          transformed     (cond-> error (fn? transform-fn) (transform-fn params))
          hook-fx         (dispatch-hooks (:on-failure hooks) params transformed)]
      (cond-> {:db (assoc-in db [:re-frame.query/mutations mid]
                             {:status :error
                              :error  transformed})}
        (seq hook-fx) (assoc :fx hook-fx)))))

(rf/reg-event-db
  :re-frame.query/reset-mutation
  (fn [db [_ k params]]
    (let [mid (util/query-id k params)]
      (update db :re-frame.query/mutations dissoc mid))))

;; ---------------------------------------------------------------------------
;; Direct Cache Manipulation
;; ---------------------------------------------------------------------------

(rf/reg-event-db
  :re-frame.query/set-query-data
  (fn [db [_ k params data]]
    (let [qid (util/query-id k params)
          now (util/now-ms)]
      (update-in db [:re-frame.query/queries qid]
                 util/merge-with-default
                 {:status     :success
                  :data       data
                  :error      nil
                  :fetching?  false
                  :stale?     false
                  :fetched-at now}))))

;; ---------------------------------------------------------------------------
;; Invalidation
;; ---------------------------------------------------------------------------

(rf/reg-event-fx
  :re-frame.query/invalidate-tags
  (fn [{:keys [db]} [_ tags]]
    (let [queries  (get db :re-frame.query/queries {})
          matched  (volatile! #{})
         ;; Mark all matching queries as stale, tracking which were matched
          updated  (reduce-kv
                    (fn [acc qid q]
                      (if (util/tag-match? (:tags q) tags)
                        (do (vswap! matched conj qid)
                            (assoc acc qid (assoc q :stale? true)))
                        (assoc acc qid q)))
                    {}
                    queries)
         ;; Refetch only queries that were matched AND are currently active
         ;; Route infinite queries through refetch-infinite-query
          refetch-fx (->> @matched
                          (filter (fn [qid] (:active? (get updated qid))))
                          (mapv (fn [qid]
                                  (let [[k params] qid
                                        query-config (registry/get-query k)
                                        event-id (if (util/infinite-query? query-config)
                                                   :re-frame.query/refetch-infinite-query
                                                   :re-frame.query/refetch-query)]
                                    [:dispatch [event-id k params]]))))]
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
;; Infinite Query Events
;; ---------------------------------------------------------------------------

(def ^:private empty-infinite-data
  {:pages       []
   :page-params []
   :has-next?   false})

(defn- build-infinite-fetch-effects
  "Build effects to fetch a single page of an infinite query."
  [query-config k params cursor on-success-event]
  (let [query-fn   (:query-fn query-config)
        effect-fn  (or (:effect-fn query-config)
                       (registry/get-default-effect-fn))
        request    (query-fn (assoc params :cursor cursor))]
    (if effect-fn
      (effect-fn request
                 on-success-event
                 [:re-frame.query/infinite-page-failure k params])
      request)))

(defn- apply-max-pages
  "Trim pages/page-params to max-pages from the end (sliding window)."
  [pages page-params max-pages]
  (if (and max-pages (> (count pages) max-pages))
    [(subvec pages (- (count pages) max-pages))
     (subvec page-params (- (count page-params) max-pages))]
    [pages page-params]))

(rf/reg-event-fx
  :re-frame.query/ensure-infinite-query
  (fn [{:keys [db]} [_ k params]]
    (let [query-config (registry/get-query k)]
      (when-not query-config
        (throw (ex-info (str "No query registered for key: " k) {:key k})))
      (when-not (util/infinite-query? query-config)
        (throw (ex-info (str "Query " k " is not an infinite query (missing :infinite config)") {:key k})))
      (let [qid    (util/query-id k params)
            query  (get-in db [:re-frame.query/queries qid])
            now    (util/now-ms)
            {:keys [initial-cursor]} (:infinite query-config)]
        (if (and (util/stale? query now)
                 (not (:fetching? query)))
          (let [refreshing? (and (= :success (:status query))
                                 (some? (:data query)))]
            (merge {:db (update-in db [:re-frame.query/queries qid] util/merge-with-default
                                   {:status         (if refreshing? :success :loading)
                                    :data           (or (:data query) empty-infinite-data)
                                    :fetching?      true
                                    :fetching-next? false
                                    :stale?         false})}
                   (build-infinite-fetch-effects
                    query-config k params initial-cursor
                    [:re-frame.query/infinite-page-success k params nil])))
          {:db db})))))

(rf/reg-event-fx
  :re-frame.query/fetch-next-page
  (fn [{:keys [db]} [_ k params]]
    (let [query-config (registry/get-query k)]
      (when-not query-config
        (throw (ex-info (str "No query registered for key: " k) {:key k})))
      (let [qid   (util/query-id k params)
            query (get-in db [:re-frame.query/queries qid])
            data  (:data query)]
       ;; No-op if: no data yet, no next cursor, or already fetching
        (if (and data
                 (:has-next? data)
                 (not (:fetching? query))
                 (not (:fetching-next? query)))
          (let [next-cursor (get-in data [:next-cursor])]
            (merge {:db (update-in db [:re-frame.query/queries qid] merge
                                   {:fetching-next? true})}
                   (build-infinite-fetch-effects
                    query-config k params next-cursor
                    [:re-frame.query/infinite-page-success k params :append])))
          {:db db})))))

(rf/reg-event-fx
  :re-frame.query/infinite-page-success
  (fn [{:keys [db]} [_ k params mode page-data]]
    (let [qid             (util/query-id k params)
          query-config    (registry/get-query k)
          query           (get-in db [:re-frame.query/queries qid])
          now             (util/now-ms)
          tags-fn         (or (:tags query-config) (constantly []))
          tags            (set (tags-fn params))
          transform-fn    (:transform-response query-config)
          transformed     (cond-> page-data (fn? transform-fn) (transform-fn params))
          infinite-cfg    (:infinite query-config)
          get-next-cursor (:get-next-cursor infinite-cfg)
          next-cursor     (get-next-cursor transformed)
          max-pages       (:max-pages query-config)
          refetch-state   (:refetch-state query)
          base-state      {:status         :success
                           :error          nil
                           :fetching?      false
                           :fetching-next? false
                           :fetched-at     now
                           :stale?         false
                           :tags           tags
                           :stale-time-ms  (:stale-time-ms query-config)
                           :cache-time-ms  (or (:cache-time-ms query-config)
                                               gc/default-cache-time-ms)}
          make-data       (fn [pages page-params]
                            {:pages       pages
                             :page-params page-params
                             :has-next?   (some? next-cursor)
                             :next-cursor next-cursor})]
      (cond
       ;; --- Sequential re-fetch mode ---
       ;; Pages accumulate in refetch-state, not in :data (atomic swap)
        (some? refetch-state)
        (let [acc-pages      (conj (:pages refetch-state) transformed)
              acc-params     (conj (:page-params refetch-state)
                                   (:current-cursor refetch-state))
              pages-fetched  (count acc-pages)
              target         (:target-page-count refetch-state)
              done?          (or (>= pages-fetched target)
                                 (nil? next-cursor))
              [final-pages final-params] (apply-max-pages
                                          (vec acc-pages) (vec acc-params) max-pages)]
          (if done?
           ;; All pages re-fetched — atomic swap into :data
            {:db (update-in db [:re-frame.query/queries qid] merge
                            (assoc base-state
                                   :data          (make-data final-pages final-params)
                                   :refetch-state nil))}
           ;; More pages needed — continue the chain
            (merge
             {:db (update-in db [:re-frame.query/queries qid] merge
                             {:refetch-state (assoc refetch-state
                                                    :pages acc-pages
                                                    :page-params acc-params
                                                    :current-cursor next-cursor)})}
             (build-infinite-fetch-effects
              query-config k params next-cursor
              [:re-frame.query/infinite-page-success k params nil]))))

       ;; --- Append mode (fetch-next-page) ---
        (= mode :append)
        (let [old-data     (:data query)
              new-pages    (conj (:pages old-data) transformed)
              new-params   (conj (:page-params old-data)
                                 (:next-cursor old-data))
              [final-pages final-params] (apply-max-pages
                                          (vec new-pages) (vec new-params) max-pages)]
          {:db (update-in db [:re-frame.query/queries qid] merge
                          (assoc base-state
                                 :data (make-data final-pages final-params)))})

       ;; --- Initial first page load ---
        :else
        {:db (update-in db [:re-frame.query/queries qid] merge
                        (assoc base-state
                               :data (make-data [transformed]
                                                [(:initial-cursor infinite-cfg)])))}))))

(rf/reg-event-db
  :re-frame.query/infinite-page-failure
  (fn [db [_ k params error]]
    (let [qid          (util/query-id k params)
          query-config (registry/get-query k)
          transform-fn (:transform-error query-config)]
     ;; On failure: set error, clear refetch-state, preserve old :data
      (update-in db [:re-frame.query/queries qid] merge
                 {:status         :error
                  :error          (cond-> error (fn? transform-fn) (transform-fn params))
                  :fetching?      false
                  :fetching-next? false
                  :refetch-state  nil}))))

;; Modified refetch-query for infinite queries — starts sequential re-fetch
(rf/reg-event-fx
  :re-frame.query/refetch-infinite-query
  (fn [{:keys [db]} [_ k params]]
    (let [query-config (registry/get-query k)
          qid          (util/query-id k params)
          query        (get-in db [:re-frame.query/queries qid])
          data         (:data query)
          page-count   (count (:pages data))
          {:keys [initial-cursor]} (:infinite query-config)]
      (when-not query-config
        (throw (ex-info (str "No query registered for key: " k) {:key k})))
      (if (pos? page-count)
       ;; Has pages — start sequential re-fetch
        (merge
         {:db (update-in db [:re-frame.query/queries qid] merge
                         {:fetching?     true
                          :stale?        false
                          :refetch-state {:target-page-count page-count
                                          :pages             []
                                          :page-params       []
                                          :current-cursor    initial-cursor}})}
         (build-infinite-fetch-effects
          query-config k params initial-cursor
          [:re-frame.query/infinite-page-success k params nil]))
       ;; No pages yet — just fetch the first page
        (merge
         {:db (update-in db [:re-frame.query/queries qid] util/merge-with-default
                         {:status         :loading
                          :data           empty-infinite-data
                          :fetching?      true
                          :fetching-next? false
                          :stale?         false})}
         (build-infinite-fetch-effects
          query-config k params initial-cursor
          [:re-frame.query/infinite-page-success k params nil]))))))

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
