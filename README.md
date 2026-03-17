# re-frame-query

Declarative data fetching and caching for [re-frame](https://github.com/day8/re-frame), inspired by [TanStack Query](https://tanstack.com/query) and [RTK Query](https://redux-toolkit.js.org/rtk-query/overview).

## Features

- **Declarative queries & mutations** — describe *what* to fetch, the library handles *when* and *how*
- **Automatic callback wiring** — no manual `:on-success` / `:on-failure` plumbing
- **Tag-based cache invalidation** with automatic refetching of active queries
- **Per-query garbage collection** — inactive queries are cleaned up after `cache-time-ms` via per-query timers (same model as TanStack Query)
- **Polling** — automatic refetch intervals with per-subscriber or per-query config; multiple subscribers use the lowest non-zero interval
- **Smart status tracking** — distinguishes initial loading from background refetching
- **Transport-agnostic** — works with any re-frame effect (HTTP, GraphQL, WebSocket, etc.)
- **All state in re-frame DB** — predictable, inspectable, time-travel debuggable
- **Subscription-driven** — subscribing is all you need; fetching, caching, and cleanup are automatic

## Quick Start

### 1. Add dependency

```clojure
;; deps.edn
{:deps {io.github.shipclojure/re-frame-query {:git/sha "86a658d"}}}
```

### 2. Initialize the registry

Define your entire query/mutation configuration in one place using `rfq/init!`. Call this **once** at app startup:

```clojure
(ns my-app.queries
  (:require [re-frame.query :as rfq]))

(rfq/init!
  {:default-effect-fn
   (fn [request on-success on-failure]
     {:http (assoc request :on-success on-success :on-failure on-failure)})

   :queries
   {:todos/list
    {:query-fn      (fn [{:keys [user-id]}]
                      {:method :get
                       :url    (str "/api/users/" user-id "/todos")})
     :stale-time-ms 30000          ;; 30s — data is fresh for this long
     :cache-time-ms (* 5 60 1000)  ;; 5min — inactive cache is GC'd after this
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

That's it — no `:on-success` or `:on-failure` wiring. The library auto-injects the correct callbacks via your `default-effect-fn`.

The `default-effect-fn` receives three arguments:

| Argument | Description |
|---|---|
| `request` | The map returned by your `query-fn` or `mutation-fn` |
| `on-success` | A re-frame event vector — `conj` response data onto it and dispatch |
| `on-failure` | A re-frame event vector — `conj` error data onto it and dispatch |

> **Incremental API** — You can also register queries and mutations one at a time
> with `rfq/reg-query`, `rfq/reg-mutation`, and `rfq/set-default-effect-fn!`.
> These work both standalone and after an `init!` call (e.g. for lazy-loaded modules).

### 3. Use a query

Think of `(rf/subscribe [::rfq/query k params])` like a **`use-query` hook** —
subscribing is all you need. It triggers the fetch, caches the result, and
keeps it fresh. No imperative dispatch required.

```clojure
(ns my-app.views
  (:require [re-frame.core :as rf]
            [re-frame.query :as rfq]))

(defn todos-view []
  ;; This single subscribe call does everything:
  ;;  • fetches data if absent or stale
  ;;  • returns cached data instantly if fresh
  ;;  • refetches automatically when invalidated by a mutation
  ;;  • cleans up when the component unmounts
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

Just like React Query's `useQuery`, the subscription manages the full lifecycle:
1. **Fetches** data if absent or stale — no manual dispatch needed
2. **Marks the query as active** — so mutations know to refetch it
3. **Refetches** automatically when matching tags are invalidated
4. **Marks as inactive** on unmount — triggering the GC timer

### 4. Dispatch a mutation

```clojure
(rf/dispatch [::rfq/execute-mutation :todos/add {:user-id 42 :title "Ship it"}])
```

On success, mutations automatically invalidate matching tags — all active queries with those tags are refetched.

### 5. Manual invalidation

```clojure
(rf/dispatch [::rfq/invalidate-tags [[:todos :user 42]]])
```

## Status Tracking

The library distinguishes between **initial loading** and **background refetching** so your UI never flashes a loading spinner when stale data is available:

| Scenario | `:status` | `:fetching?` | `:data` |
|---|---|---|---|
| Initial load (no data) | `:loading` | `true` | `nil` |
| Background refetch (stale data) | `:success` | `true` | previous data |
| Idle, fresh data | `:success` | `false` | current data |
| Failed fetch | `:error` | `false` | previous data or `nil` |
| Retry after error | `:loading` | `true` | previous data or `nil` |

Use `:status` for **data state** (what to render) and `:fetching?` for **network state** (show a subtle spinner).

## Garbage Collection

Inactive queries (no subscribers) are automatically garbage-collected after their `cache-time-ms` expires. This uses **per-query timers**, following the same model as TanStack Query and RTK Query:

1. **Component unmounts** → query marked inactive → `setTimeout` starts with `cache-time-ms`
2. **Component remounts before timer fires** → timer is cancelled, query stays in cache
3. **Timer fires** → query removed from `app-db` (only if still inactive)

| Config | Default | Description |
|---|---|---|
| `:cache-time-ms` | `300000` (5 min) | Time before inactive query is GC'd |
| `:stale-time-ms` | none | Time before data is considered stale |

Timer handles are stored in a side-channel atom (not in `app-db`) to keep the re-frame store fully serializable.

## Polling

Queries can automatically refetch on an interval. Polling is configured via `:polling-interval-ms`, either at the **query level** (default for all subscribers) or at the **subscription level** (per-component override). When multiple subscribers have different intervals, the **lowest non-zero** interval wins.

### Query-level polling

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

### Per-subscription polling

Override or set the interval for a specific subscriber via the opts map:

```clojure
;; This component polls at 1s, regardless of the query-level default
@(rf/subscribe [::rfq/query :stocks/prices {} {:polling-interval-ms 1000}])
```

### Multiple subscribers → lowest interval wins

```clojure
;; Component A — polls at 5s (query-level default)
@(rf/subscribe [::rfq/query :stocks/prices {}])

;; Component B — polls at 1s (per-subscription override)
@(rf/subscribe [::rfq/query :stocks/prices {} {:polling-interval-ms 1000}])

;; Effective interval: 1s (the lowest non-zero)
;; When Component B unmounts → interval reverts to 5s
```

### Stopping polling

Polling stops automatically when all subscribers with a polling interval unmount. No manual cleanup needed.

## API Reference

### Setup

| Function | Description |
|---|---|
| `rfq/init!` | Initialize the full registry with a single config map (queries, mutations, default-effect-fn) |
| `rfq/set-default-effect-fn!` | Set the global effect adapter `(fn [request on-success on-failure] -> effects-map)` |

#### `init!` config keys

| Key | Description |
|---|---|
| `:default-effect-fn` | `(fn [request on-success on-failure] -> effects-map)` — global effect adapter |
| `:queries` | `{keyword -> query-config}` — map of query definitions (same keys as `reg-query`) |
| `:mutations` | `{keyword -> mutation-config}` — map of mutation definitions (same keys as `reg-mutation`) |

### Registration (incremental)

Use these to add queries/mutations one at a time, either standalone or after `init!`:

| Function | Description |
|---|---|
| `rfq/reg-query` | Register a single query definition |
| `rfq/reg-mutation` | Register a single mutation definition |

#### `reg-query` config keys

| Key | Required | Description |
|---|---|---|
| `:query-fn` | ✅ | `(fn [params] -> request-map)` — describes what to fetch |
| `:stale-time-ms` | | Milliseconds before data is considered stale |
| `:cache-time-ms` | | Milliseconds before inactive query is GC'd (default: 5 min) |
| `:tags` | | `(fn [params] -> [[tag ...] ...])` — for cache invalidation |
| `:effect-fn` | | Per-query effect adapter (overrides global) |
| `:polling-interval-ms` | | Default polling interval for all subscribers (ms). Multiple subscribers use the lowest non-zero interval. |

#### `reg-mutation` config keys

| Key | Required | Description |
|---|---|---|
| `:mutation-fn` | ✅ | `(fn [params] -> request-map)` — describes the mutation |
| `:invalidates` | | `(fn [params] -> [[tag ...] ...])` — tags to invalidate on success |
| `:effect-fn` | | Per-mutation effect adapter (overrides global) |

### Events

With `(:require [re-frame.query :as rfq])`, use `::rfq/` shorthand:

| Event | Description |
|---|---|
| `[::rfq/ensure-query k params]` | Fetch if stale/absent (called automatically by subscription) |
| `[::rfq/refetch-query k params]` | Force refetch regardless of staleness |
| `[::rfq/execute-mutation k params]` | Execute a mutation |
| `[::rfq/invalidate-tags tags]` | Mark matching queries stale & refetch active ones |
| `[::rfq/remove-query qid]` | Remove a specific query from cache (used internally by GC) |
| `[::rfq/garbage-collect]` | Bulk remove all expired inactive queries |

### Subscriptions

> **Only `::rfq/query` triggers a fetch.** The other query subscriptions are
> derived — they extract a single field from the query state but do **not**
> start a fetch or manage the query lifecycle. Always subscribe to
> `::rfq/query` first (or instead).

| Subscription | Triggers fetch? | Returns |
|---|---|---|
| `[::rfq/query k params]` | ✅ Yes | Full query state map |
| `[::rfq/query k params opts]` | ✅ Yes | Full query state map (with per-sub options, e.g. `{:polling-interval-ms 5000}`) |
| `[::rfq/query-data k params]` | ❌ No | Just the `:data` |
| `[::rfq/query-status k params]` | ❌ No | Just the `:status` (`:idle`, `:loading`, `:success`, `:error`) |
| `[::rfq/query-fetching? k params]` | ❌ No | Boolean — is a request in flight? |
| `[::rfq/query-error k params]` | ❌ No | Just the `:error` |
| `[::rfq/mutation k params]` | ❌ No | Mutation state map |
| `[::rfq/mutation-status k params]` | ❌ No | Just the mutation `:status` |

### Query State Shape

```clojure
{:status        :idle | :loading | :success | :error
 :data          <response data>
 :error         <error data>
 :fetching?     true | false
 :stale?        true | false
 :fetched-at    <ms timestamp>
 :tags          #{[:tag :tuple] ...}
 :active?       true | false
 :stale-time-ms <ms>
 :cache-time-ms <ms>}
```

## Where Data Lives in `app-db`

re-frame-query stores **all** its state inside your re-frame `app-db` under two
namespaced keys. This means query state is fully inspectable with
[re-frame-10x](https://github.com/day8/re-frame-10x), serializable, and
compatible with time-travel debugging.

```clojure
;; Your app-db will look like:
{;; ... your own app state ...
 :my-app/route   [:home]
 :my-app/user    {:id 42 :name "Alice"}

 ;; ┌─── re-frame-query state ─────────────────────────────────────────┐

 :re-frame.query/queries
 {[:todos/list {:user-id 42}]          ;; ← query-id = [key params]
  {:status     :success
   :data       [{:id 1 :title "Ship it"} {:id 2 :title "Write docs"}]
   :error      nil
   :fetching?  false
   :stale?     false
   :active?    true                     ;; ← a component is subscribed
   :fetched-at 1718900000000
   :tags       #{[:todos :user 42]}
   :stale-time-ms 30000
   :cache-time-ms 300000}

  [:todos/list {:user-id 7}]
  {:status :loading :data nil :fetching? true ,,,}}

 :re-frame.query/mutations
 {[:todos/add {:user-id 42 :title "New"}]
  {:status :success
   :data   {:id 3 :title "New"}
   :error  nil}}

 ;; └──────────────────────────────────────────────────────────────────┘
 }
```

### Key layout

| `app-db` key | Shape | Description |
|---|---|---|
| `:re-frame.query/queries` | `{[k params] → query-map}` | Cache of all fetched queries. Each entry is a query-id (`[key params]`) mapped to the query state shape above. |
| `:re-frame.query/mutations` | `{[k params] → mutation-map}` | Status of in-flight and completed mutations. Each entry has `:status`, `:data`, and `:error`. |

### What this means for your app

- **No conflicts** — the namespaced keys (`:re-frame.query/*`) won't collide with your own state.
- **Fully inspectable** — open re-frame-10x and browse `:re-frame.query/queries` to see every cached query, its status, data, and tags.
- **Serializable** — GC timer handles are stored in a separate side-channel atom (not in `app-db`), so your db remains serializable for time-travel and persistence.
- **You own the db** — re-frame-query never touches keys outside its namespace. You can safely `assoc`, `merge`, or `reset!` your own keys alongside it.

## How It Works

1. **Subscribing** to `[::rfq/query k params]` automatically fetches data (if absent or stale) and marks the query as **active**.
2. **`query-fn`** returns a request description map. The library wraps it with success/failure callbacks via the configured `effect-fn` and dispatches the resulting re-frame effect.
3. **On success**, the cache entry is updated with data, timestamps, and tags. On **failure**, the error is stored and the query is marked as stale (so it will retry on next subscription).
4. **Mutations** execute side-effects and on success, invalidate matching tags — all active queries with those tags are automatically refetched.
5. **Unsubscribing** (component unmount) marks the query **inactive** and starts a GC timer. If the component remounts before the timer fires, the timer is cancelled.
6. **Garbage collection** fires per-query — each inactive query has its own `setTimeout` based on its `cache-time-ms`. No global polling interval.

## Per-Query Effect Override

For queries that use a different transport (e.g., WebSocket instead of HTTP), provide a per-query `:effect-fn`:

```clojure
(rfq/reg-query :chat/messages
  {:query-fn  (fn [{:keys [room-id]}]
                {:channel (str "room:" room-id)
                 :event   "get-messages"})
   :effect-fn (fn [request on-success on-failure]
                {:ws-send (assoc request
                            :on-success on-success
                            :on-failure on-failure)})})
```

## Custom Success/Failure Callbacks

Need to run your own logic on success or failure? Extend the `on-success` / `on-failure` vectors in your `effect-fn`:

```clojure
;; Global — all queries/mutations dispatch ::my-app/on-success after the library handler
(rfq/set-default-effect-fn!
  (fn [request on-success on-failure]
    {:http (assoc request
             :on-success (into on-success [::my-app/on-success])
             :on-failure (into on-failure [::my-app/on-failure]))}))

;; Per-query — only this query dispatches a custom event
(rfq/reg-query :books/list
  {:query-fn  (fn [_] {:method :get :url "/api/books"})
   :effect-fn (fn [request on-success on-failure]
                {:http (assoc request
                         :on-success (into on-success [::books-loaded])
                         :on-failure on-failure)})})
```

Since `on-success` and `on-failure` are plain vectors, you have full control — append events, wrap them, or replace them entirely.

## Examples

Two full example apps are included in `examples/`:

| App | Framework | Port | Directory |
|---|---|---|---|
| Reagent | Reagent + re-frame | 8710 | `examples/reagent-app/` |
| UIx | UIx v2 + re-frame | 8720 | `examples/uix-app/` |

Both use [MSW (Mock Service Worker)](https://mswjs.io/) to intercept fetch requests with an in-memory book CRUD API.

### Running an example

```bash
cd examples/reagent-app   # or examples/uix-app

# Install dependencies (once)
pnpm install

# Bundle MSW mock handlers
pnpm run mocks

# Start shadow-cljs dev server
pnpm exec shadow-cljs watch demo

# Or do both in one step:
pnpm run dev
```

Then open <http://localhost:8710> (Reagent) or <http://localhost:8720> (UIx).

## Development

```bash
# Install deps
clj -A:dev -P

# Run tests (shadow-cljs node-test)
pnpm exec shadow-cljs compile test
```

## License

MIT
