# Polling

Queries can automatically refetch on an interval. Polling is configured via `:polling-interval-ms`, either at the **query level** (default for all subscribers) or at the **subscription level** (per-component override). When multiple subscribers have different intervals, the **lowest non-zero** interval wins.

## Query-level polling

Set a default polling interval when registering the query:

```clojure
(rfq/reg-query :stocks/prices
  {:query-fn            (fn [_] {:method :get :url "/api/stocks"})
   :polling-interval-ms 5000}) ;; every 5 seconds
```

Every subscription to `:stocks/prices` will poll at 5s automatically:

```clojure
;; Starts polling at 5s — no extra config needed
@(rf/subscribe [::rfq/query :stocks/prices {}])
```

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

## Stopping polling

Polling stops automatically when all subscribers with a polling interval unmount. No manual cleanup needed.
