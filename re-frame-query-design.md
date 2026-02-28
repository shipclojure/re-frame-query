# Re-frame Query: API Design + Proposal Implementation

This document proposes an API and implementation sketch for a query/cache
invalidation layer on top of `re-frame`, inspired by TanStack Query and RTK
Query. It focuses on ergonomic data fetching, cache keys, invalidation, and
request lifecycle tracking while staying idiomatic in `re-frame`.

## Goals

- Provide declarative queries and mutations with caching.
- Enable cache invalidation and dependent refetching.
- Keep all state in the re-frame DB (predictable, inspectable).
- Offer subscription-based access to query state.
- Allow adapters for different transport layers (HTTP, GraphQL, etc.).

Non-goals

- Implement a full GraphQL client.
- Replace existing re-frame effect handlers; integrate with them.

## Proposed API

### 1. Registering a Query Definition

```clojure
(rfq/reg-query
  :todos/list
  {:query-fn (fn [{:keys [user-id]}]
               {:http-xhrio {:method :get
                             :uri (str "/api/users/" user-id "/todos")
                             :response-format (ajax/json-response-format {:keywords? true})
                             :on-success [:rfq/query-success :todos/list {:user-id user-id}]
                             :on-failure [:rfq/query-failure :todos/list {:user-id user-id}]}})
   :cache-time-ms (* 5 60 1000)
   :stale-time-ms 30000
   :tags (fn [{:keys [user-id]}]
           [[:todos :user user-id]])})
```

Notes

- `query-fn` returns a re-frame effect map. This keeps transport pluggable.
- `tags` returns a set of tag tuples used for invalidation.

### 2. Dispatching a Query

```clojure
(rf/dispatch [:rfq/ensure-query :todos/list {:user-id 42}])
```

This triggers a fetch if no cached value exists or if it is stale.

### 3. Subscribing to Query State

```clojure
(rf/subscribe [:rfq/query :todos/list {:user-id 42}])
```

The subscription returns a map with fields like:

```clojure
{:status :success
 :data   [...]
 :error  nil
 :fetched-at 1700000000000
 :stale? false
 :fetching? false}
```

### 4. Registering a Mutation

```clojure
(rfq/reg-mutation
  :todos/add
  {:mutation-fn (fn [{:keys [user-id title]}]
                  {:http-xhrio {:method :post
                                :uri (str "/api/users/" user-id "/todos")
                                :params {:title title}
                                :response-format (ajax/json-response-format {:keywords? true})
                                :on-success [:rfq/mutation-success :todos/add {:user-id user-id}]
                                :on-failure [:rfq/mutation-failure :todos/add {:user-id user-id}]}})
   :invalidates (fn [{:keys [user-id]}]
                  [[:todos :user user-id]])})
```

### 5. Dispatching a Mutation

```clojure
(rf/dispatch [:rfq/execute-mutation :todos/add {:user-id 42 :title "Ship it"}])
```

### 6. Invalidation

```clojure
(rf/dispatch [:rfq/invalidate-tags [[:todos :user 42]]])
```

This will mark matching queries stale and refetch active ones.

## Data Model (re-frame db)

```clojure
{:rfq/queries
 {[:todos/list {:user-id 42}]
  {:status :success
   :data [...]
   :error nil
   :fetched-at 1700000000000
   :stale-time-ms 30000
   :cache-time-ms 300000
   :stale? false
   :fetching? false
   :tags #{[:todos :user 42]}}}

 :rfq/mutations
 {[:todos/add {:user-id 42 :title "Ship it"}]
  {:status :loading
   :error nil}}}
```

## Event API Summary

Events (internal namespace `:rfq/*`)

- `:rfq/ensure-query` (query-key, params)
- `:rfq/refetch-query` (query-key, params)
- `:rfq/query-success` (query-key, params, data)
- `:rfq/query-failure` (query-key, params, error)
- `:rfq/execute-mutation` (mutation-key, params)
- `:rfq/mutation-success` (mutation-key, params, data)
- `:rfq/mutation-failure` (mutation-key, params, error)
- `:rfq/invalidate-tags` (tags)
- `:rfq/garbage-collect` (now)

