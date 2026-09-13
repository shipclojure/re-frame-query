(ns re-frame.query.events
  "Re-frame event handlers for query and mutation lifecycle."
  (:require
   [re-frame.core :as rf]
   [re-frame.query.db :as qdb]
   [re-frame.query.gc :as gc]
   [re-frame.query.polling :as polling]
   [re-frame.query.registry :as registry]
   [re-frame.query.util :as util]))

;; ---------------------------------------------------------------------------
;; Request identity
;; ---------------------------------------------------------------------------

(rf/reg-cofx
  :re-frame.query/request-id
  ;; One arity: `(inject-cofx id)` calls the handler with the coeffects only.
  ;; A 2-arity fn silently works in CLJS but throws on the JVM.
  ;; `random-uuid` is Clojure 1.11+, so the :clj branch goes direct to
  (fn [cofx]
    (assoc cofx :re-frame.query/request-id
           (util/gen-request-id))))

(def ^:private inject-request-id
  "Interceptor supplying the fresh id every issuing handler stamps its attempt
   with. Generating the id in a coeffect keeps the handlers pure."
  (rf/inject-cofx :re-frame.query/request-id))

(defn- make-request-control
  "Per-attempt identity stamp carried as metadata on the result callbacks.
   `:issued-at` is diagnostics only — supersession compares `:request-id`."
  [k params req-id]
  {:query-id (util/query-id k params)
   :request-id req-id
   :issued-at (util/mono-now)})

(defn- resolve-effect-fn
  "The effect adapter for `config` — its own `:effect-fn` or the global
   default. Throws when neither is configured: running without an adapter
   is an unconfigured-library state, not a supported mode."
  [config k]
  (or (:effect-fn config)
      (registry/get-default-effect-fn)
      (throw (ex-info (str "re-frame-query: no effect adapter configured for " k
                           " — set a global adapter with `rfq/set-default-effect-fn!`"
                           " (or `:default-effect-fn` in `rfq/init!`),"
                           " or provide a per-query `:effect-fn`.")
                      {:key k}))))

(defn- start-query-attempt
  "Write `query-state` plus the new `:request-id` onto the entry for `k`/`params`,
   creating it with defaults if absent. Any attempt still in flight is thereby
   superseded and its response dropped on arrival. Returns the updated db.

   Every issuing handler goes through here, so a request can never be started
   against an entry that has no `:request-id` (supersession silently off), and
   no path can create a skeletal entry: refetching an uncached query used to
   `merge` onto nil, producing an entry missing every `default-query` key
   (`:active?`, `:tags`, `:data`, …) that `ensure-query` would have supplied."
  [db k params request-id query-state]
  (when (nil? request-id)
    (throw (ex-info "re-frame-query: missing `:re-frame.query/request-id` coeffect — add (rf/inject-cofx :re-frame.query/request-id) to this event handler"
                    {:query-id (util/query-id k params)})))
  (update-in db [:re-frame.query/queries (util/query-id k params)]
             util/merge-with-default
             (assoc query-state :request-id request-id)))

;; ---------------------------------------------------------------------------
;; Query Events
;; ---------------------------------------------------------------------------

(defn- build-query-effects [query-config k params req-id]
  (let [query-fn (:query-fn query-config)
        effect-fn (resolve-effect-fn query-config k)
        request (query-fn params)
        control (make-request-control k params req-id)]
    (effect-fn request
               (util/with-request-control [:re-frame.query/query-success k params] control)
               (util/with-request-control [:re-frame.query/query-failure k params] control))))

(rf/reg-event-fx
  :re-frame.query/ensure-query
  [inject-request-id]
  (fn [{:keys [db] :re-frame.query/keys [request-id]} [_ & args]]
    (let [{k :query params :params} (util/normalize-payload :re-frame.query/ensure-query args
                                                            [:query :params]
                                                            {:reject-keys util/mutation-only-hook-keys})
          query-config (registry/get-query! k)
          _ (when (util/infinite-query? query-config)
              (throw (ex-info (str "Query " k " is an infinite query — use :re-frame.query/ensure-infinite-query instead")
                              {:key k})))
          qid (util/query-id k params)
          query (qdb/get-query db qid)
          now (util/now-ms)]
      (if (and (util/stale? query now)
               (not (:fetching? query)))
        (let [refreshing? (and (= :success (:status query))
                               (some? (:data query)))]
          (merge {:db (start-query-attempt db k params request-id
                                           {:status (if refreshing? :success :loading)
                                            :fetching? true
                                            :stale? false})}
                 (build-query-effects query-config k params request-id)))
        {:db db}))))

