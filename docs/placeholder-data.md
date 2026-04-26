# Placeholder Data

When you already have partial data for a query — e.g. you're navigating from a list view (`:todos/list`) into a detail view (`:todos/get`) — you can seed the cache so the user sees content instantly while the real fetch runs in the background.

```clojure
(require '[re-frame.query.db :as rfq-db])

(rf/reg-event-fx :todos/route-detail-entered
  (fn [{:keys [db]} [_ todo-id]]
    (let [list-data    (rfq-db/get-query-data db :todos/list {})
          placeholder  (some #(when (= (:id %) todo-id) %) list-data)]
      {:db (cond-> db
             placeholder (rfq-db/set-query-data :todos/get {:id todo-id} placeholder))
       :fx [[:dispatch [::rfq/ensure-query :todos/get {:id todo-id}]]
            [:dispatch [::rfq/mark-active   :todos/get {:id todo-id}]]]})))
```

`set-query-data` writes the placeholder **and marks the entry stale**, so the very next `ensure-query` triggers a background refetch. The view subscribed via `::rfq/query-state` sees `:status :success` (with the placeholder) and `:fetching? true` simultaneously — render the placeholder immediately, optionally show a subtle refresh indicator until the verified data arrives.

## Behavior contract

`set-query-data` (both the `::rfq/set-query-data` event and the `rfq-db/set-query-data` pure function) always:

- writes `:status :success` and your `:data`
- marks the entry **stale** (`:stale? true`) — the data has not been verified
- preserves `:fetching?` if a request is already in flight (so it doesn't lie about network state)
- creates the cache entry if it didn't exist

Because the entry is stale, any of the following will trigger a background refetch:

- the next `::rfq/ensure-query` for the same `[k params]`
- a fresh subscriber to `::rfq/query` (the causal sub) for the same `[k params]`

In-flight deduplication still applies — if a fetch is already running, the placeholder write doesn't kick off a second one.

## Placeholder data vs prefetching

| Pattern | Cache freshness after the write | Triggers a fetch? |
|---|---|---|
| [Prefetching](prefetching.md) | fresh (came from the network) | only if absent/stale |
| Placeholder data | stale (came from your code) | yes, on next `ensure-query` |

Use **prefetching** when you want to warm the cache with canonical data ahead of time (e.g. on hover). Use **placeholder data** when you want the user to see *something* now, knowing it'll be replaced shortly.

## Placeholder data vs optimistic updates

Same underlying mechanism — both write unverified data via `set-query-data` and let a later event confirm it. The difference is what does the confirming:

- **Placeholder data:** the next `ensure-query` (driven by route enter or subscription).
- **Optimistic updates:** the mutation's `:invalidates`, which fires after the server confirms the write.

See [Lifecycle Hooks → Optimistic Updates Recipe](lifecycle-hooks.md#optimistic-updates-recipe) for the optimistic flow.
