---
name: re-frame-query
description: "Develop with re-frame-query: declarative data fetching and caching for re-frame (ClojureScript). Use when writing, reviewing, or debugging code that uses re-frame-query (rfq) for queries, mutations, cache invalidation, polling, infinite scroll, or optimistic updates. Triggers include mentions of re-frame-query, rfq, ::rfq/query, ::rfq/execute-mutation, reg-query, reg-mutation, query-fn, stale-time-ms, invalidate-tags, fetch-next-page, or any re-frame.query namespaced keywords."
---

# re-frame-query Development

## Core Concepts

re-frame-query is a TanStack Query / RTK Query inspired library for re-frame. All state lives in `app-db` under `:re-frame.query/queries` and `:re-frame.query/mutations`.

**Namespaces:**
- `[re-frame.query :as rfq]` — public API (events, subs, registration)
- `[re-frame.query.db :as rfq-db]` — pure `db → db` functions for inline cache operations

**Key pattern:** register a query once with `rfq/reg-query`, then subscribe with `[::rfq/query k params]` — subscribing triggers fetch, caching, refetch, and GC automatically.

## Setup Pattern

```clojure
;; 1. Configure effect adapter (once at startup)
(rfq/set-default-effect-fn!
  (fn [request on-success on-failure]
    {:http-xhrio (assoc request :on-success on-success :on-failure on-failure)}))

;; 2. Register queries
(rfq/reg-query :todos/list
  {:query-fn      (fn [{:keys [user-id]}]
                    {:method :get :url (str "/api/users/" user-id "/todos")})
   :stale-time-ms 30000
   :tags          (fn [{:keys [user-id]}] [[:todos :user user-id]])})

;; 3. Register mutations
(rfq/reg-mutation :todos/add
  {:mutation-fn (fn [{:keys [user-id title]}]
                  {:method :post :url (str "/api/users/" user-id "/todos") :body {:title title}})
   :invalidates (fn [{:keys [user-id]}] [[:todos :user user-id]])})
```

Alternative: use `rfq/init!` for one-shot declarative registration of all queries, mutations, and the effect adapter.

## Subscription Patterns

### Effectful (auto-fetching, for views)

```clojure
;; Triggers fetch + marks active + starts polling + handles GC
(let [{:keys [status data error fetching?]}
      @(rf/subscribe [::rfq/query :todos/list {:user-id 42}])]
  (case status
    :loading [:div "Loading..."]
    :success [:div (for [t data] ^{:key (:id t)} [:li (:title t)])]
    :error   [:div "Error: " (pr-str error)]))
```

### Passive (no side effects, for manual lifecycle)

```clojure
;; Pure read — prefer these when managing lifecycle via navigation hooks
@(rf/subscribe [::rfq/query-state :todos/list {:user-id 42}])
@(rf/subscribe [::rfq/infinite-query-state :feed/items {}])
```

Use passive subs when managing lifecycle explicitly (e.g. route hooks):

```clojure
;; Route enter
(rf/dispatch [::rfq/ensure-query :todos/list {:user-id 42}])
(rf/dispatch [::rfq/mark-active :todos/list {:user-id 42}])
;; View uses ::rfq/query-state (pure read, same shape)
;; Route leave
(rf/dispatch [::rfq/mark-inactive :todos/list {:user-id 42}])
```

## Polling

Polling can be started either via subscription opts or via `mark-active`:

```clojure
;; Via subscription opts
@(rf/subscribe [::rfq/query :stats/live {} {:polling-interval-ms 5000}])

;; Via mark-active (event-based lifecycle, no effectful sub needed)
(rf/dispatch [::rfq/mark-active :stats/live {} {:polling-interval-ms 5000 :sub-id :my-widget}])
(rf/dispatch [::rfq/mark-inactive :stats/live {} {:sub-id :my-widget}])
```

Polling skips a tick when a request is already in-flight (prevents stale-response races). Set `:polling-mode :force` on the query config to restore unconditional polling. Infinite queries do not support polling.

## Inline Cache Operations (`re-frame.query.db`)

Use `rfq-db` to read/write the query cache inside your own event handlers without dispatching extra events:

