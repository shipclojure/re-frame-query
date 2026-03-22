# Effect Overrides & Custom Callbacks

## Per-Query Effect Override

For queries that use a different transport (e.g., WebSocket instead of HTTP), provide a per-query `:effect-fn`:

```clojure
(rfq/reg-query :chat/messages
  {:query-fn  (fn [{:keys [room-id]}]
                {:channel (str "room:" room-id)
                 :event   "get-messages"})
   :effect-fn (fn [request on-success on-failure]
                {:ws-send (assoc request
                            :on-success on-success
                            :on-failure on-failure)})})
```

## Custom Success/Failure Callbacks

Need to run your own logic on success or failure? Extend the `on-success` / `on-failure` vectors in your `effect-fn`:

```clojure
;; Global — all queries/mutations dispatch ::my-app/on-success after the library handler
(rfq/set-default-effect-fn!
  (fn [request on-success on-failure]
    {:http (assoc request
             :on-success (into on-success [::my-app/on-success])
             :on-failure (into on-failure [::my-app/on-failure]))}))

;; Per-query — only this query dispatches a custom event
(rfq/reg-query :books/list
  {:query-fn  (fn [_] {:method :get :url "/api/books"})
   :effect-fn (fn [request on-success on-failure]
                {:http (assoc request
                         :on-success (into on-success [::books-loaded])
                         :on-failure on-failure)})})
```

Since `on-success` and `on-failure` are plain vectors, you have full control — append events, wrap them, or replace them entirely.
