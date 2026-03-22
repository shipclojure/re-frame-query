# Twitter

Announcing re-frame-query 0.1.0 🎉

TanStack Query / RTK Query inspired data fetching for re-frame.

Register once, subscribe anywhere:

```clojure

(require [re-frame.query :as rfq])

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

✅ Tag-based invalidation
✅ Per-query GC
✅ Polling
✅ Infinite scroll
✅ Optimistic updates
✅ Transport-agnostic
✅ All state in app-db

https://github.com/shipclojure/re-frame-query

---

# LinkedIn

Excited to announce re-frame-query 0.1.0 — declarative data fetching and caching for re-frame, inspired by TanStack Query and RTK Query.

If you've built React apps with TanStack Query, you know how it transforms data fetching: subscribe once, and the library handles fetching, caching, background refetching, garbage collection, and invalidation. re-frame-query brings that same model to ClojureScript + re-frame.

Register a query — describe what to fetch, the library handles the rest:

```clojure
(rfq/reg-query :todos/list
  {:query-fn      (fn [{:keys [user-id]}]
                    {:method :get :url (str "/api/users/" user-id "/todos")})
   :stale-time-ms 30000
   :tags          (fn [{:keys [user-id]}] [[:todos :user user-id]])})
```

Then subscribe — one call does fetch + cache + refetch + GC:

```clojure
(let [{:keys [status data]} @(rf/subscribe [::rfq/query :todos/list {:user-id 42}])]
  (case status
    :loading [:div "Loading..."]
    :success [:ul (for [todo data] [:li (:title todo)])]))
```

What it includes:
• Tag-based cache invalidation — mutations invalidate tags, matching queries auto-refetch
• Per-query garbage collection — inactive queries evicted via timers, same model as TanStack
• Polling with per-subscriber intervals
• Infinite queries — cursor-based pagination with sequential re-fetch on invalidation
• Mutation lifecycle hooks for optimistic updates with snapshot/rollback
• Transport-agnostic — HTTP, WebSocket, GraphQL, anything that's a re-frame effect
• All state in app-db — fully inspectable, serializable, time-travel debuggable

The subscription-driven approach isn't fully idiomatic re-frame (views become causal), but it's a deliberate convenience trade-off. For purists, the library exposes all the underlying events (ensure-query, mark-active, mark-inactive) so you can wire things up in the classic re-frame style through navigation hooks.

Available on Clojars: com.shipclojure/re-frame-query {:mvn/version "0.1.0"}

Two full example apps (Reagent + UIx) with 8 demo tabs, MSW mocks, 80 unit tests, and 60 Playwright e2e tests.

GitHub: https://github.com/shipclojure/re-frame-query

#clojure #clojurescript #opensource #webdev #reframe
