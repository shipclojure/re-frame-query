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
      :transform-response   (fn [data params] -> data')  Optional.
                            Applied to raw success data before caching. Use to
                            unwrap nested responses, normalize into lookup maps, etc.
                            For infinite queries, applied per-page.
      :transform-error      (fn [error params] -> error')  Optional.
                            Applied to raw error data before storing.
      :infinite             Map with infinite query config. When present, the query
                            uses paginated accumulation instead of single-result caching.
                            Keys:
                              :initial-cursor  - cursor for the first page (e.g. nil, 0)
                              :get-next-cursor - (fn [page-response] -> cursor-or-nil)
                            Use with ::rfq/infinite-query subscription and
                            rfq/fetch-next-page.
      :max-pages            Optional integer. When set, only the most recent N pages
                            are kept (sliding window). Older pages are dropped.

  Returns the query key."
  registry/reg-query)

(def reg-mutation
  "Register a mutation definition.

  Arguments:
    k      - Keyword identifying the mutation (e.g. :todos/add)
    config - Map with:
      :mutation-fn        (fn [params] -> request-map)  REQUIRED
                          Returns a request description map. The library auto-injects
                          success/failure callbacks via the configured effect-fn.
      :effect-fn          Optional per-mutation effect adapter (overrides global)
      :invalidates        (fn [params] -> [[tag ...] ...]) tags to invalidate on success
      :transform-response (fn [data params] -> data')  Optional.
                          Applied to raw mutation success data before storing.
      :transform-error    (fn [error params] -> error')  Optional.
                          Applied to raw mutation error before storing.

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
;; Reset
;; ---------------------------------------------------------------------------

(defn reset-api-state!
  "Clear all query and mutation state, cancel all GC and polling timers.

  Dispatches `::rfq/reset-api-state` which removes `:re-frame.query/queries`
  and `:re-frame.query/mutations` from `app-db` and cancels all pending
  timers. All other keys in `app-db` are preserved.

  Common use cases:
    - Logout (wipe cached user data)
    - Account switch (start fresh)
    - Hard refresh of all server state

  Example:
    (defn on-logout []
      (rfq/reset-api-state!)
      (rf/dispatch [:app/navigate :login]))"
  []
  (rf/dispatch [:re-frame.query/reset-api-state]))

;; ---------------------------------------------------------------------------
;; Direct Cache Manipulation
;; ---------------------------------------------------------------------------

;; For inline db operations inside your own event handlers (optimistic
;; updates, cache seeding, manual GC), require re-frame.query.db directly:
;;
;;   (ns my-app.events
;;     (:require [re-frame.query.db :as rfq-db]))
;;
;;   (rfq-db/get-query-data db k params)
;;   (rfq-db/set-query-data db k params data)
;;   (rfq-db/get-query     db k params)

(defn set-query-data
  "Directly set the cached data for a query without fetching.

  Dispatches `::rfq/set-query-data` which replaces the `:data` field in the
  query cache entry and sets `:status` to `:success`. Creates the entry if
  it doesn't exist.

  Use cases:
    - Optimistic updates (patch cache before mutation completes)
    - Rollback (restore snapshot on mutation failure)
    - Seeding cache from another query's response
    - Manually populating cache from external data

  Example:
    ;; Optimistically mark a todo as done
    (rfq/set-query-data :todos/list {:user-id 42}
      (mapv #(if (= (:id %) 5) (assoc % :done true) %) old-todos))

    ;; Rollback to snapshot
    (rfq/set-query-data :todos/list {:user-id 42} snapshot)"
  [k params data]
  (rf/dispatch [:re-frame.query/set-query-data k params data]))

;; ---------------------------------------------------------------------------
;; Infinite Query API
;; ---------------------------------------------------------------------------

(defn fetch-next-page
  "Fetch the next page of an infinite query.

  Reads the current `next-cursor` from the cache and fetches the next page.
  No-op if there is no next page (`has-next?` is false) or a fetch is
  already in progress.

  Example:
    (rfq/fetch-next-page :feed/items {:category \"tech\"})"
  [k params]
  (rf/dispatch [:re-frame.query/fetch-next-page k params]))

(defn fetch-previous-page
  "Fetch the previous page of an infinite query.

  Reads the current `prev-cursor` from the cache and fetches the previous page,
  prepending it to the pages vector. Requires `:get-previous-cursor` in the
  query's `:infinite` config. No-op if there is no previous page (`has-prev?`
  is false) or a fetch is already in progress.

  When `:max-pages` is set, older pages are trimmed from the end (opposite
  of `fetch-next-page` which trims from the start).

  Example:
    (rfq/fetch-previous-page :feed/items {:category \"tech\"})"
  [k params]
  (rf/dispatch [:re-frame.query/fetch-previous-page k params]))

(defn infinite-query-data
  "Subscribe to just the `:data` field of an infinite query's passive state.

  Returns `{:pages [...] :page-params [...] :has-next? bool :has-prev? bool}`
  without triggering a fetch or any lifecycle side effects.

  Components subscribed here only re-render when the pages data changes —
  not when `:fetching-next?`, `:fetching-prev?`, or `:stale?` toggle.
  This avoids unnecessary re-renders during stale-while-revalidate background
  refetches.

  Requires the query lifecycle to be managed separately via
  `::rfq/ensure-infinite-query` and `::rfq/mark-active` / `::rfq/mark-inactive`,
  or by having another component subscribed to `::rfq/infinite-query`.

  Example:
    @(rf/subscribe [::rfq/infinite-query-data :feed/items {:user \"alex\"}])"
  [k params]
  (rf/subscribe [:re-frame.query/infinite-query-data k params]))

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

  Options (optional map):
    :clj->js? — When true (default), converts args via clj->js before logging.
                 Set to false when using Chrome custom formatters (e.g. cljs-devtools),
                 which render ClojureScript data structures natively.

  Examples:
    ;; Default — args converted to plain JS objects
    (when ^boolean goog.DEBUG (rfq/enable-debug-logging!))

    ;; With Chrome custom formatters — args logged as ClojureScript values
    (when ^boolean goog.DEBUG (rfq/enable-debug-logging! {:clj->js? false}))"
  ([] (enable-debug-logging! {}))
  ([{:keys [clj->js?] :or {clj->js? true}}]
   (rf/reg-global-interceptor
    (rf/->interceptor
     :id :re-frame.query/debug-log
     :before (fn [context]
               (let [[event-id & args] (get-in context [:coeffects :event])]
                 (when (and (keyword? event-id)
                            (= "re-frame.query" (namespace event-id)))
                   (let [label (str "📦 " event-id)
                         argv (vec args)]
                     #?(:cljs (if clj->js?
                                (js/console.log label (clj->js argv))
                                (js/console.log label argv))
                        :clj (println label argv)))))
               context)))
   nil))
