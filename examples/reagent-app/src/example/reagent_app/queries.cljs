(ns example.reagent-app.queries
  "Query and mutation registrations for the demo book app.
   Uses :http effect (js/fetch) — requests are intercepted by MSW."
  (:require
   [re-frame.query :as rfq]))

;; ---------------------------------------------------------------------------
;; Effect adapter — configure once, used by all queries and mutations
;; ---------------------------------------------------------------------------

(rfq/set-default-effect-fn!
 (fn [request on-success on-failure]
   {:http (assoc request :on-success on-success :on-failure on-failure)}))

;; ---------------------------------------------------------------------------
;; Queries — Basic CRUD
;; ---------------------------------------------------------------------------

(rfq/reg-query :books/list
  {:query-fn (fn [_params]
               {:method :get
                :url "/api/books"})
   :stale-time-ms 30000
   :cache-time-ms 300000
   :tags (constantly [[:books :all]])})

(rfq/reg-query :books/page
  {:query-fn (fn [{:keys [page per-page]}]
               {:method :get
                :url (str "/api/books?page=" page "&per_page=" (or per-page 3))})
   :stale-time-ms 30000
   :cache-time-ms 300000
   :tags (fn [{:keys [page]}]
           [[:books :all]
            [:books :page page]])})

(rfq/reg-query :book/detail
  {:query-fn (fn [{:keys [id]}]
               {:method :get
                :url (str "/api/books/" id)})
   :stale-time-ms 30000
   :cache-time-ms 300000
   :tags (fn [{:keys [id]}]
           [[:books :id id]
            [:books :all]])})

;; ---------------------------------------------------------------------------
;; Queries — Polling
;; ---------------------------------------------------------------------------

(rfq/reg-query :server/stats
  {:query-fn (fn [_] {:method :get :url "/api/server-stats"})
   :stale-time-ms 1000
   :polling-interval-ms 2000})

;; ---------------------------------------------------------------------------
;; Queries — Dependent queries
;; ---------------------------------------------------------------------------

(rfq/reg-query :user/current
  {:query-fn (fn [_] {:method :get :url "/api/me"})
   :stale-time-ms 60000})

(rfq/reg-query :user/favorites
  {:query-fn (fn [{:keys [user-id]}]
               {:method :get
                :url (str "/api/users/" user-id "/favorites")})
   :stale-time-ms 30000})

;; ---------------------------------------------------------------------------
;; Mutations — Basic CRUD
;; ---------------------------------------------------------------------------

(rfq/reg-mutation :books/create
  {:mutation-fn (fn [{:keys [title author]}]
                  {:method :post
                   :url "/api/books"
                   :body {:title title :author author}})
   :invalidates (constantly [[:books :all]])})

(rfq/reg-mutation :books/update
  {:mutation-fn (fn [{:keys [id title author]}]
                  {:method :put
                   :url (str "/api/books/" id)
                   :body (cond-> {}
                           title (assoc :title title)
                           author (assoc :author author))})
   :invalidates (fn [{:keys [id]}]
                  [[:books :id id]
                   [:books :all]])})

(rfq/reg-mutation :books/delete
  {:mutation-fn (fn [{:keys [id]}]
                  {:method :delete
                   :url (str "/api/books/" id)})
   :invalidates (fn [{:keys [id]}]
                  [[:books :id id]
                   [:books :all]])})

;; ---------------------------------------------------------------------------
;; Queries — Optimistic updates
;; ---------------------------------------------------------------------------

(rfq/reg-query :todos/list
  {:query-fn (fn [_] {:method :get
                      :url "/api/todos"
                      :abort-key [:todos/list {}]})
   :stale-time-ms 30000
   :tags (constantly [[:todos]])})

(rfq/reg-mutation :todos/toggle
  {:mutation-fn (fn [{:keys [id done fail-mode?]}]
                  {:method :put
                   :url (str "/api/todos/" id)
                   :body (cond-> {:done done}
                           fail-mode? (assoc :fail_mode true))})
   :invalidates (constantly [[:todos]])})

;; ---------------------------------------------------------------------------
;; Queries — WebSocket transport (per-query effect-fn override)
;; ---------------------------------------------------------------------------

(defn- ws-effect-fn
  "Effect adapter for WebSocket queries/mutations.
   Uses :ws-send effect instead of :http."
  [request on-success on-failure]
  {:ws-send (assoc request :on-success on-success :on-failure on-failure)})

(rfq/reg-query :ws/notifications
  {:query-fn (fn [_] {:channel "notifications:list"})
   :effect-fn ws-effect-fn
   :stale-time-ms 30000})

(rfq/reg-query :ws/latest-notification
  {:query-fn (fn [_] {:channel "notifications:latest"})
   :effect-fn ws-effect-fn
   :stale-time-ms 1000
   :polling-interval-ms 3000})

(rfq/reg-query :ws/chat-messages
  {:query-fn (fn [_] {:channel "chat:messages"})
   :effect-fn ws-effect-fn
   :stale-time-ms 5000
   :tags (constantly [[:chat :messages]])})

(rfq/reg-mutation :ws/chat-send
  {:mutation-fn (fn [{:keys [user text]}]
                  {:channel "chat:send"
                   :payload {:user user :text text}})
   :effect-fn ws-effect-fn
   :invalidates (constantly [[:chat :messages]])})

;; ---------------------------------------------------------------------------
;; Queries — Infinite scroll
;; ---------------------------------------------------------------------------

(rfq/reg-query :feed/items
  {:query-fn (fn [{:keys [user cursor]}]
               {:method :get
                :url (str "/api/feed?user=" (or user "alex")
                          "&cursor=" (or cursor 0)
                          "&limit=10")})
   :infinite {:initial-cursor 0
              :get-next-cursor (fn [resp] (:next_cursor resp))}
   :stale-time-ms 30000
   :tags (fn [{:keys [user]}] [[:feed] [:feed user]])})

(rfq/reg-mutation :feed/add-item
  {:mutation-fn (fn [{:keys [user title]}]
                  {:method :post
                   :url "/api/feed"
                   :body {:title title :user (or user "alex")}})
   :invalidates (fn [{:keys [user]}] [[:feed (or user "alex")]])})

;; ---------------------------------------------------------------------------
;; Mutations — Mutation lifecycle demo
;; ---------------------------------------------------------------------------

(rfq/reg-mutation :books/create-demo
  {:mutation-fn (fn [{:keys [title author]}]
                  {:method :post
                   :url "/api/books"
                   :body {:title title :author author}})
   :invalidates (constantly [[:books :all]])})