(defn- refetch-effects
  "Build the effects map for refetching a query. Shared by refetch-query
   and poll-refetch. Stamps the new :request-id so any older attempt still in
   flight is superseded and its response dropped on arrival."
  [db query-config k params request-id]
  (let [qid (util/query-id k params)
        query (get-in db [:re-frame.query/queries qid])
        refreshing? (and (= :success (:status query))
                         (some? (:data query)))]
    (merge {:db (start-query-attempt db k params request-id
                                     {:status (if refreshing? :success :loading)
                                      :fetching? true
                                      :stale? false})}
           (build-query-effects query-config k params request-id))))

(rf/reg-event-fx
  :re-frame.query/refetch-query
  [inject-request-id]
  (fn [{:keys [db] :re-frame.query/keys [request-id]} [_ & args]]
    (let [{k :query params :params} (util/normalize-payload :re-frame.query/refetch-query args
                                                            [:query :params]
                                                            {:reject-keys util/mutation-only-hook-keys})
          query-config (registry/get-query! k)]
      (refetch-effects db query-config k params request-id))))

(rf/reg-event-fx
  :re-frame.query/poll-refetch
  [inject-request-id]
  (fn [{:keys [db] :re-frame.query/keys [request-id]} [_ k params]]
    (let [query-config (registry/get-query! k)
          qid (util/query-id k params)
          query (get-in db [:re-frame.query/queries qid])
          force? (= :force (:polling-mode query-config))]
      (if (and (not force?) (:fetching? query))
        {:db db}
        (refetch-effects db query-config k params request-id)))))

(rf/reg-event-db
  :re-frame.query/query-success
  (fn [db [_ k params data :as event]]
    (let [qid (util/query-id k params)
          query (get-in db [:re-frame.query/queries qid])
          req-id (:request-id (util/request-control event))]
      (if-not (util/current-attempt? query req-id)
       ;; Superseded by a newer attempt — drop the result. Leave :fetching?,
       ;; :status, :data and :error alone: the newer attempt is still in flight.
        db
        (let [query-config (registry/get-query k)
              now (util/now-ms)
              tags-fn (or (:tags query-config) (constantly []))
              tags (set (tags-fn params))
              transform-fn (:transform-response query-config)]
          (update-in db [:re-frame.query/queries qid]
                     util/merge-with-default
                     {:status :success
                      :data (cond-> data (fn? transform-fn) (transform-fn params))
                      :error nil
                      :fetching? false
                      :fetched-at now
                      :stale? false
                      :tags tags
                      :stale-time-ms (:stale-time-ms query-config)
                      :cache-time-ms (or (:cache-time-ms query-config)
                                         gc/default-cache-time-ms)}))))))

(rf/reg-event-db
  :re-frame.query/query-failure
  (fn [db [_ k params error :as event]]
    (let [qid (util/query-id k params)
          query (get-in db [:re-frame.query/queries qid])
          req-id (:request-id (util/request-control event))]
      (if-not (util/current-attempt? query req-id)
       ;; Superseded — a late failure must not flip a newer success to :error.
        db
        (let [query-config (registry/get-query k)
              transform-fn (:transform-error query-config)]
          (update-in db [:re-frame.query/queries qid] util/merge-with-default
                     {:status :error
                      :error (cond-> error (fn? transform-fn) (transform-fn params))
                      :fetching? false}))))))

;; ---------------------------------------------------------------------------
;; Cancellation
;; ---------------------------------------------------------------------------

