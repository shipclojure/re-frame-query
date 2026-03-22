**re-frame-query** — Declarative data fetching and caching for re-frame. 0.1.0 is now available on Clojars.

If you've used TanStack Query or RTK Query in React land and wished re-frame had something similar — this is it.

```clojure
;; Register once
(rfq/reg-query :todos/list
  {:query-fn      (fn [{:keys [user-id]}]
                    {:method :get :url (str "/api/users/" user-id "/todos")})
   :stale-time-ms 30000
   :tags          (fn [{:keys [user-id]}] [[:todos :user user-id]])})

;; Subscribe = fetch + cache + refetch + GC. That's it.
(let [{:keys [status data]} @(rf/subscribe [::rfq/query :todos/list {:user-id 42}])]
  (case status
    :loading [:div "Loading..."]
    :success [:ul (for [todo data] ^{:key (:id todo)} [:li (:title todo)])]))
```

What's in the box:
- **Subscribe-driven** — subscribing triggers fetch, caching, and cleanup automatically (like `useQuery`)
- **Tag-based invalidation** — mutations invalidate tags, matching queries refetch
- **Per-query GC** — inactive queries are evicted after `cache-time-ms` via per-query timers
- **Polling** — per-query or per-subscriber intervals, lowest wins
- **Infinite queries** — cursor-based pagination with sequential re-fetch on invalidation
- **Mutation lifecycle hooks** — `on-start` / `on-success` / `on-failure` for optimistic updates
- **Transport-agnostic** — works with any re-frame effect (HTTP, WebSocket, GraphQL, etc.)
- **All state in app-db** — inspectable, serializable, time-travel friendly

A note on style: the `::rfq/query` subscription is causal (it dispatches events on subscribe/dispose), which isn't fully idiomatic re-frame. It's a deliberate convenience trade-off. If you prefer the classic re-frame approach where views are never causal, the library exposes all the building blocks — dispatch `::rfq/ensure-query` + `::rfq/mark-active` on route enter, `::rfq/mark-inactive` on leave, and use passive subscriptions like `::rfq/query-data` in your views.

```clojure
com.shipclojure/re-frame-query {:mvn/version "0.1.0"}
```

Two example apps included (Reagent + UIx) with 8 demo tabs each, MSW mocks, and Playwright e2e tests.

GitHub: https://github.com/shipclojure/re-frame-query
