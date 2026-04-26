# Prefetching

Pre-populate the cache before a component subscribes. Useful for hover-triggered preloading, route prefetching, or warming the cache from event handlers.

```clojure
;; On mouse-enter for a "Next page" button
(rfq/prefetch :books/list {:page 2})

;; Or dispatch ensure-query directly — same thing
(rf/dispatch [::rfq/ensure-query :books/list {:page 2}])
```

When the component later mounts and subscribes, it finds cached data and skips the fetch:

```clojure
;; This finds cached data from the prefetch — no loading spinner
@(rf/subscribe [::rfq/query :books/list {:page 2}])
```

Prefetch respects stale-time and in-flight deduplication — it won't re-fetch data that's already fresh or already being fetched. It does **not** mark the query as active, so the data is subject to normal GC rules.

> **Related:** if you want to seed the cache from existing client data (e.g. populating `:todos/get` from `:todos/list` on route enter) rather than from the network, see [Placeholder Data](placeholder-data.md). That pattern intentionally marks the entry stale so a refetch confirms it.
