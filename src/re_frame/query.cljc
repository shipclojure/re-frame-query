(ns re-frame.query
  "re-frame-query: Declarative data fetching and caching for re-frame.

  Public API namespace — require this to get started:

    (ns my-app.core
      (:require [re-frame.query :as rfq]))

    ;; Configure the effect adapter (once, at app startup)
    (rfq/set-default-effect-fn!
      (fn [request on-success on-failure]
        {:http (assoc request :on-success on-success :on-failure on-failure)}))

    ;; Register a query — no manual callback wiring needed
    (rfq/reg-query :todos/list
      {:query-fn (fn [{:keys [user-id]}]
                   {:method :get
                    :url (str \"/api/users/\" user-id \"/todos\")})
       :stale-time-ms 30000
       :tags (fn [{:keys [user-id]}] [[:todos :user user-id]])})

    ;; Fetch and subscribe
    (rf/dispatch [::rfq/ensure-query :todos/list {:user-id 42}])
    @(rf/subscribe [::rfq/query :todos/list {:user-id 42}])"
  (:require
   ;; Side-effecting requires — registers events, subs, and fx on load
   [re-frame.core :as rf]
   [re-frame.query.events]
   [re-frame.query.gc]
   [re-frame.query.polling]
   ;; Functional requires
   [re-frame.query.registry :as registry]
   [re-frame.query.subs]))

;; ---------------------------------------------------------------------------
;; Public Registration API
;; ---------------------------------------------------------------------------

(def set-default-effect-fn!
  "Set the global effect adapter function.

  Called once at app startup to tell re-frame-query how to execute HTTP
  requests. The function receives three arguments:
    - `request`    — the map returned by `query-fn` or `mutation-fn`
    - `on-success` — re-frame event vector to dispatch on success
    - `on-failure` — re-frame event vector to dispatch on failure

  Must return a re-frame effects map.

  Example:
    (rfq/set-default-effect-fn!
      (fn [request on-success on-failure]
        {:http (assoc request :on-success on-success :on-failure on-failure)}))"
  registry/set-default-effect-fn!)

(def reg-query
  "Register a query definition.

  Arguments:
    k      - Keyword identifying the query (e.g. :todos/list)
    config - Map with:
      :query-fn             (fn [params] -> request-map)  REQUIRED
                            Returns a request description map. The library auto-injects
                            success/failure callbacks via the configured effect-fn.
      :effect-fn            Optional per-query effect adapter (overrides global)
      :cache-time-ms        Milliseconds before inactive query is GC'd (default: 300000 / 5 min)
      :stale-time-ms        Milliseconds before query auto-becomes stale
      :tags                 (fn [params] -> [[tag ...] ...]) for invalidation
      :polling-interval-ms  Default polling interval for this query. When set,
                            every subscription to this query will poll at this
                            interval unless overridden per-subscription. Multiple
                            subscribers with different intervals use the lowest.

  Returns the query key."
  registry/reg-query)

(def reg-mutation
  "Register a mutation definition.

  Arguments:
    k      - Keyword identifying the mutation (e.g. :todos/add)
    config - Map with:
      :mutation-fn  (fn [params] -> request-map)  REQUIRED
                    Returns a request description map. The library auto-injects
                    success/failure callbacks via the configured effect-fn.
      :effect-fn    Optional per-mutation effect adapter (overrides global)
      :invalidates  (fn [params] -> [[tag ...] ...]) tags to invalidate on success

  Returns the mutation key."
  registry/reg-mutation)

(def init!
  "Initialize the registry with a complete configuration map.

  Replaces the entire registry in one shot, giving you a single declarative
  place to define all queries, mutations, and the default effect adapter.

  `config` is a map with optional keys:

    :default-effect-fn — (fn [request on-success on-failure] -> effects-map)
                         Global effect adapter.

    :queries           — {keyword -> query-config}
                         Map of query definitions (same keys as `reg-query`).

    :mutations         — {keyword -> mutation-config}
                         Map of mutation definitions (same keys as `reg-mutation`).

  Example:
    (rfq/init!
      {:default-effect-fn (fn [request on-success on-failure]
                            {:http (assoc request
                                    :on-success on-success
                                    :on-failure on-failure)})
       :queries
       {:books/list {:query-fn      (fn [{:keys [page]}]
                                      {:url (str \"/api/books?page=\" page)})
                     :stale-time-ms 30000
                     :tags          (fn [_] [[:books]])}}
       :mutations
       {:books/create {:mutation-fn (fn [{:keys [title]}]
                                      {:url \"/api/books\" :method :post})
                       :invalidates (fn [_] [[:books]])}}})"
  registry/init!)

(def clear-registry!
  "Reset all query and mutation registrations. For testing only."
  registry/clear-registry!)

;; ---------------------------------------------------------------------------
;; Prefetching
;; ---------------------------------------------------------------------------

(defn prefetch
  "Pre-populate the cache for a query before a component subscribes.

  Dispatches `ensure-query` which fetches if the query is absent or stale,
  respecting stale-time and in-flight deduplication. Does NOT mark the query
  as active — the data simply sits in the cache until a subscription picks
  it up.

  Common use cases:
    - Prefetch on hover (e.g. next page, link target)
    - Prefetch on route transition
    - Prefetch from an event handler

  Example:
    ;; On mouse-enter for a link
    (rfq/prefetch :book/detail {:id 42})

    ;; Later, when the component mounts, it finds cached data:
    @(rf/subscribe [::rfq/query :book/detail {:id 42}])"
  [k params]
  (rf/dispatch [:re-frame.query/ensure-query k params]))

;; ---------------------------------------------------------------------------
;; Debug Logging
;; ---------------------------------------------------------------------------

(defn enable-debug-logging!
  "Install a global interceptor that logs all :re-frame.query/* events to the
  browser console. Call once at app startup (dev only).

  Example:
    (when ^boolean goog.DEBUG (rfq/enable-debug-logging!))"
  []
  (rf/reg-global-interceptor
    (rf/->interceptor
      :id    :re-frame.query/debug-log
      :before (fn [context]
                (let [[event-id & args] (get-in context [:coeffects :event])]
                  (when (and (keyword? event-id)
                             (= "re-frame.query" (namespace event-id)))
                    (let [label (str "📦 " event-id)]
                      #?(:cljs (js/console.log label (clj->js (vec args)))
                         :clj  (println label (vec args))))))
                context)))
  nil)
