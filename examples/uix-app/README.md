# re-frame-query · UIx Example

A minimal [UIx](https://github.com/pitch-io/uix) app demonstrating **re-frame-query** features
with React hooks instead of Reagent's reactive atoms:

- **Queries** — declarative data fetching with automatic caching and stale detection
- **Mutations** — create, update, and delete operations with cache invalidation
- **Invalidation** — tag-based cache invalidation that triggers automatic refetching
- **Loading & error states** — built-in status tracking for every query and mutation
- **`uix.re-frame`** — UIx's built-in `use-subscribe` hook for tear-free re-frame reads

## Running the example

```bash
cd examples/uix-app
pnpm install        # only needed once — installs shadow-cljs + react
pnpm exec shadow-cljs watch demo
```

Then open [http://localhost:8720](http://localhost:8720) in your browser.

## What to try

1. **Browse books** — the list loads via a `:books/list` query with 30 s stale time.
2. **Click a book** — opens a detail view powered by a separate `:book/detail` query.
3. **Edit a title** — dispatches a `:books/update` mutation that invalidates the cache.
4. **Delete a book** — dispatches `:books/delete` and returns to the list.
5. **Add a book** — uses `:books/create`; the list auto-refetches via tag invalidation.
6. **Manual invalidate** — click "🔄 Invalidate All Books" to force all book queries stale.

## UIx ↔ re-frame bridge

UIx provides a dedicated `uix.re-frame` namespace with a `use-subscribe` hook that
bridges re-frame subscriptions to React via `useSyncExternalStore`:

```clojure
(ns my-app.views
  (:require [uix.core :refer [defui $]]
            [uix.re-frame :as urf]
            [re-frame.core :as rf]))

(defui my-component []
  (let [query (urf/use-subscribe [:re-frame.query/query :books/list {}])]
    (case (:status query)
      :loading ($ :div "Loading…")
      :success ($ :div (pr-str (:data query)))
      ($ :div "Idle"))))
```

## Project structure

```
src/example/uix_app/
├── core.cljs       Entry point — mounts the UIx root
├── mock_api.cljs   In-memory mock API + :mock-http effect handler
├── queries.cljs    Query & mutation registrations (rfq/reg-query, rfq/reg-mutation)
└── views.cljs      UIx components (book list, detail, form, invalidate button)
```
