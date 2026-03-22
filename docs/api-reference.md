# API Reference

## Setup

| Function | Description |
|---|---|
| `rfq/init!` | Initialize the full registry with a single config map (queries, mutations, default-effect-fn) |
| `rfq/set-default-effect-fn!` | Set the global effect adapter `(fn [request on-success on-failure] -> effects-map)` |

### `init!` config keys

| Key | Description |
|---|---|
| `:default-effect-fn` | `(fn [request on-success on-failure] -> effects-map)` — global effect adapter |
| `:queries` | `{keyword -> query-config}` — map of query definitions (same keys as `reg-query`) |
| `:mutations` | `{keyword -> mutation-config}` — map of mutation definitions (same keys as `reg-mutation`) |

## Registration (incremental)

Use these to add queries/mutations one at a time, either standalone or after `init!`:

| Function | Description |
|---|---|
| `rfq/reg-query` | Register a single query definition |
| `rfq/reg-mutation` | Register a single mutation definition |
| `rfq/prefetch` | `(rfq/prefetch k params)` — pre-populate cache (convenience for dispatching `::rfq/ensure-query`) |
| `rfq/reset-api-state!` | Clear all query/mutation state and cancel all timers (for logout, account switch, etc.) |

### `reg-query` config keys

| Key | Required | Description |
|---|---|---|
| `:query-fn` | ✅ | `(fn [params] -> request-map)` — describes what to fetch |
| `:stale-time-ms` | | Milliseconds before data is considered stale |
| `:cache-time-ms` | | Milliseconds before inactive query is GC'd (default: 5 min) |
| `:tags` | | `(fn [params] -> [[tag ...] ...])` — for cache invalidation |
| `:effect-fn` | | Per-query effect adapter (overrides global) |
| `:polling-interval-ms` | | Default polling interval for all subscribers (ms). Multiple subscribers use the lowest non-zero interval. |
| `:transform-response` | | `(fn [data params] -> data')` — transform raw success data before caching. For infinite queries, applied per-page. |
| `:transform-error` | | `(fn [error params] -> error')` — transform raw error before storing |
| `:infinite` | | Map with `{:initial-cursor val :get-next-cursor fn}` — enables infinite query mode. See [Infinite Queries](infinite-queries.md). |
| `:max-pages` | | Integer — sliding window cap for infinite queries. Oldest pages are dropped when exceeded. |

### `reg-mutation` config keys

| Key | Required | Description |
|---|---|---|
| `:mutation-fn` | ✅ | `(fn [params] -> request-map)` — describes the mutation |
| `:invalidates` | | `(fn [params] -> [[tag ...] ...])` — tags to invalidate on success |
| `:effect-fn` | | Per-mutation effect adapter (overrides global) |
| `:transform-response` | | `(fn [data params] -> data')` — transform raw success data before storing |
| `:transform-error` | | `(fn [error params] -> error')` — transform raw error before storing |

## Events

With `(:require [re-frame.query :as rfq])`, use `::rfq/` shorthand:

| Event | Description |
|---|---|
| `[::rfq/ensure-query k params]` | Fetch if stale/absent (called automatically by subscription; can also used for prefetching) |
| `[::rfq/refetch-query k params]` | Force refetch regardless of staleness |
| `[::rfq/execute-mutation k params]` | Execute a mutation |
| `[::rfq/execute-mutation k params opts]` | Execute with [lifecycle hooks](mutation-hooks.md) |
| `[::rfq/set-query-data k params data]` | Directly set cached query data (for optimistic updates, rollback) |
| `[::rfq/invalidate-tags tags]` | Mark matching queries stale & refetch active ones |
| `[::rfq/remove-query qid]` | Remove a specific query from cache (used internally by GC) |
| `[::rfq/garbage-collect]` | Bulk remove all expired inactive queries |
| `[::rfq/reset-api-state]` | Clear all queries, mutations, and cancel all GC/polling timers |
| `[::rfq/reset-mutation k params]` | Clear a mutation's state back to idle |
| `[::rfq/fetch-next-page k params]` | Fetch and append the next page of an [infinite query](infinite-queries.md) |

## Subscriptions

> **Only `::rfq/query` triggers a fetch.** The other query subscriptions are
> derived — they extract a single field from the query state but do **not**
> start a fetch or manage the query lifecycle. Always subscribe to
> `::rfq/query` first (or instead).

| Subscription | Triggers fetch? | Returns |
|---|---|---|
| `[::rfq/query k params]` | ✅ Yes | Full query state map |
| `[::rfq/query k params opts]` | ✅ Yes | Full query state map (opts: `{:polling-interval-ms 5000, :skip? false}`) |
| `[::rfq/query-data k params]` | ❌ No | Just the `:data` |
| `[::rfq/query-status k params]` | ❌ No | Just the `:status` (`:idle`, `:loading`, `:success`, `:error`) |
| `[::rfq/query-fetching? k params]` | ❌ No | Boolean — is a request in flight? |
| `[::rfq/query-error k params]` | ❌ No | Just the `:error` |
| `[::rfq/infinite-query k params]` | ✅ Yes | Full [infinite query](infinite-queries.md) state (pages, cursors, has-next?) |
| `[::rfq/mutation k params]` | ❌ No | Mutation state map |
| `[::rfq/mutation-status k params]` | ❌ No | Just the mutation `:status` |

## Query State Shape

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
