(ns re-frame.query.util
  "Internal utility functions for re-frame-query.")

(def default-query
  {:status :idle
   :data nil
   :error nil
   :fetching? false
   :stale? true
   :active? false
   :tags #{}})

(defn merge-with-default
  [& maps]
  (apply merge (into [default-query] maps)))

(defn now-ms
  "Returns current time in milliseconds since epoch."
  []
  #?(:clj (System/currentTimeMillis)
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
   - query is in an error state
   - stale-time-ms has elapsed since last fetch"
  [query now]
  (boolean
   (or (nil? query)
       (:stale? query)
       (= :error (:status query))
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

(defn infinite-query?
  "Returns true if the query config has an :infinite key."
  [query-config]
  (boolean (:infinite query-config)))

(defn parse-result-event
  "Parses one of the four rfq query result events into a map, hiding the
   positional event-vector shape from callers (interceptors, telemetry, etc.).

   Recognized events and the maps they produce:
     [:re-frame.query/query-success         k params data]
       => {:event-id ... :k ... :params ... :data data}

     [:re-frame.query/query-failure         k params error]
       => {:event-id ... :k ... :params ... :error error}

     [:re-frame.query/infinite-page-success k params mode page-data]
       => {:event-id ... :k ... :params ... :mode mode :data page-data}
          (mode is nil | :append | :prepend)

     [:re-frame.query/infinite-page-failure k params error]
       => {:event-id ... :k ... :params ... :error error}

   Returns nil for any other event vector — callers can branch on truthiness."
  [event]
  (let [[event-id k params a b] event
        rfq-result-event? (#{:re-frame.query/query-success
                             :re-frame.query/query-failure
                             :re-frame.query/infinite-page-success
                             :re-frame.query/infinite-page-failure}
                           event-id)]
    (when rfq-result-event?
      (cond-> {:event-id event-id :k k :params params}
        (= event-id :re-frame.query/query-success)
        (assoc :data a)

        (= event-id :re-frame.query/query-failure)
        (assoc :error a)

        (= event-id :re-frame.query/infinite-page-success)
        (assoc :mode a :data b)

        (= event-id :re-frame.query/infinite-page-failure)
        (assoc :error a)))))
