# Example App Reference

Two complete example apps in `examples/`. Read these files for working patterns.

## Effect Adapters

**HTTP (js/fetch + AbortController):**
`examples/reagent-app/src/example/reagent_app/http_fx.cljs`

Shows `:http` and `:abort-request` re-frame effects — JSON fetch with abort support for optimistic update cancellation.

**WebSocket:**
`examples/reagent-app/src/example/reagent_app/ws_fx.cljs`

Shows `:ws-send` effect for queries/mutations over WebSocket.

## Query & Mutation Registration

**Incremental style (reg-query / reg-mutation):**
`examples/reagent-app/src/example/reagent_app/queries.cljs`

All registrations in one file: basic CRUD, polling, dependent queries, optimistic, infinite scroll, WebSocket (per-query `:effect-fn` override).

**Declarative style (init!):**
`examples/uix-app/src/example/uix_app/queries.cljs`

Same queries/mutations via `rfq/init!` one-shot, with WebSocket and infinite scroll added incrementally via `reg-query`/`reg-mutation` after init.

## View Patterns by Feature

All views in `examples/reagent-app/src/example/reagent_app/views/` (Reagent) and `examples/uix-app/src/example/uix_app/views/` (UIx).

| File | Pattern |
|---|---|
| `views/basic.cljs` | `::rfq/query` subscription, CRUD mutations, pagination, tag invalidation |
| `views/polling.cljs` | Query-level `:polling-interval-ms`, live server stats |
| `views/dependent.cljs` | `:skip?` option — fetch user first, then favorites with user-id |
| `views/prefetching.cljs` | `rfq/prefetch` on hover, instant cache hit on navigate |
| `views/mutations.cljs` | `::rfq/execute-mutation` with lifecycle hooks, mutation status tracking |
| `views/websocket.cljs` | Queries/mutations using `:ws-send` effect via per-query `:effect-fn` |
| `views/optimistic.cljs` | Snapshot → `set-query-data` patch → rollback on failure, `:abort-request` for race conditions |
| `views/infinite.cljs` | `::rfq/infinite-query`, `fetch-next-page`, per-user params, mutation invalidation with sequential re-fetch |

## MSW Mock Handlers

`examples/reagent-app/src/mocks/handlers.js`

In-memory CRUD API with cursor-based feed pagination, per-user data, configurable failure mode for optimistic update testing.
