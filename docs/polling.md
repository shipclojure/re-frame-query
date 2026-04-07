# Polling

Queries can automatically refetch on an interval. Polling is configured via `:polling-interval-ms`, either at the **query level** (default for all subscribers) or at the **subscription level** (per-component override). When multiple subscribers have different intervals, the **lowest non-zero** interval wins.

## Query-level polling

Set a default polling interval when registering the query:

```clojure
(rfq/reg-query :stocks/prices
  {:query-fn            (fn [_] {:method :get :url "/api/stocks"})
   :polling-interval-ms 5000}) ;; every 5 seconds
```

### With effectful subscriptions

The `::rfq/query` subscription handles the full lifecycle — fetching, active tracking, polling start/stop — automatically:

```clojure
;; Starts polling at 5s — no extra config needed
@(rf/subscribe [::rfq/query :stocks/prices {}])
```

### With passive subscriptions and manual lifecycle

If you prefer explicit control (e.g. route-based lifecycle), use `::rfq/query-state` for reading and manage lifecycle with events. The effectful `::rfq/query` subscription can still be used solely for its polling side effects:

```clojure
;; On route enter — start fetching and mark active
(rf/dispatch [::rfq/ensure-query :stocks/prices {}])
(rf/dispatch [::rfq/mark-active :stocks/prices {}])

;; In a view — read with a passive sub (no side effects)
(let [{:keys [status data fetching?]}
      @(rf/subscribe [::rfq/query-state :stocks/prices {}])]
  ...)

;; To also get polling, subscribe with the effectful sub somewhere
;; (e.g. in the root component for this route):
@(rf/subscribe [::rfq/query :stocks/prices {}])

;; On route leave
(rf/dispatch [::rfq/mark-inactive :stocks/prices {}])
```

> **Tip:** Polling lifecycle (start/stop) is tied to the `::rfq/query` subscription mount/unmount. If you only use passive subscriptions with manual events, polling won't start automatically. Mount one effectful `::rfq/query` subscription per polling query to drive the timer.

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

Polling stops automatically when all subscribers with a polling interval unmount. No manual cleanup needed.

## Limitations

Polling is currently only supported for standard queries (`::rfq/query`). [Infinite queries](infinite-queries.md) do not support automatic polling. If you have a use case for polling infinite queries, please [open an issue](https://github.com/shipclojure/re-frame-query/issues/new).
