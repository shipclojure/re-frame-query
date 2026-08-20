(ns re-frame.query.db
  "Pure functions for reading and transforming the re-frame-query section of
   app-db. Import this namespace to perform cache operations inline in your
   own reg-event-db/reg-event-fx handlers — no extra dispatches required.

   Example:
     (ns my-app.events
       (:require [re-frame.query.db :as rfq-db]))

     (rf/reg-event-fx ::optimistic-update
       (fn [{:keys [db]} [_ {:keys [id changes]}]]
         (let [current (rfq-db/get-query-data db :items/table {})
               new-data (update-item current id changes)]
           {:db (-> db
                    (assoc-in [:my-app :snapshot] current)
                    (rfq-db/set-query-data :items/table {} new-data))})))"
  (:require
   [re-frame.query.util :as util]))

;; ---------------------------------------------------------------------------
;; Reads
;; ---------------------------------------------------------------------------

(defn get-query
  "Return the full cache entry map for a query, or nil if not cached."
  ([db qid]
   (get-in db [:re-frame.query/queries qid]))
  ([db k params]
   (get-in db [:re-frame.query/queries (util/query-id k params)])))

(defn get-query-data
  "Return just the :data field of a cached query, or nil if not cached."
  [db k params]
  (get-in db [:re-frame.query/queries (util/query-id k params) :data]))

;; ---------------------------------------------------------------------------
;; Writes
;; ---------------------------------------------------------------------------

(defn set-query-data
  "Return an updated db with `data` written into the query cache entry.

  Sets :status to :success, clears :error, marks the entry as **stale** —
  the data has not been verified by the server, so the next `ensure-query`
  (or any active subscriber) will background-refetch. Preserves :fetching?
  if a request is already in flight; otherwise sets it to false.
  Creates the cache entry if it does not exist.

  Common uses:
    - Placeholder data (e.g. seed `:todo/get` from `:todo/list` on route
      enter so the user sees something instantly while the real fetch runs).
    - Optimistic updates — the data is treated as provisional until either
      a mutation's `:invalidates` triggers a refetch or the next
      `ensure-query` resolves.

  This function does **not** supersede an in-flight request: a fetch that
  started earlier can still land afterwards and overwrite what you wrote
  (e.g. a pre-mutation response clobbering an optimistic patch). Pair it
  with `::rfq/cancel-query` (or `rfq/cancel-query`) when that matters:

    (rfq/cancel-query :items/table {:page 1})
    (rfq-db/set-query-data db :items/table {:page 1} patched)

  Example:
    (rfq-db/set-query-data db :items/table {:page 1} new-items)"
  [db k params data]
  (let [qid (util/query-id k params)
        now (util/now-ms)
        in-flight? (boolean (get-in db [:re-frame.query/queries qid :fetching?]))]
    (update-in db [:re-frame.query/queries qid]
               util/merge-with-default
               {:status :success
                :data data
                :error nil
                :fetching? in-flight?
                :stale? true
                :fetched-at now})))

;; ---------------------------------------------------------------------------
;; Garbage collection
;; ---------------------------------------------------------------------------

(defn remove-query
  "Return an updated db with `qid` evicted, but only if it is inactive.
   If the query became active again before this runs, it is left untouched."
  [db qid]
  (let [query (get-in db [:re-frame.query/queries qid])]
    (if (and query (not (:active? query)))
      (update db :re-frame.query/queries dissoc qid)
      db)))

(defn compute-cancelled-status
  "Return the :status a query should have after cancellation.

   If the query never received a successful response (`:loading`), revert to
   `:idle`.  Otherwise keep the last finished status (`:success` or `:error`)."
  [query-data]
  (let [status (:status query-data)]
    (if (= :loading status)
      :idle
      status)))

(defn cancel-query
  "Cancel any pending request attempts by setting `request-id` on the query data.
  Works by setting a new request-id on the query data, which will trigger the
  success/failure event to conclude the in-flight request is not the
  current-attempt, dropping all the response data.

  Params:
  - `db`: re-frame db snapshot
  - `k`: query registry id
  - `params`: params used for the issued query
  - `query-config`: config of the query. Gotten from query registry.
  - `request-id`: the new request id to be set

  N.B: The new request-id id must be different than the currently set one from
  the query-data in `db` otherwise the query cannot be canceled."
  [db k params query-config request-id]
  (let [qid (util/query-id k params)
        query-data (get-in db [:re-frame.query/queries qid])
        in-flight? (or (:fetching? query-data)
                       (:fetching-next? query-data)
                       (:fetching-prev? query-data))
        infinite? (util/infinite-query? query-config)]
    (when (and request-id
               (= (:request-id query-data) request-id))
      (throw (ex-info "Same request-id already exists for query. Cannot be used to cancel"
                      {:request-id request-id
                       :k k
                       :params params})))
    (if query-data
      ;; Claims a fresh id without starting a request: the in-flight attempt no
      ;; longer matches, so its response is dropped on arrival. No-op when the
      ;; query is not cached — cancelling must not create an entry.
      (update-in db [:re-frame.query/queries qid]
                 util/merge-with-default
                 (cond-> {:request-id request-id
                          :status (compute-cancelled-status query-data)
                          :fetching? false}
                   in-flight? (assoc :stale? true)
                   infinite? (assoc :fetching-next? false
                                    :fetching-prev? false
                                    :refetch-state nil)))
      db)))

(defn garbage-collect
  "Return an updated db with all expired inactive queries evicted.
  A query is expired when it has been inactive for longer than its
  :cache-time-ms. `now` defaults to the current time in milliseconds."
  ([db] (garbage-collect db (util/now-ms)))
  ([db now]
   (update db :re-frame.query/queries
           (fn [queries]
             (reduce-kv
              (fn [acc qid q]
                (let [cache-ms (:cache-time-ms q)
                      fetched (:fetched-at q 0)
                      expired? (and cache-ms
                                    fetched
                                    (> (- now fetched) cache-ms))]
                  (if (and expired? (not (:active? q)))
                    acc
                    (assoc acc qid q))))
              {}
              queries)))))
