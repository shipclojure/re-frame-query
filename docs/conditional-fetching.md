# Conditional Fetching (Skip)

Use `:skip? true` in the subscription opts to prevent a query from firing. This is useful for **dependent queries** — where query B needs data from query A before it can fetch.

```clojure
(defn user-todos []
  (let [{:keys [data]}   @(rf/subscribe [::rfq/query :user/current {}])
        user-id          (:id data)
        {:keys [status]} @(rf/subscribe [::rfq/query :user/todos {:user-id user-id}
                                         {:skip? (nil? user-id)}])]
    (case status
      :idle    [:div "Waiting for user..."]
      :loading [:div "Loading todos..."]
      :success [:ul ...])))
```

When `:skip?` is `true`:
- No fetch is triggered
- The query is not marked active
- Polling does not start (even if `:polling-interval-ms` is set)
- The subscription returns `{:status :idle :data nil :error nil :fetching? false :stale? true}`

When the component re-renders with `:skip? false` (or without the `:skip?` key), the query fires automatically.
