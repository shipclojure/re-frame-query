# re-frame-query API Quick Reference

## Registration

```clojure
;; Declarative (one-shot)
(rfq/init!
  {:default-effect-fn (fn [request on-success on-failure]
                        {:http-xhrio (assoc request :on-success on-success :on-failure on-failure)})
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
| `:polling-interval-ms` | | auto-refetch interval (not supported for infinite queries) |
| `:polling-mode` | | `:skip` (default) skips tick if in-flight; `:force` always fires |
| `:transform-response` | | `(fn [data params] -> data')` |
| `:transform-error` | | `(fn [error params] -> error')` |
| `:infinite` | | `{:initial-cursor val :get-next-cursor fn}` — see below |
| `:max-pages` | | sliding window cap for infinite queries |

## Infinite Config Keys (inside `:infinite`)

| Key | Required | Description |
|---|---|---|
| `:initial-cursor` | ✅ | Starting cursor value |
| `:get-next-cursor` | ✅ | `(fn [page-response] -> cursor-or-nil)` |
| `:get-previous-cursor` | | `(fn [page-response] -> cursor-or-nil)` — enables backward pagination |

## Mutation Config Keys

| Key | Required | Description |
|---|---|---|
| `:mutation-fn` | ✅ | `(fn [params] -> request-map)` |
| `:invalidates` | | `(fn [params] -> [[tag ...] ...])` |
| `:effect-fn` | | per-mutation effect adapter |
| `:transform-response` | | `(fn [data params] -> data')` |
| `:transform-error` | | `(fn [error params] -> error')` |

## Payload Form

Every event/sub takes **one map**: `{:query k :params p ...}` for queries, `{:mutation k :params p ...}` for mutations. `:params` is optional (defaults to `{}` for the cache key). Options (`:skip?`, `:polling-interval-ms`, `:sub-id`, mutation hooks) are top-level keys of the same map.

The positional form (`[::rfq/query k params opts]`, `[::rfq/execute-mutation k params opts]`) is legacy — still supported with no deprecation, but always write the map form.

Loud errors on the map form:
- Missing/non-keyword `:query` / `:mutation` (e.g. bare params `[::rfq/ensure-query {:page 1}]`) → throws `expects a keyword under :query …`
- `:on-start` / `:on-success` / `:on-failure` on any **query** event or sub → throws `does not accept … per-call lifecycle hooks exist on mutations only …`

## Events

```clojure
[::rfq/ensure-query {:query k :params p}]                         ;; fetch if stale/absent (regular queries only)
[::rfq/refetch-query {:query k :params p}]                        ;; force refetch
[::rfq/mark-active {:query k :params p}]                          ;; manual lifecycle
[::rfq/mark-active {:query k :params p :polling-interval-ms N :sub-id kw}]
[::rfq/mark-inactive {:query k :params p}]                        ;; manual lifecycle
[::rfq/mark-inactive {:query k :params p :sub-id kw}]
[::rfq/cancel-query {:query k :params p}]                         ;; drop in-flight responses, clear :fetching?
[::rfq/execute-mutation {:mutation k :params p}]                  ;; run mutation
[::rfq/execute-mutation {:mutation k :params p
                         :on-start [...] :on-success [...] :on-failure [...]}]
[::rfq/set-query-data {:query k :params p :data d}]               ;; direct cache write
[::rfq/invalidate-tags {:tags tags}]                              ;; invalidate + refetch only matching active queries
[::rfq/reset-api-state]                                           ;; clear all state + timers
[::rfq/reset-mutation {:mutation k :params p}]                    ;; clear mutation to idle
[::rfq/ensure-infinite-query {:query k :params p}]                ;; fetch first page if absent/stale
[::rfq/fetch-next-page {:query k :params p}]                      ;; infinite: append next page
[::rfq/fetch-previous-page {:query k :params p}]                  ;; infinite: prepend previous page (requires :get-previous-cursor)
[::rfq/refetch-infinite-query {:query k :params p}]               ;; infinite: sequential re-fetch from page 1
```

Helper fns (dispatch/subscribe for you): `(rfq/prefetch {:query k :params p})`, `(rfq/set-query-data {:query k :params p :data d})`, `(rfq/cancel-query {:query k :params p})`, `(rfq/fetch-next-page {:query k :params p})`, `(rfq/fetch-previous-page {:query k :params p})`, `(rfq/infinite-query-data {:query k :params p})`.

## Subscriptions

```clojure
;; Effectful (triggers fetch + lifecycle)
[::rfq/query {:query k :params p}]                                ;; -> full query state
[::rfq/query {:query k :params p :polling-interval-ms N :skip? bool}]
[::rfq/infinite-query {:query k :params p}]                       ;; -> infinite query state

;; Passive (pure read, no side effects — prefer these for manual lifecycle)
[::rfq/query-state {:query k :params p}]                          ;; same shape as ::rfq/query
[::rfq/infinite-query-state {:query k :params p}]                 ;; same shape as ::rfq/infinite-query

;; Derived (no fetch, depend on effectful ::rfq/query)
[::rfq/query-data {:query k :params p}]                           ;; -> :data
[::rfq/query-status {:query k :params p}]                         ;; -> :status
[::rfq/query-fetching? {:query k :params p}]                      ;; -> :fetching?
[::rfq/query-error {:query k :params p}]                          ;; -> :error
[::rfq/infinite-query-data {:query k :params p}]                  ;; -> infinite :data only
[::rfq/mutation {:mutation k :params p}]                          ;; -> mutation state
[::rfq/mutation-status {:mutation k :params p}]                   ;; -> :status
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
 :has-prev?   bool     ;; present when :get-previous-cursor is configured
 :next-cursor val
 :prev-cursor val}
;; + :fetching-next? / :fetching-prev? on the query map
```

## Mutation Lifecycle Hooks

```clojure
(rf/dispatch [::rfq/execute-mutation {:mutation   :todos/toggle
                                      :params     {:id 5 :done true}
                                      :on-start   [:my/on-start-event]    ;; receives params
                                      :on-success [:my/on-success-event]  ;; receives params, data
                                      :on-failure [:my/on-failure-event]  ;; receives params, error
                                      }])

;; Several events per hook — pass a vector of event vectors
{:on-success [[:my/refresh-badge] [:my/toast "Saved"]]}
```

Handler signatures — rfq's args are appended **after** any pre-bound args in the hook event vector:

```clojure
(fn [_ [_ params]] ...)                ;; :on-start
(fn [_ [_ params data]] ...)           ;; :on-success
(fn [_ [_ params error]] ...)          ;; :on-failure
(fn [_ [_ pre-1 pre-2 params data]] ...) ;; if dispatched with [:hook pre-1 pre-2]
```

> ⚠️ **Not the same as day8/http-fx.** http-fx appends only `response` to `:on-success`; rfq appends `params` then `data`. Copy/pasted http-fx success handlers will silently bind the mutation-params map to the `response` slot and drop the real response. See [docs/lifecycle-hooks.md](https://github.com/shipclojure/re-frame-query/blob/main/docs/lifecycle-hooks.md) for the full migration checklist.

## Query Lifecycle Events

Queries have **no per-call hooks** — observe via global interceptors on `::rfq/query-success`, `::rfq/query-failure`, `::rfq/infinite-page-success`, `::rfq/infinite-page-failure`. Inside the interceptor, use `(rfq/parse-result-event event-vec)` to get a map (`{:event-id :k :params (:data | :error) [:mode]}`) instead of positionally destructuring. Returns `nil` for non-matching events.

See SKILL.md → "Observing Query Lifecycle" for the full pattern.

## `re-frame.query.db` — Inline Cache Operations

```clojure
(require '[re-frame.query.db :as rfq-db])

(rfq-db/get-query      db k params)       ;; full cache entry or nil
(rfq-db/get-query-data db k params)       ;; :data or nil
(rfq-db/set-query-data db k params data)  ;; write data, mark :success + fresh
(rfq-db/remove-query   db qid)            ;; evict inactive query
(rfq-db/garbage-collect db)               ;; bulk evict all expired inactive queries
(rfq-db/garbage-collect db now-ms)
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
| [`src/re_frame/query/db.cljc`](https://github.com/shipclojure/re-frame-query/blob/main/src/re_frame/query/db.cljc) | Pure db → db cache operations |
| [`src/re_frame/query/registry.cljc`](https://github.com/shipclojure/re-frame-query/blob/main/src/re_frame/query/registry.cljc) | Query/mutation registration storage |
| [`src/re_frame/query/gc.cljc`](https://github.com/shipclojure/re-frame-query/blob/main/src/re_frame/query/gc.cljc) | Garbage collection timers |
| [`src/re_frame/query/polling.cljc`](https://github.com/shipclojure/re-frame-query/blob/main/src/re_frame/query/polling.cljc) | Polling interval management |
| [`src/re_frame/query/util.cljc`](https://github.com/shipclojure/re-frame-query/blob/main/src/re_frame/query/util.cljc) | Shared utilities (query-id, stale?, etc.) |