```clojure
(ns my-app.events
  (:require [re-frame.query.db :as rfq-db]))

(rf/reg-event-fx ::optimistic-toggle
  (fn [{:keys [db]} [_ {:keys [id]}]]
    (let [current (rfq-db/get-query-data db :todos/list {:user-id 1})
          patched  (map #(if (= (:id %) id) (update % :done not) %) current)]
      {:db (-> db
               (assoc-in [:app/snapshot] current)
               (rfq-db/set-query-data :todos/list {:user-id 1} patched))})))
```

Functions: `get-query`, `get-query-data`, `set-query-data`, `remove-query`, `garbage-collect`.

## Infinite Queries

```clojure
(rfq/reg-query :feed/items
  {:query-fn      (fn [{:keys [cursor]}]
                    {:method :get :url (str "/api/feed?cursor=" (or cursor 0))})
   :infinite      {:initial-cursor    0
                   :get-next-cursor     (fn [resp] (:next_cursor resp))
                   :get-previous-cursor (fn [resp] (:prev_cursor resp))}  ;; optional
   :tags          (constantly [[:feed]])})

;; Subscribe
(let [{:keys [data fetching-next?]} @(rf/subscribe [::rfq/infinite-query :feed/items {}])
      {:keys [pages has-next? has-prev?]} data]
  ...)

;; Paginate
(rf/dispatch [::rfq/fetch-next-page :feed/items {}])
(rf/dispatch [::rfq/fetch-previous-page :feed/items {}])  ;; requires :get-previous-cursor
```

On invalidation, all loaded pages are re-fetched sequentially with fresh cursors. Old data preserved until complete (atomic swap). Use `::rfq/ensure-infinite-query` (not `::rfq/ensure-query`) for infinite queries.

## Optimistic Updates

Use mutation lifecycle hooks + `rfq-db` inside your event handlers:

```clojure
(rf/dispatch [::rfq/execute-mutation :todos/toggle {:id 5 :done true}
              {:on-start   [[:todos/optimistic-patch]]   ;; receives params
               :on-success [[:todos/clear-snapshot]]     ;; receives params, data
               :on-failure [[:todos/rollback]]}])        ;; receives params, error
```

Hooks are vectors of event vectors. Each hook event gets args conj'd onto it.

## Key Design Rules

- **Events are pure data** — never pass functions as event arguments
- **All state in app-db** — queries under `:re-frame.query/queries`, mutations under `:re-frame.query/mutations`
- **Cache key = `[k params]`** — e.g. `[:todos/list {:user-id 42}]`
- **Tags drive invalidation** — mutations declare `:invalidates`, queries declare `:tags`, only matching queries are refetched
- **GC timers are side-channel** — stored in atoms outside app-db to keep it serializable
- **Transport-agnostic** — the `effect-fn` bridges to any re-frame effect (`:http-xhrio`, `:ws-send`, etc.)
- **`ensure-query` is for regular queries only** — use `ensure-infinite-query` for infinite queries

## Status Tracking

| Scenario | `:status` | `:fetching?` |
|---|---|---|
| Initial load | `:loading` | `true` |
| Background refetch | `:success` | `true` |
| Fresh data | `:success` | `false` |
| Failed | `:error` | `false` |

Use `:status` for what to render, `:fetching?` for showing a spinner overlay.

## API Reference

Read [references/api-quick-ref.md](references/api-quick-ref.md) for the complete event, subscription, and config key reference.

## Example Apps

Two full example apps in [`examples/`](https://github.com/shipclojure/re-frame-query/tree/main/examples) with 8 tabs each. Read [references/examples.md](references/examples.md) for a file-by-file guide to what each demonstrates:

- [`examples/reagent-app/`](https://github.com/shipclojure/re-frame-query/tree/main/examples/reagent-app) — Reagent + re-frame (port 8710). Incremental `reg-query`/`reg-mutation`.
- [`examples/uix-app/`](https://github.com/shipclojure/re-frame-query/tree/main/examples/uix-app) — UIx v2 + re-frame (port 8720). Declarative `init!`.

Key files to read for specific patterns: effect adapters (`http_fx.cljs`, `ws_fx.cljs`), all query registrations (`queries.cljs`), and per-feature views (`views/*.cljs`).
