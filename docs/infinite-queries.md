# Infinite Queries

For cursor-based pagination (infinite scroll, "load more"), register a query with the `:infinite` config:

```clojure
(rfq/reg-query :feed/items
  {:query-fn      (fn [{:keys [user cursor]}]
                    {:method :get
                     :url    (str "/api/feed?user=" user "&cursor=" (or cursor 0))})
   :infinite      {:initial-cursor 0
                   :get-next-cursor (fn [page-response] (:next_cursor page-response))}
   :stale-time-ms 30000
   :tags          (fn [{:keys [user]}] [[:feed] [:feed user]])})
```

## `:infinite` config keys

| Key                | Required | Description                                                                          |
|--------------------|----------|--------------------------------------------------------------------------------------|
| `:initial-cursor`  | ✅       | Cursor for the first page (e.g., `0`, `nil`, `""`)                                   |
| `:get-next-cursor` | ✅       | `(fn [page-response] -> cursor-or-nil)` — returns `nil` when there are no more pages |

## Optional: `:max-pages`

Set `:max-pages` on the query config to cap how many pages are kept in memory (sliding window). When a new page is loaded past the limit, the oldest page is dropped.

## Subscribe with `::rfq/infinite-query`

```clojure
(let [{:keys [status data fetching? fetching-next?]}
      @(rf/subscribe [::rfq/infinite-query :feed/items {:user "alex"}])
      {:keys [pages has-next?]} data]
  ;; pages = [page1-response, page2-response, ...]
  ;; Each page is the raw response from your API
  (for [page pages
        item (:items page)]
    ^{:key (:id item)}
    [:div (:title item)]))
```

The subscription returns the same shape as `::rfq/query` with additional infinite-specific fields:

| Key               | Description                                                                      |
|-------------------|----------------------------------------------------------------------------------|
| `:data`           | `{:pages [...] :page-params [...] :has-next? bool :next-cursor val}`             |
| `:fetching-next?` | `true` only during a `fetch-next-page` call (not during initial load or refetch) |

Like `::rfq/query`, subscribing triggers the first page fetch and marks the query active. Unsubscribing starts GC.

## Fetch the next page

```clojure
;; From a "Load More" button
[:button {:on-click #(rfq/fetch-next-page :feed/items {:user "alex"})
          :disabled fetching-next?}
 "Load More"]

;; Or dispatch directly
(rf/dispatch [::rfq/fetch-next-page :feed/items {:user "alex"}])
```

No-op if `has-next?` is false or a fetch is already in progress.

## How invalidation works (sequential re-fetch)

When a mutation invalidates an infinite query's tags, the library re-fetches **all previously loaded pages sequentially** with fresh cursors — the same approach used by TanStack Query and RTK Query:

1. Records how many pages were loaded (e.g., 3)
2. Fetches page 1 with `initial-cursor`
3. Extracts fresh cursor from page 1's response, fetches page 2
4. Repeats until all pages are re-fetched (or server returns no next cursor)

**During re-fetch:** status stays `:success` and old data is shown (no flicker). Fresh pages are swapped in atomically when the full re-fetch completes.

**On error:** the re-fetch fails atomically — old data is preserved, status changes to `:error`. Retry starts the chain over from page 1.

## Independent cache entries

Different params create different cache entries. Switching between users maintains separate scroll positions and page counts:

```clojure
;; These are two independent cache entries:
@(rf/subscribe [::rfq/infinite-query :feed/items {:user "alex"}])
@(rf/subscribe [::rfq/infinite-query :feed/items {:user "maria"}])
```

## Per-user tag invalidation

Use params in your tags to invalidate only one user's feed:

```clojure
;; Query tags include the user
:tags (fn [{:keys [user]}] [[:feed] [:feed user]])

;; Mutation invalidates only the posting user's feed
:invalidates (fn [{:keys [user]}] [[:feed user]])
```