(rf/reg-event-fx
  :re-frame.query/cancel-query
  [inject-request-id]
  (fn [{:keys [db] :re-frame.query/keys [request-id]} [_ & args]]
    (let [{k :query params :params} (util/normalize-payload :re-frame.query/cancel-query args
                                                            [:query :params]
                                                            {:reject-keys util/mutation-only-hook-keys})]
      {:db (qdb/cancel-query db k params (registry/get-query! k) request-id)})))

;; ---------------------------------------------------------------------------
;; Mutation Events
;; ---------------------------------------------------------------------------

(defn- dispatch-hooks
  "Build :fx entries for dispatching lifecycle hook events.
   `hook` is a single event vector or a collection of them; each event
   gets `args` conj'd onto it. Returns [] when no hooks are configured."
  [hook & args]
  (mapv (fn [ev] [:dispatch (into ev args)])
        (util/normalize-hook-events hook)))

(rf/reg-event-fx
  :re-frame.query/execute-mutation
  (fn [{:keys [db]} [_ & args]]
    (let [{k :mutation params :params :as flat} (-> (util/normalize-payload :re-frame.query/execute-mutation
                                                                            args
                                                                            [:mutation :params :opts])
                                                    util/flatten-opts)
          mutation-config (registry/get-mutation! k)
          mutation-fn (:mutation-fn mutation-config)
          effect-fn (resolve-effect-fn mutation-config k)
          mid (util/query-id k params)
          request (mutation-fn params)
          hooks (select-keys flat [:on-success :on-failure])
          effects (effect-fn request
                             [:re-frame.query/mutation-success k params hooks]
                             [:re-frame.query/mutation-failure k params hooks])
          start-fx (dispatch-hooks (:on-start flat) params)]
      (-> (merge
           {:db (assoc-in db [:re-frame.query/mutations mid]
                          {:status :loading
                           :error nil})
            :fx []}
           effects)
          (update :fx into start-fx)))))

(rf/reg-event-fx
  :re-frame.query/mutation-success
  (fn [{:keys [db]} [_ k params hooks data]]
    (let [mutation-config (registry/get-mutation k)
          mid (util/query-id k params)
          invalidates-fn (or (:invalidates mutation-config) (constantly []))
          tags (invalidates-fn params)
          transform-fn (:transform-response mutation-config)
          transformed (cond-> data (fn? transform-fn) (transform-fn params))
          invalidate-fx (when (seq tags)
                          [[:dispatch [:re-frame.query/invalidate-tags {:tags tags}]]])
          hook-fx (dispatch-hooks (:on-success hooks) params transformed)]
      (-> {:db (assoc-in db [:re-frame.query/mutations mid]
                         {:status :success
                          :data transformed
                          :error nil})
           :fx []}
          (update :fx into invalidate-fx)
          (update :fx into hook-fx)))))

(rf/reg-event-fx
  :re-frame.query/mutation-failure
  (fn [{:keys [db]} [_ k params hooks error]]
    (let [mid (util/query-id k params)
          mutation-config (registry/get-mutation k)
          transform-fn (:transform-error mutation-config)
          transformed (cond-> error (fn? transform-fn) (transform-fn params))
          hook-fx (dispatch-hooks (:on-failure hooks) params transformed)]
      (-> {:db (assoc-in db [:re-frame.query/mutations mid]
                         {:status :error
                          :error transformed})
           :fx []}
          (update :fx into hook-fx)))))

(rf/reg-event-db
  :re-frame.query/reset-mutation
  (fn [db [_ & args]]
    (let [{k :mutation params :params} (util/normalize-payload :re-frame.query/reset-mutation
                                                               args
                                                               [:mutation :params])
          mid (util/query-id k params)]
      (update db :re-frame.query/mutations dissoc mid))))

;; ---------------------------------------------------------------------------
;; Direct Cache Manipulation
;; ---------------------------------------------------------------------------

(rf/reg-event-db
  :re-frame.query/set-query-data
  (fn [db [_ & args]]
    (let [{k :query params :params data :data}
          (util/normalize-payload :re-frame.query/set-query-data
                                  args
                                  [:query :params :data]
                                  {:reject-keys util/mutation-only-hook-keys})]
      (qdb/set-query-data db k params data))))

