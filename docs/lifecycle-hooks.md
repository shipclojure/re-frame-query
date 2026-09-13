# Lifecycle Hooks

re-frame-query exposes lifecycle information differently for mutations and queries:

- **Mutations** support explicit per-call hooks (`:on-start`, `:on-success`, `:on-failure`) passed as top-level keys of the `execute-mutation` payload map. They're scoped to a single action and commonly power optimistic updates.
- **Queries** are observed via **re-frame global interceptors** on the library's lifecycle events. This matches the fact that query fetches can originate from many places (subscriptions, navigation events, polling, tag invalidation, prefetches) — a per-call hook would silently miss most of them.

## Mutation Lifecycle Hooks

Add `:on-start` / `:on-success` / `:on-failure` keys to the `execute-mutation` payload map to hook into the mutation lifecycle:

```clojure
(rf/dispatch [::rfq/execute-mutation {:mutation   :todos/toggle
                                      :params     {:id 5 :done true}
                                      :on-start   [:my-app/on-start-event]
                                      :on-success [:my-app/on-success-event]
                                      :on-failure [:my-app/on-failure-event]}])
```

| Hook | When | Args conj'd onto each event vector |
|---|---|---|
| `:on-start` | Before the effect fires | `params` |
| `:on-success` | After mutation succeeds | `params`, `response-data` |
| `:on-failure` | After mutation fails | `params`, `error` |

Each hook takes an event vector. Hooks are optional; omitting the hook keys works exactly as before.

### Multiple events per hook

To dispatch several events from one hook, pass a vector of event vectors — all of them are dispatched:

```clojure
(rf/dispatch [::rfq/execute-mutation {:mutation   :todos/toggle
                                      :params     {:id 5 :done true}
                                      :on-success [[:my-app/refresh-badge]
                                                   [:my-app/toast "Saved"]]}])
```

### Hook Handler Signatures

rfq **conj's its own args onto every hook event you register**. This is different from how day8/http-fx and most other re-frame HTTP effects work — those dispatch `(conj on-success-event response)`, appending only the response. rfq appends `params` **and** (for `:on-success`/`:on-failure`) the response or error.

If you pre-bind data in the hook event vector, those values sit *before* rfq's appended args:

```clojure
;; Dispatch:
[::rfq/execute-mutation {:mutation   :todos/toggle
                         :params     {:id 5}
                         :on-success [:my/hook extra-1 extra-2]}]

;; Hook handler receives:
(fn [cofx [_ extra-1 extra-2 mutation-params response]] ...)
;;               ^^^^^^^ ^^^^^^^  ^^^^^^^^^^^^^^^^  ^^^^^^^^
;;               your pre-bound args  rfq args
```

### Signature cheat sheet

```clojure
;; No pre-bound args
(rf/reg-event-fx :my/on-success
  (fn [_ [_ params response]] ...))

(rf/reg-event-fx :my/on-failure
  (fn [_ [_ params error]] ...))

(rf/reg-event-fx :my/on-start
  (fn [_ [_ params]] ...))

;; With pre-bound args (e.g. a user-supplied callback fn)
(rf/dispatch [::rfq/execute-mutation {:mutation   :todos/add
                                      :params     {:title "x"}
                                      :on-success [:my/on-success some-data]}])

(rf/reg-event-fx :my/on-success
  (fn [_ [_ some-data params response]] ...))
```

### Why this design?

Hooks receive `params` so they can operate on the same input the mutation ran with — essential for optimistic-update snapshots keyed by the mutation input, rollback logic, and generic analytics/toast interceptors. Making `params` implicit (always passed) keeps hook events reusable across call sites without threading mutation inputs through the event vector manually.

## Optimistic Updates Recipe

Use lifecycle hooks + `set-query-data` to build optimistic updates in pure re-frame:

