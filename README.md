# re-frame-query

Declarative data fetching and caching for [re-frame](https://github.com/day8/re-frame), inspired by [TanStack Query](https://tanstack.com/query) and [RTK Query](https://redux-toolkit.js.org/rtk-query/overview).

## Features

- **Declarative queries & mutations** with automatic caching
- **Tag-based cache invalidation** with dependent refetching
- **Transport-agnostic** — works with any re-frame effect (HTTP, GraphQL, WebSocket, etc.)
- **All state in re-frame DB** — predictable, inspectable, time-travel debuggable
- **Subscription-based** access to query state (status, data, error, staleness)
- **Garbage collection** for expired, inactive queries

## Quick Start

### 1. Add dependency

```clojure
;; deps.edn
{:deps {io.github.ovistoica/re-frame-query {:git/tag "v0.1.0" :git/sha "..."}}}
```

### 2. Register a query

```clojure
(ns my-app.queries
  (:require
   [rfq.core :as rfq]
   [ajax.core :as ajax]))

(rfq/reg-query :todos/list
  {:query-fn (fn [{:keys [user-id]}]
               {:http-xhrio {:method          :get
                              :uri             (str "/api/users/" user-id "/todos")
                              :response-format (ajax/json-response-format {:keywords? true})
                              :on-success      [:rfq/query-success :todos/list {:user-id user-id}]
                              :on-failure      [:rfq/query-failure :todos/list {:user-id user-id}]}})
   :stale-time-ms 30000          ;; 30 seconds
   :cache-time-ms (* 5 60 1000)  ;; 5 minutes
   :tags (fn [{:keys [user-id]}]
           [[:todos :user user-id]])})
```

### 3. Subscribe (fetching is automatic)

```clojure
(ns my-app.views
  (:require
   [re-frame.core :as rf]))

;; Just subscribe — the library automatically fetches, tracks active state,
;; and refetches when invalidated. No manual dispatching needed.
(defn todos-view []
  (let [{:keys [status data error]} @(rf/subscribe [:rfq/query :todos/list {:user-id 42}])]
    (case status
      :loading [:div "Loading..."]
      :error   [:div "Error: " (pr-str error)]
      :success [:ul (for [todo data]
                      ^{:key (:id todo)}
                      [:li (:title todo)])]
      [:div "Idle"])))
```

### 4. Register a mutation

```clojure
(rfq/reg-mutation :todos/add
  {:mutation-fn (fn [{:keys [user-id title]}]
                  {:http-xhrio {:method          :post
                                :uri             (str "/api/users/" user-id "/todos")
                                :params          {:title title}
                                :format          (ajax/json-request-format)
                                :response-format (ajax/json-response-format {:keywords? true})
                                :on-success      [:rfq/mutation-success :todos/add {:user-id user-id}]
                                :on-failure      [:rfq/mutation-failure :todos/add {:user-id user-id}]}})
   :invalidates (fn [{:keys [user-id]}]
                  [[:todos :user user-id]])})

;; Dispatch
(rf/dispatch [:rfq/execute-mutation :todos/add {:user-id 42 :title "Ship it"}])
```

### 5. Manual invalidation

```clojure
(rf/dispatch [:rfq/invalidate-tags [[:todos :user 42]]])
```

## API Reference

### Registration

| Function | Description |
|---|---|
| `rfq/reg-query` | Register a query definition |
| `rfq/reg-mutation` | Register a mutation definition |

### Events

| Event | Description |
|---|---|
| `[:rfq/ensure-query k params]` | Fetch if stale/absent (called automatically by subscription) |
| `[:rfq/refetch-query k params]` | Force refetch |
| `[:rfq/execute-mutation k params]` | Execute a mutation |
| `[:rfq/invalidate-tags tags]` | Mark matching queries stale & refetch active |
| `[:rfq/garbage-collect now]` | Remove expired inactive queries |

### Subscriptions

| Subscription | Returns |
|---|---|
| `[:rfq/query k params]` | Full query state map |
| `[:rfq/query-data k params]` | Just the `:data` |
| `[:rfq/query-status k params]` | Just the `:status` |
| `[:rfq/query-fetching? k params]` | Boolean — is it fetching? |
| `[:rfq/query-error k params]` | Just the `:error` |
| `[:rfq/mutation k params]` | Mutation state map |
| `[:rfq/mutation-status k params]` | Just the mutation `:status` |

### Query State Shape

```clojure
{:status    :idle | :loading | :success | :error
 :data      <response data>
 :error     <error data>
 :fetching? true | false
 :stale?    true | false
 :fetched-at <ms timestamp>
 :tags      #{[:tag :tuple] ...}
 :active?   true | false}
```

## How It Works

1. **Subscribing** to `[:rfq/query k params]` automatically fetches the data (if absent or stale) and marks the query as **active**. No manual dispatching needed.
2. **Success/failure callbacks** (`:rfq/query-success` / `:rfq/query-failure`) update the cache in the re-frame DB.
3. **Mutations** execute side-effects and on success, dispatch `:rfq/invalidate-tags` to mark related queries stale.
4. **Invalidation** marks matching queries (by tag intersection) as stale and **automatically refetches active ones** (those with live subscriptions).
5. **Unsubscribing** (component unmount) marks the query **inactive** via Reagent's `on-dispose` lifecycle — no manual cleanup needed.
6. **Garbage collection** runs periodically to prune expired, inactive queries.

## Examples

Two full example apps are included in `examples/`:

| App | Framework | Port | Directory |
|---|---|---|---|
| Reagent | Reagent + re-frame | 8710 | `examples/reagent-app/` |
| UIx | UIx v2 + `uix.re-frame/use-subscribe` | 8720 | `examples/uix-app/` |

Both examples use [MSW (Mock Service Worker)](https://mswjs.io/) to intercept
`fetch` requests with an in-memory book CRUD API — network traffic is visible
in the browser DevTools Network tab.

### Running an example

```bash
cd examples/reagent-app   # or examples/uix-app

# Install npm dependencies (once)
npm install

# Bundle MSW mock handlers (generates resources/public/mocks-bundle.js)
npm run mocks

# Start shadow-cljs dev server
npx shadow-cljs watch demo

# Or do both in one step:
npm run dev
```

Then open <http://localhost:8710> (Reagent) or <http://localhost:8720> (UIx).

## Development

```bash
# Install deps
clj -A:dev -P

# Run tests (shadow-cljs node-test)
npx shadow-cljs compile test
```

## License

MIT