;; ---------------------------------------------------------------------------
;; Invalidation
;; ---------------------------------------------------------------------------

(rf/reg-event-fx
  :re-frame.query/invalidate-tags
  (fn [{:keys [db]} [_ & args]]
    (let [{:keys [tags]} (util/normalize-payload :re-frame.query/invalidate-tags args [:tags]
                                                 {:reject-keys util/mutation-only-hook-keys})
          queries (get db :re-frame.query/queries {})
          matched (volatile! #{})
         ;; Mark all matching queries as stale, tracking which were matched
          updated (reduce-kv
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
                                    [:dispatch [event-id {:query k
                                                          :params params}]]))))]
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
  {:pages []
   :page-params []
   :has-next? false
   :has-prev? false})

(defn- build-infinite-fetch-effects
  "Build effects to fetch a single page of an infinite query.
   `req-id` stamps both callbacks so superseded pages can be dropped. A
   sequential re-fetch chain keeps one id across all of its pages — it is
   one logical attempt."
  [query-config k params cursor on-success-event req-id]
  (let [query-fn (:query-fn query-config)
        effect-fn (resolve-effect-fn query-config k)
        request (query-fn (assoc params :cursor cursor))
        control (make-request-control k params req-id)]
    (effect-fn request
               (util/with-request-control on-success-event control)
               (util/with-request-control
                 [:re-frame.query/infinite-page-failure k params] control))))

(defn- apply-max-pages
  "Trim pages/page-params to max-pages (sliding window).
   direction :forward (default) — trim from the start, keep the latest pages.
   direction :backward — trim from the end, keep the earliest pages."
  ([pages page-params max-pages]
   (apply-max-pages pages page-params max-pages :forward))
  ([pages page-params max-pages direction]
   (if (and max-pages (> (count pages) max-pages))
     (if (= direction :backward)
       [(subvec pages 0 max-pages)
        (subvec page-params 0 max-pages)]
       [(subvec pages (- (count pages) max-pages))
        (subvec page-params (- (count page-params) max-pages))])
     [pages page-params])))

(rf/reg-event-fx
  :re-frame.query/ensure-infinite-query
  [inject-request-id]
  (fn [{:keys [db] :re-frame.query/keys [request-id]} [_ & args]]
    (let [{k :query params :params} (util/normalize-payload :re-frame.query/ensure-infinite-query
                                                            args
                                                            [:query :params]
                                                            {:reject-keys util/mutation-only-hook-keys})
          query-config (or (registry/get-query k)
                           (throw (ex-info (str "No query registered for key: " k) {:key k})))
          _ (when-not (util/infinite-query? query-config)
              (throw (ex-info (str "Query " k " is not an infinite query (missing :infinite config)") {:key k})))
          qid (util/query-id k params)
          query (get-in db [:re-frame.query/queries qid])
          now (util/now-ms)
          {:keys [initial-cursor]} (:infinite query-config)]
      (if (and (util/stale? query now)
               (not (:fetching? query)))
        (let [refreshing? (and (= :success (:status query))
                               (some? (:data query)))]
          (merge {:db (start-query-attempt db k params request-id
                                           {:status (if refreshing? :success :loading)
                                            :data (or (:data query) empty-infinite-data)
                                            :fetching? true
                                            :fetching-next? false
                                            :fetching-prev? false
                                            :stale? false})}
                 (build-infinite-fetch-effects query-config k params initial-cursor
                                               [:re-frame.query/infinite-page-success k params nil]
                                               request-id)))
        {:db db}))))

