# Polling

Queries can automatically refetch on an interval. Polling is configured via `:polling-interval-ms`, either at the **query level** (default for all subscribers) or at the **subscription/event level** (per-caller override). When multiple subscribers have different intervals, the **lowest non-zero** interval wins.

## Query-level polling

Set a default polling interval when registering the query:

```clojure
(rfq/reg-query :stocks/prices
  {:query-fn            (fn [_] {:method :get :url "/api/stocks"})
   :polling-interval-ms 5000}) ;; every 5 seconds
```

### With effectful subscriptions

The `::rfq/query` subscription handles the full lifecycle — fetching, active tracking, polling start/stop — automatically. It reads `:polling-interval-ms` from the query config:

```clojure
;; Starts polling at 5s — no extra config needed
@(rf/subscribe [::rfq/query :stocks/prices {}])
```

### With events and passive subscriptions

For explicit lifecycle control (e.g. route-based navigation), use events for lifecycle and `::rfq/query-state` for reading. `mark-active` reads `:polling-interval-ms` from its opts map or falls back to the query config:

```clojure
;; On route enter — start fetching, mark active, and start polling
(rf/dispatch [::rfq/ensure-query :stocks/prices {}])
(rf/dispatch [::rfq/mark-active :stocks/prices {}]) ;; reads interval from query config

;; Or override the interval per-caller:
(rf/dispatch [::rfq/mark-active :stocks/prices {} {:polling-interval-ms 1000}])

;; In views — read with a passive sub (no side effects)
(let [{:keys [status data fetching?]}
      @(rf/subscribe [::rfq/query-state :stocks/prices {}])]
  ...)

;; On route leave — stops polling and schedules GC
(rf/dispatch [::rfq/mark-inactive :stocks/prices {}])
```

### Subscriber identity (`:sub-id`)

Each polling subscriber is tracked by a `:sub-id`. Effectful subscriptions automatically use a unique ID per component instance. For event-based lifecycle, a `:default` sub-id is used when none is provided — this works well for the common case of one caller per query.

If **multiple callers** manage the same query independently (e.g. a dashboard and a sidebar both polling the same data), pass explicit `:sub-id` values so each caller only removes its own subscriber:

```clojure
;; Dashboard route
(rf/dispatch [::rfq/mark-active :stocks/prices {} {:sub-id :dashboard}])
;; ...later
(rf/dispatch [::rfq/mark-inactive :stocks/prices {} {:sub-id :dashboard}])

;; Sidebar (different interval, independent lifecycle)
(rf/dispatch [::rfq/mark-active :stocks/prices {} {:sub-id :sidebar
                                                    :polling-interval-ms 1000}])
;; ...later — only removes the sidebar subscriber
(rf/dispatch [::rfq/mark-inactive :stocks/prices {} {:sub-id :sidebar}])
```

When multiple subscribers exist for the same query, the **lowest non-zero** interval wins — same as with effectful subscriptions.

## Per-subscription polling

Override or set the interval for a specific subscriber via the opts map:

```clojure
;; This component polls at 1s, regardless of the query-level default
@(rf/subscribe [::rfq/query :stocks/prices {} {:polling-interval-ms 1000}])
```

## Multiple subscribers → lowest interval wins

```clojure
;; Component A — polls at 5s (query-level default)
@(rf/subscribe [::rfq/query :stocks/prices {}])

;; Component B — polls at 1s (per-subscription override)
@(rf/subscribe [::rfq/query :stocks/prices {} {:polling-interval-ms 1000}])

;; Effective interval: 1s (the lowest non-zero)
;; When Component B unmounts → interval reverts to 5s
```

## In-flight deduplication (default)

By default, if a query is already fetching when a poll tick fires, the tick is **skipped**. This prevents stale-response races — if request T0 stalls past the next interval, T1 won't fire a duplicate request that could later overwrite fresher data.

```clojure
;; Default behavior — no config needed, ticks are skipped while fetching
(rfq/reg-query :metrics/live
  {:query-fn            (fn [_] {:method :get :url "/api/metrics"})
   :polling-interval-ms 2000})
```

To opt out and fire every tick regardless of in-flight status (matches TanStack Query behavior), set `:polling-mode :force`:

```clojure
(rfq/reg-query :metrics/live
  {:query-fn            (fn [_] {:method :get :url "/api/metrics"})
   :polling-interval-ms 2000
   :polling-mode        :force}) ;; fire even if prior request is in-flight
```

> **Note:** Manual `refetch-query` calls are always unconditional — `:polling-mode` only affects automatic poll ticks.

## Stopping polling

Polling stops automatically when:
- **Effectful subscriptions:** all subscribers with a polling interval unmount
- **Event-based lifecycle:** `::rfq/mark-inactive` is dispatched

No manual cleanup needed in either case.

## Limitations

Polling is currently only supported for standard queries (`::rfq/query`). [Infinite queries](infinite-queries.md) do not support automatic polling. If you have a use case for polling infinite queries, please [open an issue](https://github.com/shipclojure/re-frame-query/issues/new).