```clojure
;; 1. Register hook events — these are YOUR event handlers, not library code
(rf/reg-event-fx :todos/optimistic-toggle
  (fn [{:keys [db]} [_ {:keys [id done]}]]
    (let [qid  [:todos/list {}]
          old  (get-in db [:re-frame.query/queries qid :data])
          new  (mapv #(if (= (:id %) id) (assoc % :done done) %) old)]
      {:db       (assoc-in db [:snapshots qid] old)                        ;; save snapshot
       :dispatch [::rfq/set-query-data {:query :todos/list :data new}]}))) ;; patch cache

(rf/reg-event-fx :todos/rollback
  (fn [{:keys [db]} [_ _params _error]]
    (let [qid [:todos/list {}]
          old (get-in db [:snapshots qid])]
      {:db       (update db :snapshots dissoc qid)
       :dispatch [::rfq/set-query-data {:query :todos/list :data old}]}))) ;; restore snapshot

;; 2. Dispatch mutation with hooks
(rf/dispatch [::rfq/execute-mutation {:mutation   :todos/toggle
                                      :params     {:id 5 :done true}
                                      :on-start   [:todos/optimistic-toggle]
                                      :on-failure [:todos/rollback]}])
```

The checkbox toggles instantly. If the server rejects, the snapshot is restored. No library magic — just re-frame events and data.

> **Race condition note:** If a query has active polling or an in-flight refetch, the refetch response could briefly overwrite your optimistic data before the mutation completes. In practice this race is rare and self-correcting — the mutation's `:invalidates` triggers a fresh refetch with correct server data immediately after success. If you need to guard against it, dispatch [`::rfq/cancel-query`](#advanced-cancelling-in-flight-requests) alongside `set-query-data` — see below.

## Advanced: Cancelling In-Flight Requests

- **`rfq/cancel-query`** — a built-in, state-layer cancel. It supersedes whatever request is in flight for a query so its response is dropped the moment it lands, without touching your transport. Zero setup, works with any effect adapter.
- **Aborting the network request itself** — actually stopping the HTTP call (or websocket, etc.) so it doesn't run to completion. Since re-frame-query is transport-agnostic, this still lives in your transport layer, not in the library. Only worth the extra plumbing if the wasted request itself is a problem (bandwidth, server load), not just its effect on `app-db`.

### Built-in: `rfq/cancel-query`

Every fetch is stamped with a fresh `:request-id` when it starts. Calling `rfq/cancel-query` claims a new `:request-id` without issuing a request, so whatever response is still in flight no longer matches and is dropped on arrival — `:fetching?` clears immediately, and `:data`/`:status`/`:error` are left exactly as they are.

Dispatch it in the same `on-start` hook that patches the optimistic update:

```clojure
(rf/reg-event-fx :todos/optimistic-toggle
  (fn [{:keys [db]} [_ {:keys [id done]}]]
    (let [qid [:todos/list {}]
          old (get-in db [:re-frame.query/queries qid :data])
          new (mapv #(if (= (:id %) id) (assoc % :done done) %) old)]
      {:db (assoc-in db [:snapshots qid] old)
       :dispatch-n [[::rfq/cancel-query {:query :todos/list}]             ;; drop any in-flight response
                    [::rfq/set-query-data {:query :todos/list :data new}]]}))) ;; patch cache
```

Or call `re-frame.query.db/cancel-query` directly if you're already inside a `db -> db` handler and want to avoid the extra dispatch cycle. `rfq/cancel-query` is also useful on its own, with no cache write — e.g. abandoning a slow infinite re-fetch, or leaving a route for which a query is already in flight.

### Also aborting the network request

Plain `rfq/cancel-query` does **not** abort the network call — the request keeps running to completion, its response is just dropped at the state layer. If the wasted request itself is a problem (bandwidth, server load), also abort it in your transport layer. You no longer need to hand-roll an `:abort-key` through `query-fn` — `rfq/request-control` gives you the same `:query-id` re-frame-query itself uses, read straight off `on-success`:

```clojure
;; 1. Store AbortControllers per query in your transport layer
(defonce abort-controllers (atom {}))

(rf/reg-fx :http-xhrio
  (fn [{:keys [method url body on-success on-failure]}]
    (let [{:keys [query-id]} (rfq/request-control on-success)
          controller         (js/AbortController.)
          signal              (.-signal controller)]
      (when query-id
        (when-let [old (get @abort-controllers query-id)]
          (.abort old))                                    ;; a newer attempt supersedes the old one
        (swap! abort-controllers assoc query-id controller))
      (-> (js/fetch url (clj->js {:method  (name method)
                                  :headers {"Content-Type" "application/json"}
                                  :signal  signal
                                  :body    (some-> body clj->js js/JSON.stringify)}))
          (.then  #(when (.-ok %) ...dispatch on-success...))
          (.catch #(when-not (.-aborted signal)  ;; silently drop aborted requests
                    ...dispatch on-failure...))))))

;; 2. Register an effect that aborts a request by key on demand
(rf/reg-fx :abort-request
  (fn [query-id]
    (when-let [controller (get @abort-controllers query-id)]
      (.abort controller)
      (swap! abort-controllers dissoc query-id))))

;; 3. In your on-start hook, abort the in-flight refetch's network call
;;    *and* drop it at the state layer, then patch the cache
(rf/reg-event-fx :todos/optimistic-toggle
  (fn [{:keys [db]} [_ {:keys [id done]}]]
    (let [qid [:todos/list {}]
          old (get-in db [:re-frame.query/queries qid :data])
          new (mapv #(if (= (:id %) id) (assoc % :done done) %) old)]
      {:db            (assoc-in db [:snapshots qid] old)
       :abort-request qid                                                     ;; stop the network call
       :dispatch-n    [[::rfq/cancel-query {:query :todos/list}]              ;; clear :fetching?, drop the response
                       [::rfq/set-query-data {:query :todos/list :data new}]]}))) ;; patch cache
```

`qid` here is exactly `(util/query-id :todos/list {})` — the same value `rfq/request-control` reports as `:query-id` in step 1 — so the key you dispatch `:abort-request` with always matches what the adapter has stored, with no separate `:abort-key` to keep in sync.

The `:http-xhrio` adapter's own `(.abort old)` in step 1 also aborts an older attempt automatically the instant a newer one for the same `k`/`params` starts firing (e.g. an overlapping refetch), so step 2/3's explicit `:abort-request` is only needed for cancelling *before* a replacement request exists — like the optimistic-update case above.

Aborting the network call alone leaves `:fetching?` stuck `true` forever, since an aborted request fires neither `on-success` nor `on-failure` — that's why step 3 dispatches `::rfq/cancel-query` alongside `:abort-request` rather than relying on either alone.

## Observing Query Lifecycle

Queries don't have per-call `:on-start`/`:on-success`/`:on-failure` hooks. The reason is that a single query key can be fetched from many entry points in the same session — `ensure-query`, `refetch-query`, polling ticks, tag invalidations, prefetches, or the `::rfq/query` subscription — and most of those paths have no natural place to carry caller-supplied opts. Baking hooks into only some of them would be a footgun.

The map payload enforces this loudly: putting a hook key on any query event or subscription throws instead of being silently ignored, and the error points you at the interceptor lane below.

```clojure
(rf/dispatch [::rfq/ensure-query {:query :books/list :params {:page 1} :on-success [:my/loaded]}])
;; => ExceptionInfo: re-frame-query: :re-frame.query/ensure-query does not accept #{:on-success}
;;    — per-call lifecycle hooks exist on mutations only. To observe query lifecycles, register a
;;    re-frame global interceptor over the rfq result events and parse them with
;;    re-frame.query/parse-result-event (see docs/lifecycle-hooks.md).
```

Instead, observe the library's **lifecycle events** with a re-frame global interceptor. The events are stable and part of the public surface:

| Event | Carries | When |
|---|---|---|
| `[:re-frame.query/ensure-query k params]` | `k`, `params` | A fetch is about to start (not fired on cache hits) |
| `[:re-frame.query/refetch-query k params]` | `k`, `params` | A forced refetch is starting |
| `[:re-frame.query/query-success k params data]` | `k`, `params`, post-`:transform-response` data | Success, after `:db` commit |
| `[:re-frame.query/query-failure k params error]` | `k`, `params`, post-`:transform-error` error | Failure, after `:db` commit |