(rf/reg-event-fx
  :re-frame.query/fetch-next-page
  [inject-request-id]
  (fn [{:keys [db] :re-frame.query/keys [request-id]} [_ & args]]
    (let [{k :query params :params} (util/normalize-payload :re-frame.query/fetch-next-page
                                                            args
                                                            [:query :params]
                                                            {:reject-keys util/mutation-only-hook-keys})
          query-config (registry/get-query k)]
      (when-not query-config
        (throw (ex-info (str "No query registered for key: " k) {:key k})))
      (let [qid (util/query-id k params)
            {:keys [data fetching? fetching-next? fetching-prev?]}
            (get-in db [:re-frame.query/queries qid])
            {:keys [has-next? next-cursor]} data
            should-fetch-next? (and data has-next?
                                    (not fetching?)
                                    (not fetching-next?)
                                    (not fetching-prev?))]
        ;; No-op if: no data yet, no next cursor, or already fetching
        (if-not should-fetch-next?
          {:db db}
          (merge {:db (start-query-attempt db k params request-id
                                           {:fetching-next? true})}
                 (build-infinite-fetch-effects
                  query-config k params next-cursor
                  [:re-frame.query/infinite-page-success k params :append]
                  request-id)))))))

(rf/reg-event-fx
  :re-frame.query/fetch-previous-page
  [inject-request-id]
  (fn [{:keys [db] :re-frame.query/keys [request-id]} [_ & args]]
    (let [{k :query params :params} (util/normalize-payload :re-frame.query/fetch-previous-page
                                                            args
                                                            [:query :params]
                                                            {:reject-keys util/mutation-only-hook-keys})
          query-config (registry/get-query k)]
      (when-not query-config
        (throw (ex-info (str "No query registered for key: " k) {:key k})))
      (let [qid (util/query-id k params)
            query (get-in db [:re-frame.query/queries qid])
            data (:data query)]
       ;; No-op if: no data yet, no prev cursor, or already fetching
        (if (and data
                 (:has-prev? data)
                 (not (:fetching? query))
                 (not (:fetching-next? query))
                 (not (:fetching-prev? query)))
          (let [prev-cursor (:prev-cursor data)]
            (merge {:db (start-query-attempt db k params request-id
                                             {:fetching-prev? true})}
                   (build-infinite-fetch-effects
                    query-config k params prev-cursor
                    [:re-frame.query/infinite-page-success k params :prepend]
                    request-id)))
          {:db db})))))

