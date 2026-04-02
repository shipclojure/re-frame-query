(ns re-frame.query.registry
  "Internal registry for query and mutation definitions.
   Stores configuration maps keyed by query/mutation name.")

(defonce ^:private registry
  (atom {:queries {}
         :mutations {}
         :config {}}))

(defn reg-query
  "Register a query definition.

  `k`      - Namespaced keyword identifying the query (e.g. :todos/list)
  `config` - Map with keys:
    :query-fn           (fn [params] -> request-map) — REQUIRED
                        Returns a request description. When an effect-fn is configured
                        (see `set-default-effect-fn!`), the library auto-injects
                        success/failure callbacks. Without effect-fn, must return a
                        full re-frame effects map with manual callbacks (legacy).
    :cache-time-ms      — ms before an inactive query is garbage-collected (default: 300000 / 5 min)
    :stale-time-ms      — ms before a query is considered stale
    :tags               (fn [params] -> [[tag-tuple] ...]) — for invalidation matching
    :transform-response (fn [data params] -> data') — optional, applied to raw success
                        data before caching. Use to unwrap, reshape, or normalize
                        server responses.
    :transform-error    (fn [error params] -> error') — optional, applied to raw error
                        before storing. Use to normalize error shapes."
  [k config]
  {:pre [(keyword? k) (fn? (:query-fn config))]}
  (swap! registry assoc-in [:queries k] config)
  k)

(defn reg-mutation
  "Register a mutation definition.

  `k`      - Namespaced keyword identifying the mutation (e.g. :todos/add)
  `config` - Map with keys:
    :mutation-fn        (fn [params] -> request-map) — REQUIRED
                        Returns a request description. When an effect-fn is configured
                        (see `set-default-effect-fn!`), the library auto-injects
                        success/failure callbacks. Without effect-fn, must return a
                        full re-frame effects map with manual callbacks (legacy).
    :invalidates        (fn [params] -> [[tag-tuple] ...]) — tags to invalidate on success
    :transform-response (fn [data params] -> data') — optional, applied to raw success
                        data before storing.
    :transform-error    (fn [error params] -> error') — optional, applied to raw error
                        before storing."
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

(defn- validate-queries! [queries]
  (doseq [[k config] queries]
    (when-not (keyword? k)
      (throw (ex-info "Query key must be a keyword" {:key k})))
    (when-not (fn? (:query-fn config))
      (throw (ex-info (str "Query " k " must have a :query-fn function") {:key k})))))

(defn- validate-mutations! [mutations]
  (doseq [[k config] mutations]
    (when-not (keyword? k)
      (throw (ex-info "Mutation key must be a keyword" {:key k})))
    (when-not (fn? (:mutation-fn config))
      (throw (ex-info (str "Mutation " k " must have a :mutation-fn function") {:key k})))))

(defn init!
  "Initialize the registry with a complete configuration map.

  Replaces the entire registry in one shot, giving you a single declarative
  place to define all queries, mutations, and the default effect adapter.

  `config` is a map with optional keys:

    :default-effect-fn — (fn [request on-success on-failure] -> effects-map)
                         Global effect adapter called for every query and mutation
                         unless overridden per-query. See `set-default-effect-fn!`.

    :queries           — {keyword -> query-config}
                         Map of query definitions. Each config supports the same
                         keys as `reg-query`: :query-fn, :cache-time-ms,
                         :stale-time-ms, :tags, :effect-fn.

    :mutations         — {keyword -> mutation-config}
                         Map of mutation definitions. Each config supports the same
                         keys as `reg-mutation`: :mutation-fn, :invalidates, :effect-fn.

  Example:

    (rfq/init!
      {:default-effect-fn (fn [request on-success on-failure]
                            {:http (assoc request
                                    :on-success on-success
                                    :on-failure on-failure)})
       :queries
       {:books/list   {:query-fn      (fn [{:keys [page]}]
                                        {:url (str \"/api/books?page=\" page)})
                       :stale-time-ms 30000
                       :tags          (fn [_] [[:books]])}
        :book/detail  {:query-fn (fn [{:keys [id]}]
                                   {:url (str \"/api/books/\" id)})
                       :tags     (fn [{:keys [id]}] [[:books] [:book id]])}}
       :mutations
       {:books/create {:mutation-fn (fn [{:keys [title]}]
                                      {:url \"/api/books\" :method :post :body {:title title}})
                       :invalidates (fn [_] [[:books]])}}})"
  [config]
  {:pre [(map? config)]}
  (let [queries (or (:queries config) {})
        mutations (or (:mutations config) {})
        effect-fn (:default-effect-fn config)]
    (validate-queries! queries)
    (validate-mutations! mutations)
    (when (and effect-fn (not (fn? effect-fn)))
      (throw (ex-info ":default-effect-fn must be a function" {:value effect-fn})))
    (reset! registry
            {:queries queries
             :mutations mutations
             :config (if effect-fn {:effect-fn effect-fn} {})})
    nil))

(defn clear-registry!
  "Reset the registry. Primarily for testing."
  []
  (reset! registry {:queries {} :mutations {} :config {}})
  nil)