Because these fire regardless of which entry point triggered the fetch, a single interceptor will reliably observe *every* lifecycle transition for the queries you care about.

### Global interceptor (all queries)

Use `rfq/parse-result-event` to extract the event into a map, and
`re-frame.interceptor/update-effect` to enqueue dispatches via `:fx` —
keeping the interceptor a pure `context -> context` function:

```clojure
(require '[re-frame.interceptor :as rfi])

(rf/reg-global-interceptor
  (rf/->interceptor
    :id :my-app/query-telemetry
    :after
    (fn [context]
      (let [{:keys [event-id k params data error]}
            (rfq/parse-result-event (get-in context [:coeffects :event]))]
        (case event-id
          :re-frame.query/query-success
          (rfi/update-effect context :fx (fnil conj [])
                             [:dispatch [:analytics/query-succeeded k params]])

          :re-frame.query/query-failure
          (rfi/update-effect context :fx (fnil conj [])
                             [:dispatch [:analytics/query-failed k params error]])

          context)))))
```

The `:after` hook runs after the handler commits, so for `query-success` / `query-failure` the fresh data is already in `app-db` — any event you enqueue via `:fx` will see the updated state.

### Route-scoped interceptors

Interceptors don't have to live forever. Register on route enter, clear on route leave — the interceptor only sees events dispatched while it's installed, so there's no global pollution.

Do the registration and clearing in the route-enter/leave **functions** themselves (e.g. reitit's `:controllers` `:start`/`:stop`, or whatever your router calls before dispatching its enter/leave events). re-frame events should remain pure data; `reg-global-interceptor` and `clear-global-interceptor` are side effects, so they don't belong inside an event handler.

```clojure
(require '[re-frame.interceptor :as rfi])

(defn books-route-enter []
  (rf/reg-global-interceptor
    (rf/->interceptor
      :id :books/page-telemetry       ;; unique id used to uninstall later
      :after
      (fn [context]
        (let [{:keys [event-id k]}
              (rfq/parse-result-event (get-in context [:coeffects :event]))]
          (if (and (= k :books/list)
                   (#{:re-frame.query/query-success
                      :re-frame.query/query-failure} event-id))
            (rfi/update-effect context :fx (fnil conj [])
                               [:dispatch [:analytics/books-event event-id]])
            context)))))
  (rf/dispatch [::rfq/ensure-query {:query :books/list :params {:page 1}}]))

(defn books-route-leave []
  (rf/clear-global-interceptor :books/page-telemetry)
  (rf/dispatch [::rfq/mark-inactive {:query :books/list :params {:page 1}}]))

;; Wire into your router. With reitit:
;; {:name :books
;;  :controllers [{:start books-route-enter
;;                 :stop  books-route-leave}]}
```

Pair this with [polling's route enter/leave pattern](polling.md) — the two use the same lifecycle, so a single pair of route hooks can wire both fetching and observability.

### When you'd use this

- **Analytics / tracing** for query completion times across the app.
- **Toast-on-failure** policies that apply to a whole route or section.
- **Post-success cache syncing** — e.g. when `:books/list` succeeds, patch a derived `:books/count` query.
- **Debug logging** in development (this is exactly what [`rfq/enable-debug-logging!`](../src/re_frame/query.cljc) does — a global interceptor on all `:re-frame.query/*` events).

### What about before the fetch ("on-start")?

The `ensure-query` / `refetch-query` events themselves fire before the HTTP effect does. An `:after` interceptor on those sees the event *after* the `:db` update that marked the query `:loading`/`:fetching? true`, which is the natural "start" signal. If you need strict before-effect timing (rare), a `:before` interceptor sees the event even earlier — but in practice, `:loading` being in `app-db` is the observable contract most consumers want.
