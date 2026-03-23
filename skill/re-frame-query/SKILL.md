---
name: re-frame-query
description: "Develop with re-frame-query: declarative data fetching and caching for re-frame (ClojureScript). Use when writing, reviewing, or debugging code that uses re-frame-query (rfq) for queries, mutations, cache invalidation, polling, infinite scroll, or optimistic updates. Triggers include mentions of re-frame-query, rfq, ::rfq/query, ::rfq/execute-mutation, reg-query, reg-mutation, query-fn, stale-time-ms, invalidate-tags, fetch-next-page, or any re-frame.query namespaced keywords."
---

# re-frame-query Development

## Core Concepts

re-frame-query is a TanStack Query / RTK Query inspired library for re-frame. All state lives in `app-db` under `:re-frame.query/queries` and `:re-frame.query/mutations`.

**Namespace:** `[re-frame.query :as rfq]`

**Key pattern:** register a query once with `rfq/reg-query`, then subscribe with `[::rfq/query k params]` — subscribing triggers fetch, caching, refetch, and GC automatically.

## Setup Pattern

```clojure
;; 1. Configure effect adapter (once at startup)
(rfq/set-default-effect-fn!
  (fn [request on-success on-failure]
    {:http (assoc request :on-success on-success :on-failure on-failure)}))

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
;; Pure read — use with manual ensure-query/mark-active/mark-inactive
@(rf/subscribe [::rfq/query-state :todos/list {:user-id 42}])
```

Use passive subs when managing lifecycle via navigation hooks:

```clojure
;; Route enter
(rf/dispatch [::rfq/ensure-query :todos/list {:user-id 42}])
(rf/dispatch [::rfq/mark-active :todos/list {:user-id 42}])
;; View uses ::rfq/query-state (pure read)
;; Route leave
(rf/dispatch [::rfq/mark-inactive :todos/list {:user-id 42}])
```

## Infinite Queries

```clojure
(rfq/reg-query :feed/items
  {:query-fn      (fn [{:keys [cursor]}]
                    {:method :get :url (str "/api/feed?cursor=" (or cursor 0))})
   :infinite      {:initial-cursor 0
                   :get-next-cursor (fn [resp] (:next_cursor resp))}
   :tags          (constantly [[:feed]])})

;; Subscribe (effectful)
(let [{:keys [data fetching-next?]} @(rf/subscribe [::rfq/infinite-query :feed/items {}])
      {:keys [pages has-next?]} data]
  ;; pages = [page1-resp page2-resp ...]
  ...)

;; Load more
(rf/dispatch [::rfq/fetch-next-page :feed/items {}])
```

On invalidation, all loaded pages are re-fetched sequentially with fresh cursors. Old data preserved until complete (atomic swap).

## Optimistic Updates

Use mutation lifecycle hooks + `set-query-data`:

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
- **Tags drive invalidation** — mutations declare `:invalidates`, queries declare `:tags`, matching triggers refetch
- **GC timers are side-channel** — stored in atoms outside app-db to keep it serializable
- **Transport-agnostic** — the `effect-fn` bridges to any re-frame effect (`:http`, `:ws-send`, etc.)
- **`transform-response`** — `(fn [data params] -> data')` applied before caching; for infinite queries applied per-page

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
