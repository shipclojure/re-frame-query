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
  [db k params]
  (get-in db [:re-frame.query/queries (util/query-id k params)]))

(defn get-query-data
  "Return just the :data field of a cached query, or nil if not cached."
  [db k params]
  (get-in db [:re-frame.query/queries (util/query-id k params) :data]))

;; ---------------------------------------------------------------------------
;; Writes
;; ---------------------------------------------------------------------------

(defn set-query-data
  "Return an updated db with `data` written into the query cache entry.

  Sets :status to :success, clears :error and :fetching?, marks the entry
  as fresh. Creates the cache entry if it does not exist.

  Use for optimistic updates or seeding the cache without fetching:
    (rfq-db/set-query-data db :items/table {:page 1} new-items)"
  [db k params data]
  (let [qid (util/query-id k params)
        now (util/now-ms)]
    (update-in db [:re-frame.query/queries qid]
               util/merge-with-default
               {:status :success
                :data data
                :error nil
                :fetching? false
                :stale? false
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
