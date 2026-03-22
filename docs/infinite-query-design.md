# Infinite Query — Design Document

## Problem

Infinite scroll / "load more" patterns are common in apps (feeds, timelines, search results). The user scrolls down, more items load and append to the list. When a mutation invalidates the data (e.g., user deletes an item), the entire list must re-fetch correctly with fresh cursors.

This cannot be solved correctly in userland because cursor-based pagination breaks on invalidation — old cursors become stale after mutations shift the data.

## Prior Art

### TanStack Query (`useInfiniteQuery`)

- Stores `{ pages: [page1, page2, ...], pageParams: [cursor1, cursor2, ...] }` in a single cache entry
- User provides `getNextPageParam(lastPage) → nextCursor` and `initialPageParam`
- `fetchNextPage()` appends a new page
- On invalidation: re-fetches ALL pages sequentially from `initialPageParam`, extracting fresh cursors from each response
- `maxPages` caps the sliding window

### RTK Query (`build.infiniteQuery`)

- Same `InfiniteData<pages, pageParams>` structure
- Same sequential re-fetch on invalidation (`refetchCachedPages: true` by default)
- Same `getNextPageParam` / `getPreviousPageParam` / `initialPageParam` API

Both libraries built this as a first-class feature because the sequential re-fetch with fresh cursors cannot be done correctly outside the library.

---

## API Design

### Query Registration

```clojure
(rfq/reg-query :feed/items
  {:query-fn         (fn [{:keys [cursor]}]
                       {:method :get
                        :url    (str "/api/feed?cursor=" (or cursor ""))})
   :infinite         {:initial-cursor  nil
                      :get-next-cursor (fn [page-response] (:next_cursor page-response))}
   :max-pages        nil          ;; optional, nil = unlimited
   :stale-time-ms    60000
   :cache-time-ms    300000
   :tags             (constantly [[:feed]])})
```

#### `:infinite` config keys

| Key | Type | Required | Description |
|---|---|---|---|
| `:initial-cursor` | any | ✅ | The cursor for the first page (e.g., `nil`, `0`, `""`) |
| `:get-next-cursor` | `(fn [page-response] -> cursor-or-nil)` | ✅ | Extracts the next page cursor from a page response. Returns `nil` when there are no more pages. |

#### `:max-pages`

Optional integer. When set, only the most recent N pages are kept. Loading a new page drops the oldest page from the front (sliding window). `nil` means unlimited.

### Subscription

```clojure
@(rf/subscribe [::rfq/infinite-query :feed/items {}])
```

Returns:

```clojure
{:status          :success        ;; :idle | :loading | :success | :error
 :data            {:pages       [page1-resp page2-resp page3-resp]
                   :page-params [nil "abc" "def"]
                   :has-next?   true}
 :error           nil
 :fetching?       false           ;; true during initial load or full re-fetch
 :fetching-next?  false           ;; true only during fetch-next-page
 :stale?          false
 :active?         true}
```

The `:data` shape:

| Key | Type | Description |
|---|---|---|
| `:pages` | `[page1 page2 ...]` | Vector of raw page responses, in order |
| `:page-params` | `[cursor1 cursor2 ...]` | The cursor used to fetch each page |
| `:has-next?` | boolean | `true` if `get-next-cursor` returned non-nil for the last page |

The subscription works like `::rfq/query` — subscribing triggers the first page fetch and marks the query active. Disposing marks it inactive and starts GC.

### Events

#### Fetch next page

```clojure
(rf/dispatch [::rfq/fetch-next-page :feed/items {}])
```

Reads the current `next-cursor` from the cache entry, fetches the next page, and appends it on success. No-op if `:has-next?` is false or a fetch is already in progress.

#### Ensure query (first page)

```clojure
(rf/dispatch [::rfq/ensure-query :feed/items {}])
```

Behaves the same as regular queries — fetches the first page if absent or stale. The subscription dispatches this automatically.

#### Invalidation (via tags)

```clojure
(rf/dispatch [::rfq/invalidate-tags [[:feed]]])
```

Triggers a **sequential re-fetch** of all previously loaded pages:

1. Records `target-page-count` = number of pages currently loaded
2. Clears the accumulated pages
3. Fetches page 1 with `initial-cursor`
4. On page 1 success: extracts fresh cursor, fetches page 2
5. Continues until `target-page-count` pages are re-fetched or `get-next-cursor` returns nil
6. Sets `fetching?` to false when complete

