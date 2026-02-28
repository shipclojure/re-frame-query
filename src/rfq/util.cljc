(ns rfq.util
  "Internal utility functions for re-frame-query.")

(defn now-ms
  "Returns current time in milliseconds since epoch."
  []
  #?(:clj  (System/currentTimeMillis)
     :cljs (.now js/Date)))

(defn query-id
  "Creates a canonical cache key from a query/mutation name and params."
  [k params]
  [k (or params {})])

(defn stale?
  "Determines if a query entry needs refetching.
   Returns true when:
   - query does not exist
   - query is explicitly marked stale
   - stale-time-ms has elapsed since last fetch"
  [query now]
  (boolean
   (or (nil? query)
       (:stale? query)
       (let [stale-time (:stale-time-ms query)
             fetched-at (:fetched-at query)]
         (and stale-time
              fetched-at
              (> (- now fetched-at) stale-time))))))

(defn tag-match?
  "Returns true if any of the `invalidation-tags` appear in `query-tags`."
  [query-tags invalidation-tags]
  (boolean
   (and (seq query-tags)
        (seq invalidation-tags)
        (some (set query-tags) invalidation-tags))))
