# Example App Reference

Two complete example apps in [`examples/`](https://github.com/shipclojure/re-frame-query/tree/main/examples). Read these files for working patterns.

## Effect Adapters

**HTTP (js/fetch + AbortController):**
[`examples/reagent-app/src/example/reagent_app/http_fx.cljs`](https://github.com/shipclojure/re-frame-query/blob/main/examples/reagent-app/src/example/reagent_app/http_fx.cljs)

Shows `:http-xhrio` and `:abort-request` re-frame effects — JSON fetch with abort support for optimistic update cancellation.

**WebSocket:**
[`examples/reagent-app/src/example/reagent_app/ws_fx.cljs`](https://github.com/shipclojure/re-frame-query/blob/main/examples/reagent-app/src/example/reagent_app/ws_fx.cljs)

Shows `:ws-send` effect for queries/mutations over WebSocket.

## Query & Mutation Registration

**Incremental style (reg-query / reg-mutation):**
[`examples/reagent-app/src/example/reagent_app/queries.cljs`](https://github.com/shipclojure/re-frame-query/blob/main/examples/reagent-app/src/example/reagent_app/queries.cljs)

All registrations in one file: basic CRUD, polling, dependent queries, optimistic, infinite scroll, WebSocket (per-query `:effect-fn` override).

**Declarative style (init!):**
[`examples/uix-app/src/example/uix_app/queries.cljs`](https://github.com/shipclojure/re-frame-query/blob/main/examples/uix-app/src/example/uix_app/queries.cljs)

Same queries/mutations via `rfq/init!` one-shot, with WebSocket and infinite scroll added incrementally via `reg-query`/`reg-mutation` after init.

## View Patterns by Feature

Reagent views in [`examples/reagent-app/src/example/reagent_app/views/`](https://github.com/shipclojure/re-frame-query/tree/main/examples/reagent-app/src/example/reagent_app/views), UIx views in [`examples/uix-app/src/example/uix_app/views/`](https://github.com/shipclojure/re-frame-query/tree/main/examples/uix-app/src/example/uix_app/views).

| File | Pattern |
|---|---|
| [`views/basic.cljs`](https://github.com/shipclojure/re-frame-query/blob/main/examples/reagent-app/src/example/reagent_app/views/basic.cljs) | `::rfq/query` subscription, CRUD mutations, pagination, tag invalidation |
| [`views/polling.cljs`](https://github.com/shipclojure/re-frame-query/blob/main/examples/reagent-app/src/example/reagent_app/views/polling.cljs) | Query-level `:polling-interval-ms`, live server stats |
| [`views/dependent.cljs`](https://github.com/shipclojure/re-frame-query/blob/main/examples/reagent-app/src/example/reagent_app/views/dependent.cljs) | `:skip?` option — fetch user first, then favorites with user-id |
| [`views/prefetching.cljs`](https://github.com/shipclojure/re-frame-query/blob/main/examples/reagent-app/src/example/reagent_app/views/prefetching.cljs) | `rfq/prefetch` on hover, instant cache hit on navigate |
| [`views/mutations.cljs`](https://github.com/shipclojure/re-frame-query/blob/main/examples/reagent-app/src/example/reagent_app/views/mutations.cljs) | `::rfq/execute-mutation` with lifecycle hooks, mutation status tracking |
| [`views/websocket.cljs`](https://github.com/shipclojure/re-frame-query/blob/main/examples/reagent-app/src/example/reagent_app/views/websocket.cljs) | Queries/mutations using `:ws-send` effect via per-query `:effect-fn` |
| [`views/optimistic.cljs`](https://github.com/shipclojure/re-frame-query/blob/main/examples/reagent-app/src/example/reagent_app/views/optimistic.cljs) | Snapshot → `set-query-data` patch → rollback on failure, `:abort-request` for race conditions |
| [`views/infinite.cljs`](https://github.com/shipclojure/re-frame-query/blob/main/examples/reagent-app/src/example/reagent_app/views/infinite.cljs) | `::rfq/infinite-query`, `fetch-next-page`, per-user params, mutation invalidation with sequential re-fetch |

## MSW Mock Handlers

[`examples/reagent-app/src/mocks/handlers.js`](https://github.com/shipclojure/re-frame-query/blob/main/examples/reagent-app/src/mocks/handlers.js)

In-memory CRUD API with cursor-based feed pagination, per-user data, configurable failure mode for optimistic update testing.