During re-fetch, `fetching?` is true but `:status` stays `:success` and `:data` retains the **old pages** so the UI doesn't flash (same pattern as regular query background refetch).

---

## Cache Entry Shape

Stored in `app-db` under `[:re-frame.query/queries [:feed/items {}]]`:

```clojure
{:status          :success
 :data            {:pages       [{:items [...] :next_cursor "abc"}
                                 {:items [...] :next_cursor "def"}]
                   :page-params [nil "abc"]
                   :has-next?   true}
 :error           nil
 :fetching?       false
 :fetching-next?  false
 :stale?          false
 :active?         true
 :fetched-at      1718900000000
 :tags            #{[:feed]}
 :stale-time-ms   60000
 :cache-time-ms   300000
 ;; Internal: used during sequential re-fetch
 :refetch-state   nil}
```

The `:refetch-state` key is transient — only present during a sequential re-fetch:

```clojure
{:target-page-count 3    ;; how many pages to re-fetch
 :pages-fetched     1    ;; how many have completed so far
 :old-data          ...} ;; previous :data, shown during re-fetch
```

---

## Event Flow Diagrams

### Normal load (user scrolls)

```
1. Subscribe → ensure-query dispatches
2. Fetch page 1 (initial-cursor=nil)
3. infinite-page-success → store page 1, extract next-cursor
4. User scrolls → dispatch fetch-next-page
5. Fetch page 2 (cursor="abc")
6. infinite-page-success → append page 2, extract next-cursor
7. User scrolls → dispatch fetch-next-page
8. ...
```

### Invalidation re-fetch (mutation happened)

```
1. Mutation succeeds → invalidate-tags [[:feed]]
2. Query is active + stale → refetch-query fires
3. refetch-query sees :infinite config → starts sequential re-fetch:
   a. Set refetch-state = {:target-page-count 3, :pages-fetched 0, :old-data current-data}
   b. Fetch page 1 (initial-cursor)
4. infinite-page-success:
   a. Append page 1 to new pages
   b. pages-fetched=1, target=3 → extract cursor, fetch page 2
5. infinite-page-success:
   a. Append page 2
   b. pages-fetched=2, target=3 → fetch page 3
6. infinite-page-success:
   a. Append page 3
   b. pages-fetched=3, target=3 → DONE
   c. Clear refetch-state, set fetching?=false
```

### Max pages (sliding window)

```
max-pages=3, user has loaded pages [1,2,3]

1. User scrolls → fetch-next-page
2. Page 4 arrives
3. Append page 4, drop page 1 → pages = [2,3,4]
4. page-params = [cursor2, cursor3, cursor4]
```

---

## Implementation Plan

### New/modified files

| File | Changes |
|---|---|
| `events.cljc` | `fetch-next-page` event, `infinite-page-success` event, modified `refetch-query` to handle infinite, modified `ensure-query` for first page |
| `subs.cljc` | `::rfq/infinite-query` subscription (reg-sub-raw with lifecycle) |
| `util.cljc` | Helper: `infinite-query?` predicate |
| `query.cljc` | Public `fetch-next-page` convenience fn, updated docstrings |
| `registry.cljc` | Document `:infinite` and `:max-pages` config keys |

### New events (internal)

| Event | Purpose |
|---|---|
| `::rfq/fetch-next-page` | Public — fetch and append next page |
| `::rfq/infinite-page-success` | Internal — handle page arrival, continue re-fetch chain if needed |
| `::rfq/infinite-page-failure` | Internal — handle page fetch failure |

### How it integrates with existing features

| Feature | How it works with infinite queries |
|---|---|
| **Tag invalidation** | Same tags system. Invalidation triggers sequential re-fetch. |
| **GC** | Same `cache-time-ms` / `mark-active` / `mark-inactive`. The entire infinite query is one cache entry. |
| **Polling** | Works — polls trigger re-fetch (sequential). Consider whether this is desirable for infinite lists. |
| **Stale time** | Same — `ensure-query` skips if fresh. |
| **Transform response** | Applied per-page in `infinite-page-success`. |
| **Prefetching** | `rfq/prefetch` fetches the first page only. |
| **set-query-data** | Works — replaces the entire `{:pages, :page-params, :has-next?}` structure. |
| **Optimistic updates** | Use `set-query-data` to patch the `:pages` vector optimistically. |

### What does NOT apply

| Feature | Why |
|---|---|
| **Polling** | Probably undesirable for infinite lists — re-fetching 10 pages every N seconds is expensive. Users should disable polling for infinite queries. |
| **Bidirectional scrolling** | Not in v1. Could add `fetch-previous-page` + `get-previous-cursor` later. |

