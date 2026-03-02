(ns rfq.registry
  "Internal registry for query and mutation definitions.
   Stores configuration maps keyed by query/mutation name.")

(defonce ^:private registry
  (atom {:queries {}
         :mutations {}
         :config  {}}))

(defn reg-query
  "Register a query definition.

  `k`      - Namespaced keyword identifying the query (e.g. :todos/list)
  `config` - Map with keys:
    :query-fn      (fn [params] -> request-map) — REQUIRED
                   Returns a request description. When an effect-fn is configured
                   (see `set-default-effect-fn!`), the library auto-injects
                   success/failure callbacks. Without effect-fn, must return a
                   full re-frame effects map with manual callbacks (legacy).
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
    :mutation-fn (fn [params] -> request-map) — REQUIRED
                 Returns a request description. When an effect-fn is configured
                 (see `set-default-effect-fn!`), the library auto-injects
                 success/failure callbacks. Without effect-fn, must return a
                 full re-frame effects map with manual callbacks (legacy).
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

(defn set-default-effect-fn!
  "Set the global effect adapter function.

  `effect-fn` is called as `(effect-fn request on-success on-failure)` where:
    - `request`    — the map returned by `query-fn` or `mutation-fn`
    - `on-success` — a re-frame event vector to dispatch on success (response data
                     should be `conj`'d onto it)
    - `on-failure` — a re-frame event vector to dispatch on failure (error data
                     should be `conj`'d onto it)

  Must return a re-frame effects map.

  Example for an `:http` effect:

    (rfq/set-default-effect-fn!
      (fn [request on-success on-failure]
        {:http (assoc request
                 :on-success on-success
                 :on-failure on-failure)}))"
  [effect-fn]
  {:pre [(fn? effect-fn)]}
  (swap! registry assoc-in [:config :effect-fn] effect-fn)
  nil)

(defn get-default-effect-fn
  "Retrieve the global effect adapter function. Returns nil if not set."
  []
  (get-in @registry [:config :effect-fn]))

(defn clear-registry!
  "Reset the registry. Primarily for testing."
  []
  (reset! registry {:queries {} :mutations {} :config {}})
  nil)
