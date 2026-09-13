# API Reference

## Payload forms

Every public rfq event and subscription takes a **single map payload**. Queries are identified by `:query`, mutations by `:mutation`; everything else (`:params`, `:data`, `:tags`, `:skip?`, `:polling-interval-ms`, `:sub-id`, mutation hooks) is a named key on the same map:

```clojure
[::rfq/ensure-query     {:query :books/list :params {:page 1}}]
[::rfq/query            {:query :books/list :params {:page 1} :polling-interval-ms 5000}]
[::rfq/execute-mutation {:mutation :books/add :params {:title "x"} :on-success [:my/saved]}]
[::rfq/invalidate-tags  {:tags [[:books]]}]
```

Why a map: arguments are named rather than positional, optional keys can be added without growing arities or trailing opts maps, and new keys can be introduced later without changing call sites.

- **The positional form is still fully supported** — `[::rfq/ensure-query k params]`, `[::rfq/query k params opts]`, `[::rfq/execute-mutation k params opts]` and friends all keep working, with no warnings and no deprecation timeline. The two forms are equivalent; a trailing positional `opts` map is flattened into the same top-level keys the map form uses. The full mapping is in the [Legacy positional form](#legacy-positional-form) appendix.
- **`:params` is optional** in the map form — `{:query :user/current}` and `{:query :user/current :params {}}` resolve to the same cache entry.
- **Two loud errors** guard the map form:
  - A map payload without a keyword under `:query` / `:mutation` throws — e.g. passing bare params `[::rfq/ensure-query {:page 1}]` fails with `re-frame-query: :re-frame.query/ensure-query expects a keyword under :query …`, showing both correct forms.
  - Any **query** event or subscription whose map contains `:on-start`, `:on-success` or `:on-failure` throws `… does not accept #{:on-success} — per-call lifecycle hooks exist on mutations only …`. Queries are observed via global interceptors and `rfq/parse-result-event` instead — see [Lifecycle Hooks](lifecycle-hooks.md#observing-query-lifecycle). Mutations keep their hooks.

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
| `rfq/prefetch` | `(rfq/prefetch {:query k :params params})` — pre-populate cache (convenience for dispatching `::rfq/ensure-query`) |
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
| `[::rfq/ensure-query {:query k :params params}]` | Fetch if stale/absent (called automatically by subscription; can also used for prefetching) |
| `[::rfq/refetch-query {:query k :params params}]` | Force refetch regardless of staleness |
| `[::rfq/mark-active {:query k :params params}]` | Mark a query active (manual lifecycle); accepts `:polling-interval-ms` and `:sub-id` — see [Polling](polling.md) |
| `[::rfq/mark-inactive {:query k :params params}]` | Mark a query inactive and schedule GC; accepts `:sub-id` |
| `[::rfq/execute-mutation {:mutation k :params params}]` | Execute a mutation |
| `[::rfq/execute-mutation {:mutation k :params params :on-start ev :on-success ev :on-failure ev}]` | Execute with [lifecycle hooks](lifecycle-hooks.md) |
| `[::rfq/set-query-data {:query k :params params :data data}]` | Directly set cached query data (for [placeholder data](placeholder-data.md), optimistic updates, rollback). Marks the entry stale — the next `ensure-query` background-refetches. |
| `[::rfq/invalidate-tags {:tags tags}]` | Mark matching queries stale & refetch active ones |
| `[::rfq/remove-query qid]` | Remove a specific query from cache (used internally by GC) |
| `[::rfq/garbage-collect]` | Bulk remove all expired inactive queries |
| `[::rfq/reset-api-state]` | Clear all queries, mutations, and cancel all GC/polling timers |
| `[::rfq/reset-mutation {:mutation k :params params}]` | Clear a mutation's state back to idle |
| `[::rfq/ensure-infinite-query {:query k :params params}]` | Fetch the first page of an [infinite query](infinite-queries.md) if stale/absent |
| `[::rfq/fetch-next-page {:query k :params params}]` | Fetch and append the next page of an [infinite query](infinite-queries.md) |
| `[::rfq/fetch-previous-page {:query k :params params}]` | Fetch and prepend the previous page (requires `:get-previous-cursor`) |
| `[::rfq/refetch-infinite-query {:query k :params params}]` | Sequentially re-fetch every loaded page from page 1 |
| `[::rfq/cancel-query {:query k :params params}]` | Supersede every in-flight request for a query — responses from it are dropped on arrival, `:fetching?`/`:fetching-next?`/`:fetching-prev?`/`:refetch-state` are cleared, `:data`/`:status`/`:error` are left alone. No-op if the query isn't cached. See [Lifecycle Hooks](lifecycle-hooks.md#advanced-cancelling-in-flight-requests). |

## Cancellation

| Function | Description |
|---|---|
| `rfq/cancel-query` | `(rfq/cancel-query {:query k :params params})` — dispatches `::rfq/cancel-query` above |
| `re-frame.query.db/cancel-query` | `(cancel-query db k params query-config request-id)` — pure `db -> db` version, for use directly inside your own event handlers to avoid an extra dispatch cycle |

## Subscriptions

> **Only `::rfq/query` triggers a fetch.** The other query subscriptions are
> derived — they extract a single field from the query state but do **not**
> start a fetch or manage the query lifecycle. Always subscribe to
> `::rfq/query` first (or instead).

| Subscription | Triggers fetch? | Returns |
|---|---|---|
| `[::rfq/query {:query k :params params}]` | ✅ Yes | Full query state map |
| `[::rfq/query {:query k :params params :polling-interval-ms 5000 :skip? false}]` | ✅ Yes | Full query state map (`:polling-interval-ms` and `:skip?` are optional top-level keys) |
| `[::rfq/query-state {:query k :params params}]` | ❌ No | Full query state map (same shape as `::rfq/query`, no side effects) |
| `[::rfq/infinite-query-state {:query k :params params}]` | ❌ No | Full infinite query state (same shape as `::rfq/infinite-query`, no side effects) |
| `[::rfq/query-data {:query k :params params}]` | ❌ No | Just the `:data` |
| `[::rfq/query-status {:query k :params params}]` | ❌ No | Just the `:status` (`:idle`, `:loading`, `:success`, `:error`) |
| `[::rfq/query-fetching? {:query k :params params}]` | ❌ No | Boolean — is a request in flight? |
| `[::rfq/query-error {:query k :params params}]` | ❌ No | Just the `:error` |
| `[::rfq/infinite-query {:query k :params params}]` | ✅ Yes | Full [infinite query](infinite-queries.md) state (pages, cursors, has-next?) |
| `[::rfq/infinite-query-data {:query k :params params}]` | ❌ No | Just the infinite `:data` (`{:pages :page-params :has-next? :has-prev?}`) |
| `[::rfq/mutation {:mutation k :params params}]` | ❌ No | Mutation state map |
| `[::rfq/mutation-status {:mutation k :params params}]` | ❌ No | Just the mutation `:status` |

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

## Event Introspection

| Function | Description |
|---|---|
| `rfq/parse-result-event` | `(rfq/parse-result-event event-vec)` — parses one of the four query result events (`::rfq/query-success`, `::rfq/query-failure`, `::rfq/infinite-page-success`, `::rfq/infinite-page-failure`) into a map. Returns `nil` for any other event. Use inside global interceptors so you don't have to positionally destructure rfq event vectors. See [Lifecycle Hooks](lifecycle-hooks.md#observing-query-lifecycle). |
| `rfq/request-control` | `(rfq/request-control event-vec)` — reads the per-attempt `{:query-id :request-id :issued-at}` stamp off a result callback's metadata, or `nil` if the event carries none. Effect adapters use it to key transport-level state (e.g. an abort handle) by `:query-id`. See [Lifecycle Hooks](lifecycle-hooks.md#advanced-cancelling-in-flight-requests). |

Returned map shapes (`rfq/parse-result-event`):

| Event | Map |
|---|---|
| `[::rfq/query-success k params data]` | `{:event-id :k :params :data :request-control}` |
| `[::rfq/query-failure k params error]` | `{:event-id :k :params :error :request-control}` |
| `[::rfq/infinite-page-success k params mode page-data]` | `{:event-id :k :params :mode :data :request-control}` (`:mode` is `nil` \| `:append` \| `:prepend`) |
| `[::rfq/infinite-page-failure k params error]` | `{:event-id :k :params :error :request-control}` |
| anything else | `nil` |

`:request-control` — `{:query-id :request-id :issued-at}` — is only present when the source event carries the per-attempt stamp (i.e. it went through the library's effect wiring rather than a hand-dispatched or adapter-rebuilt event vector).

## Legacy positional form

The positional form predates the map payload and remains fully supported — no warnings, no deprecation. Both columns below are equivalent; a trailing positional `opts` map is flattened into the same top-level keys the map form carries. Internal result events (`::rfq/query-success`, `::rfq/query-failure`, `::rfq/infinite-page-success`, `::rfq/infinite-page-failure`, `::rfq/mutation-success`, `::rfq/mutation-failure`) are positional only and unchanged — use `rfq/parse-result-event` to read them.

| Map form (canonical) | Positional equivalent |
|---|---|
| `[::rfq/ensure-query {:query k :params p}]` | `[::rfq/ensure-query k p]` |
| `[::rfq/refetch-query {:query k :params p}]` | `[::rfq/refetch-query k p]` |
| `[::rfq/cancel-query {:query k :params p}]` | `[::rfq/cancel-query k p]` |
| `[::rfq/set-query-data {:query k :params p :data d}]` | `[::rfq/set-query-data k p d]` |
| `[::rfq/invalidate-tags {:tags tags}]` | `[::rfq/invalidate-tags tags]` |
| `[::rfq/mark-active {:query k :params p :polling-interval-ms n :sub-id id}]` | `[::rfq/mark-active k p {:polling-interval-ms n :sub-id id}]` |
| `[::rfq/mark-inactive {:query k :params p :sub-id id}]` | `[::rfq/mark-inactive k p {:sub-id id}]` |
| `[::rfq/ensure-infinite-query {:query k :params p}]` | `[::rfq/ensure-infinite-query k p]` |
| `[::rfq/fetch-next-page {:query k :params p}]` | `[::rfq/fetch-next-page k p]` |
| `[::rfq/fetch-previous-page {:query k :params p}]` | `[::rfq/fetch-previous-page k p]` |
| `[::rfq/refetch-infinite-query {:query k :params p}]` | `[::rfq/refetch-infinite-query k p]` |
| `[::rfq/execute-mutation {:mutation k :params p :on-start ev :on-success ev :on-failure ev}]` | `[::rfq/execute-mutation k p {:on-start ev :on-success ev :on-failure ev}]` |
| `[::rfq/reset-mutation {:mutation k :params p}]` | `[::rfq/reset-mutation k p]` |
| `[::rfq/query {:query k :params p :skip? b :polling-interval-ms n}]` | `[::rfq/query k p {:skip? b :polling-interval-ms n}]` |
| `[::rfq/query-state {:query k :params p}]` | `[::rfq/query-state k p]` |
| `[::rfq/query-data {:query k :params p}]` | `[::rfq/query-data k p]` |
| `[::rfq/query-status {:query k :params p}]` | `[::rfq/query-status k p]` |
| `[::rfq/query-fetching? {:query k :params p}]` | `[::rfq/query-fetching? k p]` |
| `[::rfq/query-error {:query k :params p}]` | `[::rfq/query-error k p]` |
| `[::rfq/infinite-query {:query k :params p}]` | `[::rfq/infinite-query k p]` |
| `[::rfq/infinite-query-data {:query k :params p}]` | `[::rfq/infinite-query-data k p]` |
| `[::rfq/infinite-query-state {:query k :params p}]` | `[::rfq/infinite-query-state k p]` |
| `[::rfq/mutation {:mutation k :params p}]` | `[::rfq/mutation k p]` |
| `[::rfq/mutation-status {:mutation k :params p}]` | `[::rfq/mutation-status k p]` |

Helper functions in `re-frame.query` follow the same rule — the 1-arity map form is canonical and the positional arity still works:

| Map form (canonical) | Positional equivalent |
|---|---|
| `(rfq/prefetch {:query k :params p})` | `(rfq/prefetch k p)` |
| `(rfq/set-query-data {:query k :params p :data d})` | `(rfq/set-query-data k p d)` |
| `(rfq/cancel-query {:query k :params p})` | `(rfq/cancel-query k p)` |
| `(rfq/fetch-next-page {:query k :params p})` | `(rfq/fetch-next-page k p)` |
| `(rfq/fetch-previous-page {:query k :params p})` | `(rfq/fetch-previous-page k p)` |
| `(rfq/infinite-query-data {:query k :params p})` | `(rfq/infinite-query-data k p)` |