---

## Subscription Behavior

The `::rfq/infinite-query` subscription:

1. **On subscribe**: dispatches `ensure-query` (first page) + `mark-active`
2. **While subscribed**: returns the infinite data shape reactively
3. **On dispose**: dispatches `mark-inactive` (starts GC timer)

It does NOT auto-fetch subsequent pages — that's triggered by the user (scroll, button click) via `fetch-next-page`.

---

## Example Usage

### View (Reagent)

```clojure
(defn feed []
  (let [{:keys [status data fetching? fetching-next?]}
        @(rf/subscribe [::rfq/infinite-query :feed/items {}])
        {:keys [pages has-next?]} data]
    [:div
     (case status
       :loading [:div.loading "Loading feed…"]
       :error   [:div.error "Failed to load feed"]
       :success [:div
                 ;; Render all pages
                 (for [page pages
                       item (:items page)]
                   ^{:key (:id item)}
                   [:div.feed-item (:title item)])
                 ;; Load more button
                 (when has-next?
                   [:button
                    {:disabled fetching-next?
                     :on-click #(rf/dispatch [::rfq/fetch-next-page :feed/items {}])}
                    (if fetching-next? "Loading…" "Load More")])
                 (when fetching?
                   [:div.refreshing "Refreshing…"])]
       [:div "Idle"])]))
```

### MSW Mock Handler

```javascript
http.get("/api/feed", async ({ request }) => {
  const url = new URL(request.url);
  const cursor = parseInt(url.searchParams.get("cursor") || "0");
  const limit = 20;
  const items = allItems.slice(cursor, cursor + limit);
  const nextCursor = cursor + limit < allItems.length ? cursor + limit : null;
  return HttpResponse.json({
    items,
    next_cursor: nextCursor,
  });
});
```

---

## Test Plan

### Unit Tests

| Test | What it asserts |
|---|---|
| First page loads via ensure-query | Status transitions: idle → loading → success |
| fetch-next-page appends data | Pages array grows, page-params updated, has-next? correct |
| fetch-next-page is no-op when no next cursor | No effect dispatched |
| fetch-next-page is no-op when already fetching | Deduplication |
| Invalidation triggers sequential re-fetch | All pages re-fetched with fresh cursors |
| Sequential re-fetch preserves old data during re-fetch | Status stays :success, old pages visible |
| max-pages drops oldest page | Sliding window behavior |
| get-next-cursor returning nil sets has-next? false | End of list detection |
| GC removes infinite query when inactive | Same as regular queries |
| transform-response applied per page | Each page transformed before appending |

### E2E Tests

| Test | What it asserts |
|---|---|
| Feed loads first page | Items visible |
| Load More appends items | New items appear below existing |
| Scrolling to bottom triggers load (if wired with intersection observer) | Automatic append |
| After mutation, list re-fetches correctly | No duplicates or missing items |
| Inspector shows accumulated pages | Visible in app-db inspector |

---

## Design Decisions

1. **`::rfq/infinite-query` is a separate subscription** — different return shape (`:pages`, `:page-params`, `:has-next?`), different lifecycle behavior. Clearer than overloading `::rfq/query`.

2. **During sequential re-fetch, old data is preserved (atomic swap).** Fresh pages accumulate in `:refetch-state` (internal, not in `:data`). Only on final success are they swapped into `:data`. The UI shows old pages throughout the re-fetch — no partial updates, no flicker. This matches both TanStack and RTK behavior.

3. **No `fetch-previous-page` in v1.** Can be added later with `:get-previous-cursor`. Keeps scope small.

4. **Error during sequential re-fetch: atomic failure, preserve old data.** If any page in the re-fetch chain fails, the entire re-fetch fails. Old `:data` stays untouched. Status changes to `:error`. User can retry, which starts the sequential re-fetch over from page 1. This matches TanStack and RTK — both use a local accumulator that only commits on full success.

   ```
   1. Re-fetch starts → {:fetching? true, :refetch-state {:target 3, :fetched 0, :pages []}}
   2. Page 1 success → refetch-state accumulator updated (NOT :data)
   3. Page 2 success → refetch-state accumulator updated (NOT :data)
   4a. Page 3 success → swap :data with accumulated pages, clear :refetch-state ✓
   4b. Page 3 FAILS → set :error, clear :refetch-state, :data untouched ✓
   ```
