# re-frame-query API Quick Reference

## Registration

```clojure
;; Declarative (one-shot)
(rfq/init!
  {:default-effect-fn (fn [request on-success on-failure]
                        {:http (assoc request :on-success on-success :on-failure on-failure)})
   :queries    {k query-config ...}
   :mutations  {k mutation-config ...}})

;; Incremental
(rfq/set-default-effect-fn! effect-fn)
(rfq/reg-query k config)
(rfq/reg-mutation k config)
```

## Query Config Keys

| Key | Required | Description |
|---|---|---|
| `:query-fn` | ✅ | `(fn [params] -> request-map)` |
| `:stale-time-ms` | | ms before data is stale |
| `:cache-time-ms` | | ms before inactive query GC'd (default 5min) |
| `:tags` | | `(fn [params] -> [[tag ...] ...])` |
| `:effect-fn` | | per-query effect adapter (overrides global) |
| `:polling-interval-ms` | | auto-refetch interval |
| `:transform-response` | | `(fn [data params] -> data')` |
| `:transform-error` | | `(fn [error params] -> error')` |
| `:infinite` | | `{:initial-cursor val :get-next-cursor (fn [resp] -> cursor-or-nil)}` |
| `:max-pages` | | sliding window cap for infinite queries |

## Mutation Config Keys

| Key | Required | Description |
|---|---|---|
| `:mutation-fn` | ✅ | `(fn [params] -> request-map)` |
| `:invalidates` | | `(fn [params] -> [[tag ...] ...])` |
| `:effect-fn` | | per-mutation effect adapter |
| `:transform-response` | | `(fn [data params] -> data')` |
| `:transform-error` | | `(fn [error params] -> error')` |

## Events

```clojure
[::rfq/ensure-query k params]           ;; fetch if stale/absent
[::rfq/refetch-query k params]          ;; force refetch
[::rfq/execute-mutation k params]       ;; run mutation
[::rfq/execute-mutation k params opts]  ;; with lifecycle hooks
[::rfq/set-query-data k params data]    ;; direct cache write
[::rfq/invalidate-tags tags]            ;; invalidate + refetch active
[::rfq/reset-api-state]                 ;; clear all state + timers
[::rfq/reset-mutation k params]         ;; clear mutation to idle
[::rfq/fetch-next-page k params]        ;; infinite: append next page
[::rfq/mark-active k params]            ;; manual lifecycle
[::rfq/mark-inactive k params]          ;; manual lifecycle
```

## Subscriptions

```clojure
;; Effectful (triggers fetch + lifecycle)
[::rfq/query k params]                  ;; -> full query state
[::rfq/query k params opts]             ;; opts: {:polling-interval-ms N, :skip? bool}
[::rfq/infinite-query k params]         ;; -> infinite query state

;; Passive (pure read, no side effects)
[::rfq/query-state k params]            ;; same shape as ::rfq/query
[::rfq/infinite-query-state k params]   ;; same shape as ::rfq/infinite-query

;; Derived (no fetch, depend on ::rfq/query)
[::rfq/query-data k params]             ;; -> :data
[::rfq/query-status k params]           ;; -> :status
[::rfq/query-fetching? k params]        ;; -> :fetching?
[::rfq/query-error k params]            ;; -> :error
[::rfq/mutation k params]               ;; -> mutation state
[::rfq/mutation-status k params]        ;; -> :status
```

## Query State Shape

```clojure
{:status     :idle | :loading | :success | :error
 :data       <response>
 :error      <error>
 :fetching?  bool    ;; network in-flight
 :stale?     bool    ;; computed from stale-time-ms
 :active?    bool    ;; component subscribed
 :fetched-at <ms>
 :tags       #{[tag-tuple] ...}}
```

## Infinite Query Data Shape

```clojure
{:pages       [page1-resp page2-resp ...]
 :page-params [cursor1 cursor2 ...]
 :has-next?   bool
 :next-cursor val}
;; + :fetching-next? on the query map
```

## Mutation Lifecycle Hooks

```clojure
(rf/dispatch [::rfq/execute-mutation :todos/toggle {:id 5 :done true}
              {:on-start   [[:my/on-start-event]]    ;; receives params
               :on-success [[:my/on-success-event]]  ;; receives params, data
               :on-failure [[:my/on-failure-event]]   ;; receives params, error
               }])
```

## app-db Layout

```clojure
{:re-frame.query/queries   {[k params] -> query-state-map}
 :re-frame.query/mutations {[k params] -> {:status :data :error}}}
```

## Source Files

| File | Purpose |
|---|---|
| [`src/re_frame/query.cljc`](https://github.com/shipclojure/re-frame-query/blob/main/src/re_frame/query.cljc) | Public API namespace (require this) |
| [`src/re_frame/query/events.cljc`](https://github.com/shipclojure/re-frame-query/blob/main/src/re_frame/query/events.cljc) | All event handlers |
| [`src/re_frame/query/subs.cljc`](https://github.com/shipclojure/re-frame-query/blob/main/src/re_frame/query/subs.cljc) | All subscriptions |
| [`src/re_frame/query/registry.cljc`](https://github.com/shipclojure/re-frame-query/blob/main/src/re_frame/query/registry.cljc) | Query/mutation registration storage |
| [`src/re_frame/query/gc.cljc`](https://github.com/shipclojure/re-frame-query/blob/main/src/re_frame/query/gc.cljc) | Garbage collection timers |
| [`src/re_frame/query/polling.cljc`](https://github.com/shipclojure/re-frame-query/blob/main/src/re_frame/query/polling.cljc) | Polling interval management |
| [`src/re_frame/query/util.cljc`](https://github.com/shipclojure/re-frame-query/blob/main/src/re_frame/query/util.cljc) | Shared utilities (query-id, stale?, etc.) |
