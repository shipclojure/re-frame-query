# re-frame-query

[![Clojars Project](https://img.shields.io/clojars/v/com.shipclojure/re-frame-query.svg)](https://clojars.org/com.shipclojure/re-frame-query)

Declarative data fetching and caching for [re-frame](https://github.com/day8/re-frame), inspired by [TanStack Query](https://tanstack.com/query) and [RTK Query](https://redux-toolkit.js.org/rtk-query/overview).

## Features

- **Declarative queries & mutations** — describe *what* to fetch, the library handles *when* and *how*
- **Automatic callback wiring** — no manual `:on-success` / `:on-failure` plumbing
- **Tag-based cache invalidation** with automatic refetching of active queries
- **Per-query garbage collection** — inactive queries are cleaned up after `cache-time-ms` via per-query timers (same model as TanStack Query)
- **Polling** — automatic refetch intervals with per-subscriber or per-query config; multiple subscribers use the lowest non-zero interval
- **Conditional fetching** — skip queries with `:skip? true` until a condition is met (e.g., dependent queries)
- **Prefetching** — pre-populate the cache before a component subscribes (on hover, route transition, etc.)
- **Smart status tracking** — distinguishes initial loading from background refetching
- **Transport-agnostic** — works with any re-frame effect (HTTP, GraphQL, WebSocket, etc.)
- **All state in re-frame DB** — predictable, inspectable, time-travel debuggable
- **Infinite queries** — cursor-based pagination with automatic sequential re-fetch on invalidation, sliding window support
- **Mutation lifecycle hooks** — `:on-start`, `:on-success`, `:on-failure` for optimistic updates and rollback
- **Subscription-driven** — subscribing is all you need; fetching, caching, and cleanup are automatic

## Quick Start

### 1. Add dependency

```clojure
;; deps.edn
{:deps {com.shipclojure/re-frame-query {:mvn/version "0.7.0"}}}

;; Leiningen/Boot
[com.shipclojure/re-frame-query "0.7.0"]
```

### 2. Initialize the registry

```clojure
(ns my-app.queries
  (:require [re-frame.query :as rfq]))

(rfq/init!
  {:default-effect-fn
   (fn [request on-success on-failure]
     {:http-xhrio (assoc request :on-success on-success :on-failure on-failure)})

   :queries
   {:todos/list
    {:query-fn      (fn [{:keys [user-id]}]
                      {:method :get
                       :url    (str "/api/users/" user-id "/todos")})
     :stale-time-ms 30000
     :cache-time-ms (* 5 60 1000)
     :tags          (fn [{:keys [user-id]}]
                      [[:todos :user user-id]])}}

   :mutations
   {:todos/add
    {:mutation-fn  (fn [{:keys [user-id title]}]
                     {:method :post
                      :url    (str "/api/users/" user-id "/todos")
                      :body   {:title title}})
     :invalidates  (fn [{:keys [user-id]}]
                     [[:todos :user user-id]])}}})
```

No `:on-success` / `:on-failure` wiring needed — the library auto-injects callbacks via your `default-effect-fn`.

> **Incremental API** — You can also register queries and mutations one at a time
> with `rfq/reg-query`, `rfq/reg-mutation`, and `rfq/set-default-effect-fn!`.

### 3. Use a query

Think of `(rf/subscribe [::rfq/query k params])` like a **`use-query` hook** —
subscribing is all you need. It triggers the fetch, caches the result, and
keeps it fresh.

```clojure
(defn todos-view []
  (let [{:keys [status data error fetching?]}
        @(rf/subscribe [::rfq/query :todos/list {:user-id 42}])]
    (case status
      :loading [:div "Loading..."]
      :error   [:div "Error: " (pr-str error)]
      :success [:div
                [:ul (for [todo data]
                       ^{:key (:id todo)}
                       [:li (:title todo)])]
                (when fetching? [:span "Refreshing..."])]
      [:div "Idle"])))
```

#### How does the subscription work?

Unlike a typical re-frame subscription that just reads from `app-db`, `::rfq/query` is built with `reg-sub-raw` — it uses Reagent's `Reaction` lifecycle to manage the query automatically:

- **On subscribe:** fetches data if absent/stale, marks query active, starts polling if configured
- **While subscribed:** returns query state reactively; multiple components share a single cache entry
- **On dispose:** marks query inactive, starts GC timer, stops polling

> **A note on re-frame philosophy:** re-frame [recommends](https://day8.github.io/re-frame/FAQs/LoadOnMount/) that subscriptions be pure reads. Our `::rfq/query` dispatches events as a side effect of subscribing — a deliberate trade-off mirroring React Query's `useQuery`. If you prefer explicit control, dispatch `::rfq/ensure-query` and `::rfq/mark-active` yourself and use the passive subscriptions `::rfq/query-state` and `::rfq/infinite-query-state` — they return the exact same data shape with no side effects.

### 4. Dispatch a mutation

```clojure
(rf/dispatch [::rfq/execute-mutation :todos/add {:user-id 42 :title "Ship it"}])
```

On success, mutations automatically invalidate matching tags — all active queries with those tags are refetched.

### 5. Manual invalidation

```clojure
(rf/dispatch [::rfq/invalidate-tags [[:todos :user 42]]])
```

## Documentation

| Guide | Description |
|---|---|
| [API Reference](docs/api-reference.md) | Events, subscriptions, config keys, query state shape |
| [Status Tracking](docs/status-tracking.md) | How `:status` and `:fetching?` distinguish loading states |
| [Garbage Collection](docs/garbage-collection.md) | Per-query timer-based cache eviction |
| [Polling](docs/polling.md) | Query-level, per-subscription, and multi-subscriber polling |
| [Conditional Fetching](docs/conditional-fetching.md) | `:skip?` for dependent queries |
| [Prefetching](docs/prefetching.md) | Pre-populate cache on hover or route transition |
| [Where Data Lives](docs/app-db.md) | `app-db` layout, inspectability, serialization |
| [Effect Overrides](docs/effect-overrides.md) | Per-query transport, custom callbacks |
| [Lifecycle Hooks](docs/lifecycle-hooks.md) | Mutation hooks, optimistic updates, request cancellation, query observability via interceptors |
| [Infinite Queries](docs/infinite-queries.md) | Cursor-based pagination, sequential re-fetch, sliding window |

## How It Works

1. **Subscribing** to `[::rfq/query k params]` fetches data (if absent/stale) and marks the query **active**
2. **`query-fn`** returns a request map; the library wraps it with callbacks via `effect-fn`
3. **On success**, the cache updates with data, timestamps, and tags; on **failure**, the error is stored
4. **Mutations** invalidate matching tags — active queries with those tags are automatically refetched
5. **Unsubscribing** marks the query **inactive** and starts a per-query GC timer
6. **GC fires** per-query via `setTimeout` based on `cache-time-ms`

## Examples

Two full example apps with 8 tabs each (Basic CRUD, Polling, Dependent Queries, Prefetching, Mutation Lifecycle, WebSocket, Optimistic Updates, Infinite Scroll):

| App | Framework | Port | Directory |
|---|---|---|---|
| Reagent | Reagent + re-frame | 8710 | `examples/reagent-app/` |
| UIx | UIx v2 + re-frame | 8720 | `examples/uix-app/` |

Both use [MSW (Mock Service Worker)](https://mswjs.io/) to intercept fetch requests with an in-memory API so you can see the queries in the network tab.

```bash
cd examples/reagent-app   # or examples/uix-app
pnpm install && pnpm run mocks && pnpm exec shadow-cljs watch demo
```

## Development

```bash
# Run unit tests
bb test:unit

# Run e2e tests (both example apps)
bb test:e2e

# Format code
bb fmt

# Check formatting
bb fmt:check
```

## License

MIT