(rf/reg-event-fx
  :re-frame.query/infinite-page-success
  ;; No inject-request-id here on purpose: a sequential re-fetch chain keeps the
  ;; id it was issued with, reusing it for every follow-up page (see below).
  (fn [{:keys [db]} [_ k params mode page-data :as event]]
    (let [qid (util/query-id k params)
          query-config (registry/get-query k)
          query (get-in db [:re-frame.query/queries qid])
          req-id (:request-id (util/request-control event))
          now (util/now-ms)
          tags-fn (or (:tags query-config) (constantly []))
          tags (set (tags-fn params))
          transform-fn (:transform-response query-config)
          transformed (cond-> page-data (fn? transform-fn) (transform-fn params))
          infinite-cfg (:infinite query-config)
          get-next-cursor (:get-next-cursor infinite-cfg)
          get-prev-cursor (:get-previous-cursor infinite-cfg)
          next-cursor (get-next-cursor transformed)
          prev-cursor (when get-prev-cursor (get-prev-cursor transformed))
          max-pages (:max-pages query-config)
          refetch-state (:refetch-state query)
          base-state {:status :success
                      :error nil
                      :fetching? false
                      :fetching-next? false
                      :fetching-prev? false
                      :fetched-at now
                      :stale? false
                      :tags tags
                      :stale-time-ms (:stale-time-ms query-config)
                      :cache-time-ms (or (:cache-time-ms query-config)
                                         gc/default-cache-time-ms)}
          make-data (fn [pages page-params & {:keys [next prev]}]
                      (cond-> {:pages pages
                               :page-params page-params
                               :has-next? (some? next)
                               :next-cursor next
                               :has-prev? (some? prev)}
                        ;; Only include prev-cursor when the query supports it
                        (some? get-prev-cursor) (assoc :prev-cursor prev)))]
      (cond
       ;; --- Superseded attempt ---
       ;; A newer request replaced this one. Drop the page before any branch
       ;; runs: it would otherwise scramble a concurrent chain's accumulator
       ;; or, once that chain cleared :refetch-state, fall through to the
       ;; initial-load branch and collapse :data to a single page.
        (not (util/current-attempt? query req-id))
        {:db db}

       ;; --- Sequential re-fetch mode ---
       ;; Pages accumulate in refetch-state, not in :data (atomic swap)
        (some? refetch-state)
        (let [acc-pages (conj (:pages refetch-state) transformed)
              acc-params (conj (:page-params refetch-state)
                               (:current-cursor refetch-state))
              pages-fetched (count acc-pages)
              target (:target-page-count refetch-state)
              done? (or (>= pages-fetched target)
                        (nil? next-cursor))
              [final-pages final-params] (apply-max-pages
                                          (vec acc-pages) (vec acc-params) max-pages)
              ;; Capture prev-cursor from the first refetched page
              first-page-prev (or (:first-page-prev-cursor refetch-state)
                                  (when (= 1 pages-fetched) prev-cursor))]
          (if done?
           ;; All pages re-fetched — atomic swap into :data
            {:db (update-in db [:re-frame.query/queries qid] merge
                            (assoc base-state
                                   :data (make-data final-pages final-params
                                                    :next next-cursor
                                                    :prev first-page-prev)
                                   :refetch-state nil))}
           ;; More pages needed — continue the chain
            (merge
             {:db (update-in db [:re-frame.query/queries qid] merge
                             {:refetch-state (assoc refetch-state
                                                    :pages acc-pages
                                                    :page-params acc-params
                                                    :current-cursor next-cursor
                                                    :first-page-prev-cursor first-page-prev)})}
             ;; Same req-id — the whole chain is one logical attempt, so it
             ;; must not invalidate itself between pages.
             (build-infinite-fetch-effects
              query-config k params next-cursor
              [:re-frame.query/infinite-page-success k params nil]
              req-id))))

       ;; --- Append mode (fetch-next-page) ---
        (= mode :append)
        (let [old-data (:data query)
              new-pages (conj (:pages old-data) transformed)
              new-params (conj (:page-params old-data)
                               (:next-cursor old-data))
              [final-pages final-params] (apply-max-pages
                                          (vec new-pages) (vec new-params) max-pages)]
          {:db (update-in db [:re-frame.query/queries qid] merge
                          (assoc base-state
                                 :data (make-data final-pages final-params
                                                  :next next-cursor
                                                  ;; Keep old prev-cursor (from first page)
                                                  :prev (:prev-cursor old-data))))})

       ;; --- Prepend mode (fetch-previous-page) ---
        (= mode :prepend)
        (let [old-data (:data query)
              new-pages (into [transformed] (:pages old-data))
              new-params (into [(:prev-cursor old-data)] (:page-params old-data))
              [final-pages final-params] (apply-max-pages
                                          (vec new-pages) (vec new-params) max-pages :backward)]
          {:db (update-in db [:re-frame.query/queries qid] merge
                          (assoc base-state
                                 :data (make-data final-pages final-params
                                                  ;; Keep old next-cursor (from last page)
                                                  :next (:next-cursor old-data)
                                                  :prev prev-cursor)))})

       ;; --- Initial first page load ---
        :else
        {:db (update-in db [:re-frame.query/queries qid] merge
                        (assoc base-state
                               :data (make-data [transformed]
                                                [(:initial-cursor infinite-cfg)]
                                                :next next-cursor
                                                :prev prev-cursor)))}))))

(rf/reg-event-db
  :re-frame.query/infinite-page-failure
  (fn [db [_ k params error :as event]]
    (let [qid (util/query-id k params)
          query (get-in db [:re-frame.query/queries qid])
          req-id (:request-id (util/request-control event))]
      (if-not (util/current-attempt? query req-id)
       ;; Superseded — a late failure must not abort the newer attempt.
        db
        (let [query-config (registry/get-query k)
              transform-fn (:transform-error query-config)]
         ;; On failure: set error, clear refetch-state, preserve old :data
          (update-in db [:re-frame.query/queries qid] merge
                     {:status :error
                      :error (cond-> error (fn? transform-fn) (transform-fn params))
                      :fetching? false
                      :fetching-next? false
                      :fetching-prev? false
                      :refetch-state nil}))))))