Subscriptions

- `:rfq/query` (query-key, params)
- `:rfq/query-data` (query-key, params)
- `:rfq/query-status` (query-key, params)

Registrations

- `rfq/reg-query` (query-key, config)
- `rfq/reg-mutation` (mutation-key, config)

## Proposal Implementation (Sketch)

### 1. Registry

```clojure
(defonce registry (atom {:queries {} :mutations {}}))

(defn reg-query [k config]
  (swap! registry assoc-in [:queries k] config))

(defn reg-mutation [k config]
  (swap! registry assoc-in [:mutations k] config))
```

### 2. Query Identity

```clojure
(defn query-id [k params]
  [k params])
```

### 3. Ensure Query Event

```clojure
(rf/reg-event-fx
  :rfq/ensure-query
  (fn [{:keys [db]} [_ k params]]
    (let [qid (query-id k params)
          q (get-in db [:rfq/queries qid])
          now (.now js/Date)
          stale? (or (nil? q)
                     (:stale? q)
                     (and (:stale-time-ms q)
                          (> (- now (:fetched-at q 0)) (:stale-time-ms q))))]
      (if stale?
        (let [query-config (get-in @registry [:queries k])
              query-fn (:query-fn query-config)]
          {:db (-> db
                   (assoc-in [:rfq/queries qid :fetching?] true)
                   (assoc-in [:rfq/queries qid :status] :loading)
                   (assoc-in [:rfq/queries qid :stale?] false))
           ;; merge effect map with user-defined query effect
           :fx [[:dispatch [:rfq/execute-query-effect k params query-fn]]]} )
        {:db db}))))
```

### 4. Execute Query Effect Event

```clojure
(rf/reg-event-fx
  :rfq/execute-query-effect
  (fn [_ [_ k params query-fn]]
    (query-fn params)))
```

### 5. Success/Failure

```clojure
(rf/reg-event-db
  :rfq/query-success
  (fn [db [_ k params data]]
    (let [qid (query-id k params)
          query-config (get-in @registry [:queries k])
          now (.now js/Date)
          tags (set ((or (:tags query-config) (constantly [])) params))]
      (-> db
          (assoc-in [:rfq/queries qid :status] :success)
          (assoc-in [:rfq/queries qid :data] data)
          (assoc-in [:rfq/queries qid :error] nil)
          (assoc-in [:rfq/queries qid :fetching?] false)
          (assoc-in [:rfq/queries qid :fetched-at] now)
          (assoc-in [:rfq/queries qid :stale?] false)
          (assoc-in [:rfq/queries qid :tags] tags)
          (assoc-in [:rfq/queries qid :stale-time-ms] (:stale-time-ms query-config))
          (assoc-in [:rfq/queries qid :cache-time-ms] (:cache-time-ms query-config))))))

(rf/reg-event-db
  :rfq/query-failure
  (fn [db [_ k params error]]
    (let [qid (query-id k params)]
      (-> db
          (assoc-in [:rfq/queries qid :status] :error)
          (assoc-in [:rfq/queries qid :error] error)
          (assoc-in [:rfq/queries qid :fetching?] false)))))
```

### 6. Mutation Success/Invalidation

```clojure
(rf/reg-event-fx
  :rfq/mutation-success
  (fn [{:keys [db]} [_ k params data]]
    (let [mutation-config (get-in @registry [:mutations k])
          invalidates ((or (:invalidates mutation-config) (constantly [])) params)]
      {:db (assoc-in db [:rfq/mutations (query-id k params) :status] :success)
       :fx [[:dispatch [:rfq/invalidate-tags invalidates]]]})))
```

### 7. Invalidate Tags

