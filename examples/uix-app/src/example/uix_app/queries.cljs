(ns example.uix-app.queries
  "Query and mutation registrations for the demo book app.
   Uses rfq/init! to declare everything in one place.
   WebSocket queries are added incrementally via reg-query/reg-mutation
   to demonstrate that both approaches work together."
  (:require
   [re-frame.query :as rfq]))

;; ---------------------------------------------------------------------------
;; WebSocket effect adapter (defined before init! so it can be referenced)
;; ---------------------------------------------------------------------------

(defn- ws-effect-fn
  "Effect adapter for WebSocket queries/mutations.
   Uses :ws-send effect instead of :http."
  [request on-success on-failure]
  {:ws-send (assoc request :on-success on-success :on-failure on-failure)})

;; ---------------------------------------------------------------------------
;; Declarative registration — one init! call for all HTTP queries/mutations
;; ---------------------------------------------------------------------------

(rfq/init!
  {:default-effect-fn
   (fn [request on-success on-failure]
     {:http (assoc request :on-success on-success :on-failure on-failure)})

   :queries
   {;; Basic CRUD
    :books/list
    {:query-fn      (fn [_] {:method :get :url "/api/books"})
     :stale-time-ms 30000
     :cache-time-ms 300000
     :tags          (constantly [[:books :all]])}

    :books/page
    {:query-fn      (fn [{:keys [page per-page]}]
                      {:method :get
                       :url    (str "/api/books?page=" page "&per_page=" (or per-page 3))})
     :stale-time-ms 30000
     :cache-time-ms 300000
     :tags          (fn [{:keys [page]}]
                      [[:books :all]
                       [:books :page page]])}

    :book/detail
    {:query-fn      (fn [{:keys [id]}]
                      {:method :get
                       :url    (str "/api/books/" id)})
     :stale-time-ms 30000
     :cache-time-ms 300000
     :tags          (fn [{:keys [id]}]
                      [[:books :id id]
                       [:books :all]])}

    ;; Polling
    :server/stats
    {:query-fn            (fn [_] {:method :get :url "/api/server-stats"})
     :stale-time-ms       1000
     :polling-interval-ms 2000}

    ;; Dependent queries
    :user/current
    {:query-fn      (fn [_] {:method :get :url "/api/me"})
     :stale-time-ms 60000}

    :user/favorites
    {:query-fn      (fn [{:keys [user-id]}]
                      {:method :get
                       :url    (str "/api/users/" user-id "/favorites")})
     :stale-time-ms 30000}

    ;; Optimistic updates
    :todos/list
    {:query-fn      (fn [_] {:method    :get
                              :url       "/api/todos"
                              :abort-key [:todos/list {}]})
     :stale-time-ms 30000
     :tags          (constantly [[:todos]])}}

   :mutations
   {;; Basic CRUD
    :books/create
    {:mutation-fn (fn [{:keys [title author]}]
                    {:method :post
                     :url    "/api/books"
                     :body   {:title title :author author}})
     :invalidates (constantly [[:books :all]])}

    :books/update
    {:mutation-fn (fn [{:keys [id title author]}]
                    {:method :put
                     :url    (str "/api/books/" id)
                     :body   (cond-> {}
                               title  (assoc :title title)
                               author (assoc :author author))})
     :invalidates (fn [{:keys [id]}]
                    [[:books :id id]
                     [:books :all]])}

    :books/delete
    {:mutation-fn (fn [{:keys [id]}]
                    {:method :delete
                     :url    (str "/api/books/" id)})
     :invalidates (fn [{:keys [id]}]
                    [[:books :id id]
                     [:books :all]])}

    ;; Mutation lifecycle demo
    :books/create-demo
    {:mutation-fn (fn [{:keys [title author]}]
                    {:method :post
                     :url    "/api/books"
                     :body   {:title title :author author}})
     :invalidates (constantly [[:books :all]])}

    ;; Optimistic updates
    :todos/toggle
    {:mutation-fn (fn [{:keys [id done fail-mode?]}]
                    {:method :put
                     :url    (str "/api/todos/" id)
                     :body   (cond-> {:done done}
                               fail-mode? (assoc :fail_mode true))})
     :invalidates (constantly [[:todos]])}}})

;; ---------------------------------------------------------------------------
;; Incremental registration — WebSocket queries added after init!
;; Demonstrates that reg-query/reg-mutation work alongside init!
;; (e.g. for lazy-loaded modules or alternative transports)
;; ---------------------------------------------------------------------------

(rfq/reg-query :ws/notifications
  {:query-fn      (fn [_] {:channel "notifications:list"})
   :effect-fn     ws-effect-fn
   :stale-time-ms 30000})

(rfq/reg-query :ws/latest-notification
  {:query-fn            (fn [_] {:channel "notifications:latest"})
   :effect-fn           ws-effect-fn
   :stale-time-ms       1000
   :polling-interval-ms 3000})

(rfq/reg-query :ws/chat-messages
  {:query-fn      (fn [_] {:channel "chat:messages"})
   :effect-fn     ws-effect-fn
   :stale-time-ms 5000
   :tags          (constantly [[:chat :messages]])})

(rfq/reg-mutation :ws/chat-send
  {:mutation-fn (fn [{:keys [user text]}]
                  {:channel "chat:send"
                   :payload {:user user :text text}})
   :effect-fn   ws-effect-fn
   :invalidates (constantly [[:chat :messages]])})
