# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## [Unreleased]

### Changed

- **`set-query-data` now marks the entry stale and preserves `:fetching?`** ⚠️ **Behavior change** — `::rfq/set-query-data` and `rfq-db/set-query-data` now write `:stale? true` (was `:stale? false`). Cache writes from your code are by definition unverified — the next `ensure-query` (or any active subscriber) will background-refetch to confirm. If a request is already in flight when `set-query-data` is called, `:fetching?` is preserved as `true` (was unconditionally cleared) so subscribers don't see a momentary "not fetching" lie. This makes the placeholder-data pattern work out of the box (see [issue #1](https://github.com/shipclojure/re-frame-query/issues/1)): seed `:todo/get` from `:todo/list` on route enter, dispatch `ensure-query`, and the user sees instant data while the real fetch runs in the background. Optimistic-update flows are unaffected in practice — the in-flight `:fetching?` guard dedupes requests, and the mutation's `:invalidates` triggers the same refetch you'd want anyway.

## [0.8.0] - 2026-04-26

### Added

- **`rfq/parse-result-event`** — utility that turns one of the four query result event vectors (`::rfq/query-success`, `::rfq/query-failure`, `::rfq/infinite-page-success`, `::rfq/infinite-page-failure`) into a map `{:event-id :k :params (:data | :error) [:mode]}`. Returns `nil` for any other event vector. Centralizes the rfq event-shape so users can implement future proof (non breaking change) interceptors of the lifecycle events. This is a band aid on the fact that these events should've been dispatched with map based args from the start (Oops).
- **`db/get-query`** — now has multi-arity support. `(get-query db qid)` called directly with the query-id & `(get-query db k params)` where qid is built inside

### Docs

- **Renamed `docs/mutation-hooks.md` → `docs/lifecycle-hooks.md`** and added a "Observing Query Lifecycle" section documenting the `reg-global-interceptor` pattern for query telemetry, analytics, and route-scoped side effects. Mutation hook content is unchanged. References in `README.md`, `docs/api-reference.md`, and the skill docs updated.
- **Updated ai skill** with the latest changes and patterns

## [0.7.0] - 2026-04-11

### Added

- **`::rfq/infinite-query-data`** — passive derived subscription returning just the `:data` field of an infinite query (`{:pages [...] :has-next? bool :has-prev? bool ...}`). Components subscribed here only re-render when pages actually change, not when `:fetching-next?`, `:fetching-prev?`, or `:stale?` toggle. Useful in stale-while-revalidate patterns.

### Fixed

- **`has-prev?` is now always present in infinite query data** — previously `has-prev?` was only assoc'd into the data map when `:get-previous-cursor` was configured, making `(:has-prev? data)` return nil (not false) for forward-only queries. It is now always included as `false` when no previous page exists. `prev-cursor` is still only included when `:get-previous-cursor` is configured.

## [0.6.0] - 2026-04-11

### Fixed

- **Derived subscriptions no longer trigger fetches** ⚠️ **Breaking change** — `::rfq/query-data`, `::rfq/query-status`, `::rfq/query-fetching?`, and `::rfq/query-error` were incorrectly depending on the effectful `::rfq/query` subscription, causing a fetch to be triggered whenever any of them were subscribed to. They now derive from the passive `::rfq/query-state`, matching their documented "no fetch" contract. If you were relying on these subscriptions to trigger fetches, replace them with `::rfq/query` or dispatch `::rfq/ensure-query` explicitly.

## [0.5.0] - 2026-04-07

### Added

- **Polling via `mark-active` / `mark-inactive` events** — `mark-active` now accepts an opts map with `:polling-interval-ms` (falls back to query config) and `:sub-id` (defaults to `:default`). `mark-inactive` stops the subscriber's poll. This makes event-based lifecycle a first-class citizen for polling — no effectful subscription required.
- **`get-query!` / `get-mutation!` in registry** — throwing variants of `get-query`/`get-mutation` that raise `ex-info` when the key is not registered, replacing the repeated `(or (get-query k) (throw ...))` pattern.

### Fixed

- **Polling no longer fires duplicate requests when a fetch is in-flight** — polling now dispatches `::rfq/poll-refetch` instead of `::rfq/refetch-query`. By default, if a query is already fetching when a poll tick fires, the tick is skipped — preventing stale-response races where a late T0 response overwrites a fresher T1 response. Set `:polling-mode :force` on the query config to restore the old behavior. Manual `refetch-query` calls remain unconditional.

### Changed

- **Polling logic moved from subscriptions to events** — the effectful `::rfq/query` subscription no longer manages polling directly. It passes opts through to `mark-active`/`mark-inactive`, which are now the single authority for polling lifecycle.
- **Event handlers use `registry/get-query!`** — all event handlers now use the throwing `get-query!` / `get-mutation!` variants, removing duplicated boilerplate.

## [0.4.0] - 2026-04-03

### Added

- **`re-frame.query.db` namespace** — new namespace of pure `db → db` functions for reading and transforming the query cache inline inside your own event handlers, without dispatching extra events. Import as `[re-frame.query.db :as rfq-db]`.
  - `get-query [db k params]` — full cache entry
  - `get-query-data [db k params]` — just `:data`
  - `set-query-data [db k params data]` — write to cache (`:success`, fresh)
  - `remove-query [db qid]` — evict one inactive query
  - `garbage-collect [db]` / `[db now]` — bulk eviction of expired inactive queries

### Changed