;; Modified refetch-query for infinite queries — starts sequential re-fetch
(rf/reg-event-fx
  :re-frame.query/refetch-infinite-query
  [inject-request-id]
  (fn [{:keys [db] :re-frame.query/keys [request-id]} [_ & args]]
    (let [{k :query params :params} (util/normalize-payload :re-frame.query/refetch-infinite-query args
                                                            [:query :params]
                                                            {:reject-keys util/mutation-only-hook-keys})
          query-config (or (registry/get-query k)
                           (throw (ex-info (str "No query registered for key: " k) {:key k})))
          qid (util/query-id k params)
          query (get-in db [:re-frame.query/queries qid])
          data (:data query)
          page-count (count (:pages data))
          {:keys [initial-cursor]} (:infinite query-config)]
      (if (pos? page-count)
       ;; Has pages — start sequential re-fetch. The new `:request-id` also
       ;; supersedes any in-flight append/prepend for this entry.
        (merge
         {:db (start-query-attempt db k params request-id
                                   {:fetching? true
                                    :stale? false
                                    :refetch-state {:target-page-count page-count
                                                    :pages []
                                                    :page-params []
                                                    :current-cursor initial-cursor
                                                    :first-page-prev-cursor nil}})}
         (build-infinite-fetch-effects
          query-config k params initial-cursor
          [:re-frame.query/infinite-page-success k params nil]
          request-id))
       ;; No pages yet — just fetch the first page
        (merge
         {:db (start-query-attempt db k params request-id
                                   {:status :loading
                                    :data empty-infinite-data
                                    :fetching? true
                                    :fetching-next? false
                                    :fetching-prev? false
                                    :stale? false})}
         (build-infinite-fetch-effects
          query-config k params initial-cursor
          [:re-frame.query/infinite-page-success k params nil]
          request-id))))))

;; ---------------------------------------------------------------------------
;; Active Tracking
;; ---------------------------------------------------------------------------

(rf/reg-event-fx
  :re-frame.query/mark-active
  (fn [{:keys [db]} [_ & args]]
    (let [{k :query params :params :as opts}
          (util/flatten-opts (util/normalize-payload :re-frame.query/mark-active
                                                     args
                                                     [:query :params :opts]
                                                     {:reject-keys util/mutation-only-hook-keys}))
          qid (util/query-id k params)
          query-config (registry/get-query k)
          interval-ms (or (:polling-interval-ms opts)
                          (:polling-interval-ms query-config))
          sub-id (or (:sub-id opts) :default)]
      (cond-> {:db (assoc-in db [:re-frame.query/queries qid :active?] true)
               :re-frame.query/cancel-gc {:query-id qid}}
        interval-ms
        (assoc :re-frame.query/start-poll {:query-id qid
                                           :sub-id sub-id
                                           :k k
                                           :params params
                                           :interval-ms interval-ms})))))

(rf/reg-event-fx
  :re-frame.query/mark-inactive
  (fn [{:keys [db]} [_ & args]]
    (let [{k :query params :params :as opts}
          (util/flatten-opts (util/normalize-payload :re-frame.query/mark-inactive
                                                     args
                                                     [:query :params :opts]
                                                     {:reject-keys util/mutation-only-hook-keys}))
          qid (util/query-id k params)]
      (if-let [query (get-in db [:re-frame.query/queries qid])]
        (let [cache-time (or (:cache-time-ms query) gc/default-cache-time-ms)
              sub-id (or (:sub-id opts) :default)]
          {:db (assoc-in db [:re-frame.query/queries qid :active?] false)
           :re-frame.query/schedule-gc {:query-id qid
                                        :cache-time-ms cache-time}
           :re-frame.query/stop-poll {:query-id qid
                                      :sub-id sub-id}})
        {}))))

;; ---------------------------------------------------------------------------
;; Garbage Collection
;; ---------------------------------------------------------------------------

(rf/reg-event-db
  :re-frame.query/remove-query
  (fn [db [_ qid]]
    (qdb/remove-query db qid)))

(rf/reg-event-db
  :re-frame.query/garbage-collect
  (fn [db [_ now]]
    (qdb/garbage-collect db now)))
