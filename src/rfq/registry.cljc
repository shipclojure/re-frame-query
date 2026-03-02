(ns rfq.registry
  "Internal registry for query and mutation definitions.
   Stores configuration maps keyed by query/mutation name.")

(defonce ^:private registry
  (atom {:queries {}
         :mutations {}}))

(defn reg-query
  "Register a query definition.

  `k`      - Namespaced keyword identifying the query (e.g. :todos/list)
  `config` - Map with keys:
    :query-fn      (fn [params] -> effects-map) — REQUIRED
    :cache-time-ms — ms before an inactive query is garbage-collected (default: 300000 / 5 min)
    :stale-time-ms — ms before a query is considered stale
    :tags          (fn [params] -> [[tag-tuple] ...]) — for invalidation matching"
  [k config]
  {:pre [(keyword? k) (fn? (:query-fn config))]}
  (swap! registry assoc-in [:queries k] config)
  k)

(defn reg-mutation
  "Register a mutation definition.

  `k`      - Namespaced keyword identifying the mutation (e.g. :todos/add)
  `config` - Map with keys:
    :mutation-fn (fn [params] -> effects-map) — REQUIRED
    :invalidates (fn [params] -> [[tag-tuple] ...]) — tags to invalidate on success"
  [k config]
  {:pre [(keyword? k) (fn? (:mutation-fn config))]}
  (swap! registry assoc-in [:mutations k] config)
  k)

(defn get-query
  "Retrieve a registered query config by key. Returns nil if not found."
  [k]
  (get-in @registry [:queries k]))

(defn get-mutation
  "Retrieve a registered mutation config by key. Returns nil if not found."
  [k]
  (get-in @registry [:mutations k]))

(defn clear-registry!
  "Reset the registry. Primarily for testing."
  []
  (reset! registry {:queries {} :mutations {}})
  nil)