- **Event handlers delegate to `re-frame.query.db`** — the `set-query-data`, `remove-query`, and `garbage-collect` event handlers now call the pure db functions directly, eliminating duplicated logic.

## [0.3.1] - 2026-04-03

### Changed

- **`enable-debug-logging!` accepts options map** — pass `{:clj->js? false}` to skip `clj->js` conversion and log raw ClojureScript values instead. Useful when Chrome custom formatters (e.g. cljs-devtools) are enabled. Default behaviour (`clj->js? true`) is unchanged.

## [0.3.0] - 2026-04-02

### Fixed

- **`invalidate-tags` refetched all active queries** — previously, any tag invalidation triggered a refetch of every active query in the cache, regardless of whether its tags matched. Now only queries whose tags match the invalidation set are refetched, as intended. This eliminates N×M unnecessary network requests in apps with multiple active queries.

### Added

- **`ensure-query` rejects infinite queries** — dispatching `::rfq/ensure-query` on a query registered with `:infinite` config now throws with a clear error directing users to `::rfq/ensure-infinite-query`. Previously this would silently treat the query as a regular single-result query, producing incorrect cache state.
- **Bidirectional infinite queries (`fetch-previous-page`)** — infinite queries now support backward pagination via `:get-previous-cursor` in the `:infinite` config. Call `rfq/fetch-previous-page` or dispatch `::rfq/fetch-previous-page` to prepend pages. When `:max-pages` is set, prepending trims from the end (opposite of `fetch-next-page` which trims from the start), enabling a true sliding window in both directions. Queries without `:get-previous-cursor` are unchanged — no new keys appear in their data.

### Changed

- **Early validation in event handlers** — all event handlers (`ensure-query`, `refetch-query`, `execute-mutation`, `ensure-infinite-query`, `refetch-infinite-query`) now validate config at the top of the `let` binding using `(or (get-query k) (throw ...))`. Previously, some handlers destructured the config before validating it, allowing silent `nil` propagation.
- **`dispatch-hooks` always returns `[]`** — the internal `dispatch-hooks` helper now returns an empty vector instead of `nil` when no hooks are configured. This simplifies all mutation handler callsites by removing `fnil`/`seq` guard ceremony.
- **`idle-state` derived from `default-query`** — the subscription idle states in `subs.cljc` are now derived from `util/default-query` via `select-keys`, eliminating a duplicated source of truth that could silently diverge.
- **Test suite split into domain-specific namespaces** — the monolithic `query_test.cljc` (2600 lines) is now 6 focused files (`events_test`, `mutations_test`, `invalidation_test`, `gc_test`, `polling_test`, `infinite_test`) plus a shared `test_helpers` namespace.

## [0.2.0] - 2026-03-22

### Added

- **Passive subscriptions** — pure reads from `app-db` with no side effects
  - `::rfq/query-state` — same shape as `::rfq/query`, no fetching/lifecycle
  - `::rfq/infinite-query-state` — same shape as `::rfq/infinite-query`, no fetching/lifecycle
  - Use with manual lifecycle management via `::rfq/ensure-query`, `::rfq/mark-active`, `::rfq/mark-inactive` in navigation hooks

### Changed

- Extracted `resolve-query` and `resolve-infinite-query` pure functions in `subs.cljc` — shared by both effectful and passive subscriptions, eliminating duplicated logic

## [0.1.0] - 2026-03-22

Initial public release.

### Added

- **Core query system** — `reg-query`, `ensure-query`, `refetch-query`, `query-success`, `query-failure`
- **Mutation system** — `reg-mutation`, `execute-mutation`, `mutation-success`, `mutation-failure`, `reset-mutation`
- **`::rfq/query` subscription** — `reg-sub-raw` with automatic fetch, mark-active/inactive, polling, and GC lifecycle
- **Tag-based cache invalidation** — `invalidate-tags` marks matching queries stale and refetches active ones
- **Per-query garbage collection** — timer-based eviction of inactive queries after `cache-time-ms`
- **Polling** — per-query and per-subscriber intervals, lowest non-zero wins, auto-start/stop
- **Conditional fetching** — `:skip?` option on subscriptions for dependent queries
- **Prefetching** — `rfq/prefetch` to pre-populate cache before subscribing
- **Infinite queries** — cursor-based pagination with `:infinite` config, `::rfq/infinite-query` subscription, `fetch-next-page`, sequential re-fetch on invalidation, `:max-pages` sliding window
- **`transform-response` / `transform-error`** — per-query and per-mutation response transformation
- **`set-query-data`** — directly set cached query data for optimistic updates
- **`reset-api-state!`** — clear all query/mutation state and cancel all timers
- **Mutation lifecycle hooks** — `:on-start`, `:on-success`, `:on-failure` on `execute-mutation` opts map
- **`init!`** — declarative one-shot registry initialization (queries, mutations, default-effect-fn)
- **Per-query effect override** — `:effect-fn` on individual queries/mutations for WebSocket, GraphQL, etc.
- **Abort controller support** — `:abort-key` and `:abort-request` for cancelling in-flight requests
- Derived subscriptions: `query-data`, `query-status`, `query-fetching?`, `query-error`, `mutation`, `mutation-status`
- Debug logging via `rfq/enable-debug-logging!`
- Reagent and UIx example apps with 8 tabs each
- 80 unit tests (371 assertions), 60 Playwright e2e tests
- GitHub Actions CI with parallel unit + e2e jobs