```clojure
(defn tag-match? [query-tags invalidation-tags]
  (boolean (some query-tags invalidation-tags)))

(rf/reg-event-fx
  :rfq/invalidate-tags
  (fn [{:keys [db]} [_ tags]]
    (let [queries (get db :rfq/queries {})
          now (.now js/Date)
          updated (reduce-kv
                    (fn [acc qid q]
                      (if (tag-match? (:tags q) tags)
                        (assoc acc qid (assoc q :stale? true))
                        (assoc acc qid q)))
                    {}
                    queries)
          active-queries (filter (fn [[_ q]] (:active? q)) updated)
          refetch-fx (mapv (fn [[qid q]]
                             [:dispatch [:rfq/refetch-query (first qid) (second qid)]])
                           active-queries)]
      {:db (assoc db :rfq/queries updated)
       :fx refetch-fx})))
```

### 8. Garbage Collection

```clojure
(rf/reg-event-db
  :rfq/garbage-collect
  (fn [db [_ now]]
    (update db :rfq/queries
            (fn [queries]
              (reduce-kv
                (fn [acc qid q]
                  (let [cache-ms (:cache-time-ms q)
                        fetched (:fetched-at q 0)
                        expired? (and cache-ms (> (- now fetched) cache-ms))]
                    (if (and expired? (not (:active? q)))
                      acc
                      (assoc acc qid q))))
                {}
                queries)))))
```

## Example Usage

### A. Simple List Query

```clojure
(rfq/reg-query
  :books/list
  {:query-fn (fn [_]
               {:http-xhrio {:method :get
                             :uri "/api/books"
                             :response-format (ajax/json-response-format {:keywords? true})
                             :on-success [:rfq/query-success :books/list {}]
                             :on-failure [:rfq/query-failure :books/list {}]}})
   :cache-time-ms (* 10 60 1000)
   :stale-time-ms 60000
   :tags (fn [_] [[:books :all]])})

(rf/dispatch [:rfq/ensure-query :books/list {}])
(rf/subscribe [:rfq/query :books/list {}])
```

### B. Parametrized Query + Invalidation

```clojure
(rfq/reg-query
  :book/detail
  {:query-fn (fn [{:keys [id]}]
               {:http-xhrio {:method :get
                             :uri (str "/api/books/" id)
                             :response-format (ajax/json-response-format {:keywords? true})
                             :on-success [:rfq/query-success :book/detail {:id id}]
                             :on-failure [:rfq/query-failure :book/detail {:id id}]}})
   :tags (fn [{:keys [id]}]
           [[:books :id id]])})

(rfq/reg-mutation
  :book/update
  {:mutation-fn (fn [{:keys [id payload]}]
                  {:http-xhrio {:method :put
                                :uri (str "/api/books/" id)
                                :params payload
                                :response-format (ajax/json-response-format {:keywords? true})
                                :on-success [:rfq/mutation-success :book/update {:id id}]
                                :on-failure [:rfq/mutation-failure :book/update {:id id}]}})
   :invalidates (fn [{:keys [id]}]
                  [[:books :id id] [:books :all]])})

(rf/dispatch [:rfq/ensure-query :book/detail {:id 10}])
(rf/dispatch [:rfq/execute-mutation :book/update {:id 10 :payload {:title "New"}}])
```

### C. Manual Invalidation + Refetch

```clojure
(rf/dispatch [:rfq/invalidate-tags [[:books :all]]])
```

## Open Questions

- How should `active?` be tracked? (subscription count vs explicit events)
- Should we support `select` functions in subscriptions for derived data?
- How to support optimistic updates (mutation lifecycle hooks)?
- How to integrate with `re-frame` interceptors for global behavior (logging, tracing)?

## Next Steps

- Implement a minimal `rfq.core` namespace with registry, events, subs.
- Build a tiny demo app for integration testing.
- Evaluate ergonomic helpers for `use-query`-style wrappers (if using re-frame hooks).
