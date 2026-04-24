# Mutation Lifecycle Hooks

Pass an opts map as the third argument to `execute-mutation` to hook into the mutation lifecycle:

```clojure
(rf/dispatch [::rfq/execute-mutation :todos/toggle {:id 5 :done true}
              {:on-start   [[:my-app/on-start-event]]
               :on-success [[:my-app/on-success-event]]
               :on-failure [[:my-app/on-failure-event]]}])
```

| Hook | When | Args conj'd onto each event vector |
|---|---|---|
| `:on-start` | Before the effect fires | `params` |
| `:on-success` | After mutation succeeds | `params`, `response-data` |
| `:on-failure` | After mutation fails | `params`, `error` |

Each hook is a vector of event vectors — all events in the vector are dispatched. Hooks are optional; omitting the opts map works exactly as before.

## Hook Handler Signatures

rfq **conj's its own args onto every hook event you register**. This is different from how day8/http-fx and most other re-frame HTTP effects work — those dispatch `(conj on-success-event response)`, appending only the response. rfq appends `params` **and** (for `:on-success`/`:on-failure`) the response or error.

If you pre-bind data in the hook event vector, those values sit *before* rfq's appended args:

```clojure
;; Dispatch:
[::rfq/execute-mutation :todos/toggle {:id 5}
 {:on-success [[:my/hook extra-1 extra-2]]}]

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
(rf/dispatch [::rfq/execute-mutation :todos/add {:title "x"}
              {:on-success [[:my/on-success some-data]]}])

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
      {:db       (assoc-in db [:snapshots qid] old)           ;; save snapshot
       :dispatch [::rfq/set-query-data :todos/list {} new]}))) ;; patch cache

(rf/reg-event-fx :todos/rollback
  (fn [{:keys [db]} [_ _params _error]]
    (let [qid [:todos/list {}]
          old (get-in db [:snapshots qid])]
      {:db       (update db :snapshots dissoc qid)
       :dispatch [::rfq/set-query-data :todos/list {} old]}))) ;; restore snapshot

;; 2. Dispatch mutation with hooks
(rf/dispatch [::rfq/execute-mutation :todos/toggle {:id 5 :done true}
              {:on-start   [[:todos/optimistic-toggle]]
               :on-failure [[:todos/rollback]]}])
```

The checkbox toggles instantly. If the server rejects, the snapshot is restored. No library magic — just re-frame events and data.

> **Race condition note:** If a query has active polling or an in-flight refetch, the refetch response could briefly overwrite your optimistic data before the mutation completes. In practice this race is rare and self-correcting — the mutation's `:invalidates` triggers a fresh refetch with correct server data immediately after success. If you need to guard against it, see the cancellation recipe below.

## Advanced: Cancelling In-Flight Requests

TanStack Query solves the optimistic update race with `cancelQueries`, which aborts in-flight HTTP requests via `AbortController`. Since re-frame-query is transport-agnostic, cancellation lives in your transport layer — not in the library. Here's the pattern:

```clojure
;; 1. Store AbortControllers per query in your transport layer
(defonce abort-controllers (atom {}))

(rf/reg-fx :http-xhrio
  (fn [{:keys [method url body on-success on-failure abort-key]}]
    (let [controller (js/AbortController.)
          signal     (.-signal controller)]
      (when abort-key
        (swap! abort-controllers assoc abort-key controller))
      (-> (js/fetch url (clj->js {:method  (name method)
                                   :headers {"Content-Type" "application/json"}
                                   :signal  signal
                                   :body    (some-> body clj->js js/JSON.stringify)}))
          (.then  #(when (.-ok %) ...dispatch on-success...))
          (.catch #(when-not (.-aborted signal)  ;; silently drop aborted requests
                    ...dispatch on-failure...))))))

;; 2. Register an effect that aborts a request by key
(rf/reg-fx :abort-request
  (fn [abort-key]
    (when-let [controller (get @abort-controllers abort-key)]
      (.abort controller)
      (swap! abort-controllers dissoc abort-key))))

;; 3. Tag queries with an abort-key so they can be cancelled
(rfq/reg-query :todos/list
  {:query-fn (fn [_] {:method :get :url "/api/todos"
                       :abort-key [:todos/list {}]})
   :tags     (constantly [[:todos]])})

;; 4. In your on-start hook, abort the in-flight refetch before patching
(rf/reg-event-fx :todos/optimistic-toggle
  (fn [{:keys [db]} [_ {:keys [id done]}]]
    (let [qid [:todos/list {}]
          old (get-in db [:re-frame.query/queries qid :data])
          new (mapv #(if (= (:id %) id) (assoc % :done done) %) old)]
      {:db            (assoc-in db [:snapshots qid] old)
       :abort-request qid                                  ;; cancel in-flight refetch
       :dispatch      [::rfq/set-query-data :todos/list {} new]})))
```

The aborted fetch silently drops (no `on-failure` dispatch), the optimistic data stays intact, and the mutation's `:invalidates` triggers a correct refetch when the server responds.
