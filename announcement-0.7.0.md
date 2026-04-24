# re-frame-query 0.7.0 — Clojurians Announcement

---

re-frame-query has been moving fast since the 0.1.0 release. Six versions in, I want to share what's changed — and a shift in how I think about the library.

**com.shipclojure/re-frame-query {:mvn/version "0.7.0"}**

---

## What's new since 0.1.0

**Passive subscriptions (0.2.0+)**

The headline feature across recent releases. `::rfq/query` is still there — subscribing triggers fetch, caching, polling, GC — but you can now opt out of all of that:

```clojure
;; Pure read. No fetch. No side effects. Same shape as ::rfq/query.
@(rf/subscribe [::rfq/query-state :todos/list {:user-id 42}])
@(rf/subscribe [::rfq/infinite-query-state :feed/items {}])
@(rf/subscribe [::rfq/infinite-query-data :feed/items {}]) ;; just the pages
```

Manage lifecycle yourself via events:

```clojure
;; Route enter
(rf/dispatch [::rfq/ensure-query :todos/list {:user-id 42}])
(rf/dispatch [::rfq/mark-active  :todos/list {:user-id 42} {:polling-interval-ms 10000}])

;; Route leave
(rf/dispatch [::rfq/mark-inactive :todos/list {:user-id 42}])
```

**Polling moved to events (0.5.0)**

`mark-active` now owns polling — pass `:polling-interval-ms` directly. `::rfq/query` still wires this up automatically if you want convenience, but if you use passive subs + explicit lifecycle, polling works identically. No effectful subscription needed.

**`re-frame.query.db` — inline cache operations (0.4.0)**

Pure `db → db` functions for reading and writing the query cache inside your own event handlers:

```clojure
(require '[re-frame.query.db :as rfq-db])

(rf/reg-event-fx ::optimistic-toggle
  (fn [{:keys [db]} [_ {:keys [id]}]]
    (let [current (rfq-db/get-query-data db :todos/list {:user-id 1})
          patched  (map #(if (= (:id %) id) (update % :done not) %) current)]
      {:db (-> db
               (assoc :my-app/snapshot current)
               (rfq-db/set-query-data :todos/list {:user-id 1} patched))})))
```

No extra dispatches. No side effects. Just a plain function from db to db.

**Bidirectional infinite queries (0.3.0)**

Add `:get-previous-cursor` to your `:infinite` config and `fetch-previous-page` works. `:max-pages` becomes a true sliding window in both directions.

**Bug fixes worth noting**

- `invalidate-tags` was refetching *all* active queries, not just the ones whose tags matched. Fixed.
- `::rfq/query-data` et al. were accidentally causal — subscribing triggered a fetch. Fixed in 0.6.0 (see below).

---

## On causality and testability

In the 0.1.0 announcement I called out `::rfq/query` as a deliberate trade-off: it's causal (subscribes dispatch events), which isn't fully idiomatic re-frame.

After using the library in production, my view has shifted. **The event-driven approach is genuinely better** — not just more idiomatic, but more practical:

- Your data loading logic lives in event handlers and route hooks, not implicitly inside subscriptions
- You can test it: dispatch an event, check app-db. No reagent reactions, no reactive context
- Polling, lifecycle, and cache management all become inspectable re-frame events in your event log
- `rfq-db` functions compose naturally inside your own `reg-event-db` / `reg-event-fx` handlers

The causal `::rfq/query` is still there for quick prototyping and cases where you don't care. But if you're building something you need to test and reason about, reach for the events + passive subs pattern from the start.

A related bug we caught along the way: `::rfq/query-data`, `::rfq/query-status`, `::rfq/query-fetching?`, and `::rfq/query-error` were deriving from `::rfq/query` instead of `::rfq/query-state`. So subscribing to any of them would silently trigger a fetch — the opposite of what the docs said. **This is a breaking change in 0.6.0.** If you were using these subs and expecting them to trigger fetches, you'll need to switch to `::rfq/query` explicitly.

---

GitHub: https://github.com/shipclojure/re-frame-query
Full changelog: https://github.com/shipclojure/re-frame-query/blob/main/CHANGELOG.md
