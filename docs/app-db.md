# Where Data Lives in `app-db`

re-frame-query stores **all** its state inside your re-frame `app-db` under two
namespaced keys. This means query state is fully inspectable with
[re-frame-10x](https://github.com/day8/re-frame-10x), serializable, and
compatible with time-travel debugging.

```clojure
;; Your app-db will look like:
{;; ... your own app state ...
 :my-app/route   [:home]
 :my-app/user    {:id 42 :name "Alice"}

 ;; ┌─── re-frame-query state ─────────────────────────────────────────┐

 :re-frame.query/queries
 {[:todos/list {:user-id 42}]          ;; ← query-id = [key params]
  {:status     :success
   :data       [{:id 1 :title "Ship it"} {:id 2 :title "Write docs"}]
   :error      nil
   :fetching?  false
   :stale?     false
   :active?    true                     ;; ← a component is subscribed
   :fetched-at 1718900000000
   :tags       #{[:todos :user 42]}
   :stale-time-ms 30000
   :cache-time-ms 300000}

  [:todos/list {:user-id 7}]
  {:status :loading :data nil :fetching? true ,,,}}

 :re-frame.query/mutations
 {[:todos/add {:user-id 42 :title "New"}]
  {:status :success
   :data   {:id 3 :title "New"}
   :error  nil}}

 ;; └──────────────────────────────────────────────────────────────────┘
 }
```

## Key layout

| `app-db` key | Shape | Description |
|---|---|---|
| `:re-frame.query/queries` | `{[k params] → query-map}` | Cache of all fetched queries. Each entry is a query-id (`[key params]`) mapped to the [query state shape](api-reference.md#query-state-shape). |
| `:re-frame.query/mutations` | `{[k params] → mutation-map}` | Status of in-flight and completed mutations. Each entry has `:status`, `:data`, and `:error`. |

## What this means for your app

- **No conflicts** — the namespaced keys (`:re-frame.query/*`) won't collide with your own state.
- **Fully inspectable** — open re-frame-10x and browse `:re-frame.query/queries` to see every cached query, its status, data, and tags.
- **Serializable** — GC timer handles are stored in a separate side-channel atom (not in `app-db`), so your db remains serializable for time-travel and persistence.
- **You own the db** — re-frame-query never touches keys outside its namespace. You can safely `assoc`, `merge`, or `reset!` your own keys alongside it.
