(ns re-frame.query.polling-test
  (:require
   [clojure.test :refer [deftest is testing use-fixtures]]
   [day8.re-frame.test :as rf-test]
   [re-frame.core :as rf]
   [re-frame.db :as rf-db]
   [re-frame.query :as rfq]
   [re-frame.query.gc :as gc]
   [re-frame.query.polling :as polling]
   [re-frame.query.registry :as registry]
   [re-frame.query.util :as util]
   [re-frame.query.test-helpers :as h]
   #?(:cljs [reagent.ratom :as ratom])))

(use-fixtures :each {:before h/reset-db! :after h/reset-db!})

;; ---------------------------------------------------------------------------
;; Polling tests
;; ---------------------------------------------------------------------------

(deftest polling-subscriber-basics-test
  (testing "add-subscriber! with a positive interval starts polling"
    (let [qid (util/query-id :books/list {})]
      (polling/add-subscriber! qid :sub-1 :books/list {} 5000)
      (is (contains? (polling/active-polls) qid)
          "polling timer is active")
      (is (= 5000 (polling/current-interval qid))
          "effective interval matches the subscriber")
      (polling/remove-subscriber! qid :sub-1)
      (is (not (contains? (polling/active-polls) qid))
          "polling stopped after last subscriber removed")))

  (testing "add-subscriber! is a no-op for zero, nil, or negative intervals"
    (let [qid (util/query-id :books/list {})]
      (polling/add-subscriber! qid :sub-1 :books/list {} 0)
      (is (empty? (polling/active-polls))
          "zero interval does not start polling")
      (polling/add-subscriber! qid :sub-2 :books/list {} nil)
      (is (empty? (polling/active-polls))
          "nil interval does not start polling")
      (polling/add-subscriber! qid :sub-3 :books/list {} -1000)
      (is (empty? (polling/active-polls))
          "negative interval does not start polling")))

  (testing "remove-subscriber! is a no-op for a subscriber that doesn't exist"
    (let [qid (util/query-id :books/list {})]
      (polling/remove-subscriber! qid :nonexistent)
      (is (empty? (polling/active-polls))
          "no error when removing non-existent subscriber"))))

(deftest polling-multiple-subscribers-test
  (testing "Effective interval is the minimum of all active positive subscriber intervals"
    (polling/cancel-all!)
    (let [qid (util/query-id :books/list {})]
      ;; First subscriber at 5000ms
      (polling/add-subscriber! qid :sub-1 :books/list {} 5000)
      (is (= 5000 (polling/current-interval qid)))
      ;; Second subscriber at 1000ms → effective becomes 1000
      (polling/add-subscriber! qid :sub-2 :books/list {} 1000)
      (is (= 1000 (polling/current-interval qid))
          "effective interval is the lowest non-zero")
      (is (= 2 (polling/subscriber-count qid)))
      ;; Third subscriber at 3000ms → still 1000
      (polling/add-subscriber! qid :sub-3 :books/list {} 3000)
      (is (= 1000 (polling/current-interval qid))
          "adding a slower subscriber doesn't change the effective interval")))

  (testing "Removing the fastest subscriber recalculates to the next lowest"
    (polling/cancel-all!)
    (let [qid (util/query-id :books/list {})]
      (polling/add-subscriber! qid :sub-1 :books/list {} 5000)
      (polling/add-subscriber! qid :sub-2 :books/list {} 1000)
      (is (= 1000 (polling/current-interval qid)))
      ;; Remove the faster subscriber
      (polling/remove-subscriber! qid :sub-2)
      (is (= 5000 (polling/current-interval qid))
          "effective interval recalculated to remaining subscriber")
      (is (contains? (polling/active-polls) qid)
          "polling still active with remaining subscriber")))

  (testing "Removing all subscribers stops polling entirely"
    (polling/cancel-all!)
    (let [qid (util/query-id :books/list {})]
      (polling/add-subscriber! qid :sub-1 :books/list {} 5000)
      (polling/add-subscriber! qid :sub-2 :books/list {} 1000)
      (is (contains? (polling/active-polls) qid))
      (polling/remove-subscriber! qid :sub-1)
      (polling/remove-subscriber! qid :sub-2)
      (is (not (contains? (polling/active-polls) qid))
          "polling stopped after all subscribers removed")
      (is (nil? (polling/current-interval qid))))))

(deftest polling-interval-precedence-test
  (testing "Query registered with :polling-interval-ms starts polling when subscribed"
    (let [qid (util/query-id :books/list {})]
      ;; Simulate what the subscription does: read query config, add subscriber
      (rfq/reg-query :books/list
        {:query-fn (fn [_] {})
         :polling-interval-ms 5000})
      (let [config (registry/get-query :books/list)]
        (polling/add-subscriber! qid :sub-1 :books/list {}
                                 (:polling-interval-ms config)))
      (is (contains? (polling/active-polls) qid)
          "polling started from query-level config")
      (is (= 5000 (polling/current-interval qid)))))

  (testing "Per-subscription interval overrides query-level default"
    (let [qid (util/query-id :books/list {})]
      (rfq/reg-query :books/list
        {:query-fn (fn [_] {})
         :polling-interval-ms 5000})
      ;; Subscriber 1 uses query-level default
      (let [config (registry/get-query :books/list)]
        (polling/add-subscriber! qid :sub-1 :books/list {}
                                 (:polling-interval-ms config)))
      (is (= 5000 (polling/current-interval qid)))
      ;; Subscriber 2 overrides with faster interval
      (polling/add-subscriber! qid :sub-2 :books/list {} 1000)
      (is (= 1000 (polling/current-interval qid))
          "subscription-level override wins (lowest interval)"))))

(deftest polling-dispatches-refetch-async
  (testing "Polling timer dispatches poll-refetch on each tick"
    (rf-test/run-test-async
     (let [call-count (atom 0)]
       (rf/reg-fx :test-http (fn [_] (swap! call-count inc)))
       (rfq/set-default-effect-fn!
        (fn [request on-success on-failure]
          {:test-http (assoc request
                             :on-success on-success
                             :on-failure on-failure)}))
       (rfq/reg-query :books/list
         {:query-fn (fn [_] {:method :get :url "/api/books"})})
        ;; Populate with data first so refetches produce effects
       (rf/dispatch-sync [:re-frame.query/query-success :books/list {} [{:id 1}]])
        ;; Start polling at 100ms via subscriber API
       (let [qid (util/query-id :books/list {})]
         (polling/add-subscriber! qid :sub-1 :books/list {} 100)
          ;; Wait for the first poll-refetch to fire
         (rf-test/wait-for [:re-frame.query/poll-refetch]
                           (is (>= @call-count 1)
                               "at least one refetch effect fired from polling")
                            ;; Clean up
                           (polling/remove-subscriber! qid :sub-1)))))))

;; ---------------------------------------------------------------------------
;; Polling e2e tests (through the subscription)
;; ---------------------------------------------------------------------------

(deftest subscription-polling-e2e-test
  (testing "Subscribing with :polling-interval-ms starts polling automatically"
    (rf-test/run-test-sync
     (rfq/reg-query :books/list {:query-fn (fn [_] {})})
     (let [qid (util/query-id :books/list {})
           sub (rf/subscribe [:re-frame.query/query :books/list {} {:polling-interval-ms 5000}])]
        ;; Deref to activate the subscription
       @sub
       (is (contains? (polling/active-polls) qid)
           "polling started via subscription")
       (is (= 5000 (polling/current-interval qid))
           "effective interval matches subscription option"))))

  (testing "Query registered with :polling-interval-ms starts polling on subscribe"
    (rf-test/run-test-sync
     (rfq/reg-query :books/list
       {:query-fn (fn [_] {})
        :polling-interval-ms 3000})
     (let [qid (util/query-id :books/list {})
           sub (rf/subscribe [:re-frame.query/query :books/list {}])]
       @sub
       (is (contains? (polling/active-polls) qid)
           "polling started from query-level config")
       (is (= 3000 (polling/current-interval qid))))))

  (testing "Per-subscription interval overrides query-level default"
    (rf-test/run-test-sync
     (rfq/reg-query :books/list
       {:query-fn (fn [_] {})
        :polling-interval-ms 5000})
     (let [qid (util/query-id :books/list {})
           sub (rf/subscribe [:re-frame.query/query :books/list {} {:polling-interval-ms 1000}])]
       @sub
       (is (= 1000 (polling/current-interval qid))
           "subscription-level override is used instead of query-level"))))

  (testing "Subscribing without :polling-interval-ms does not start polling"
    (rf-test/run-test-sync
     (rfq/reg-query :books/list {:query-fn (fn [_] {})})
     (let [qid (util/query-id :books/list {})
           sub (rf/subscribe [:re-frame.query/query :books/list {}])]
       @sub
       (is (not (contains? (polling/active-polls) qid))
           "no polling without interval")))))

#?(:cljs
   (deftest subscription-dispose-stops-polling
     (testing "Disposing the subscription stops polling"
       (rf-test/run-test-sync
        (rfq/reg-query :books/list {:query-fn (fn [_] {})})
        (let [qid (util/query-id :books/list {})
              sub (rf/subscribe [:re-frame.query/query :books/list {} {:polling-interval-ms 5000}])]
          @sub
          (is (contains? (polling/active-polls) qid)
              "polling is active before dispose")
           ;; Dispose the reaction — triggers on-dispose callback
          (ratom/dispose! sub)
          (is (not (contains? (polling/active-polls) qid))
              "polling stopped after subscription disposed"))))))

#?(:cljs
   (deftest subscription-dispose-with-query-level-polling-stops
     (testing "Disposing a subscription with query-level polling stops it"
       (rf-test/run-test-sync
        (rfq/reg-query :books/list
          {:query-fn (fn [_] {})
           :polling-interval-ms 3000})
        (let [qid (util/query-id :books/list {})
              sub (rf/subscribe [:re-frame.query/query :books/list {}])]
          @sub
          (is (contains? (polling/active-polls) qid))
          (ratom/dispose! sub)
          (is (not (contains? (polling/active-polls) qid))
              "polling stopped after dispose"))))))

#?(:cljs
   (deftest polling-after-skip-toggle-test
     (testing "polling starts when re-subscribing without skip?"
       (rf-test/run-test-sync
        (rfq/reg-query :books/list {:query-fn (fn [_] {})})
        (let [qid (util/query-id :books/list {})]
          ;; Subscribe with skip — no polling
          (let [sub (rf/subscribe [:re-frame.query/query :books/list {}
                                   {:skip? true :polling-interval-ms 5000}])]
            @sub
            (is (not (contains? (polling/active-polls) qid))
                "no polling when skipped"))
          ;; Re-subscribe without skip — polling should start
          (let [sub (rf/subscribe [:re-frame.query/query :books/list {}
                                   {:polling-interval-ms 5000}])]
            @sub
            (is (contains? (polling/active-polls) qid)
                "polling starts after skip removed")))))))

;; ---------------------------------------------------------------------------
;; Conditional fetching (skip) tests
;; ---------------------------------------------------------------------------

(deftest skip-query-behavior
  (rf-test/run-test-sync
   (let [call-count (atom 0)]
     (rf/reg-fx :test-http (fn [_] (swap! call-count inc)))
     (rfq/set-default-effect-fn!
      (fn [request on-success on-failure]
        {:test-http (assoc request
                           :on-success on-success
                           :on-failure on-failure)}))
     (rfq/reg-query :books/detail
       {:query-fn (fn [{:keys [id]}]
                    {:method :get :url (str "/api/books/" id)})})

     (testing "returns idle state and does not fetch"
       (let [sub (rf/subscribe [:re-frame.query/query :books/detail {:id 1} {:skip? true}])]
         (is (= {:status :idle
                 :data nil
                 :error nil
                 :fetching? false
                 :stale? true}
                @sub))
         (is (zero? @call-count)
             "no effect fired when skipped")))

     (testing "does not mark the query active"
       (let [qid (util/query-id :books/detail {:id 1})]
         (is (nil? (get-in (h/app-db) [:re-frame.query/queries qid :active?])))))

     (testing "does not start polling even with :polling-interval-ms"
       (let [qid (util/query-id :books/detail {:id 1})
             sub (rf/subscribe [:re-frame.query/query :books/detail {:id 1}
                                {:skip? true :polling-interval-ms 5000}])]
         @sub
         (is (not (contains? (polling/active-polls) qid)))))

     (testing ":skip? false fetches normally"
       (reset! call-count 0)
       (let [sub (rf/subscribe [:re-frame.query/query :books/detail {:id 1} {:skip? false}])]
         @sub
         (is (= 1 @call-count) "effect fires when :skip? is false")))

     (testing "toggling skip off triggers the fetch"
       (reset! call-count 0)
        ;; Subscribe with skip — no new fetch
       (let [sub (rf/subscribe [:re-frame.query/query :books/detail {:id 2} {:skip? true}])]
         @sub
         (is (zero? @call-count) "no fetch when skipped"))
        ;; Subscribe without skip — fetch fires
       (let [sub (rf/subscribe [:re-frame.query/query :books/detail {:id 2}])]
         @sub
         (is (= 1 @call-count)
             "fetch fires when skip is removed"))))))

;; ---------------------------------------------------------------------------
;; skip-polling-if-fetching? tests
;; ---------------------------------------------------------------------------

(deftest poll-refetch-skips-when-fetching-by-default
  (testing "poll-refetch is a no-op when query is already fetching (default)"
    (let [call-count (atom 0)]
      (rf/reg-fx :test-http (fn [_] (swap! call-count inc)))
      (rfq/set-default-effect-fn!
       (fn [request on-success on-failure]
         {:test-http (assoc request :on-success on-success :on-failure on-failure)}))
      (rfq/reg-query :books/list
        {:query-fn (fn [_] {:method :get :url "/api/books"})})
      ;; Populate with data and simulate an in-flight fetch
      (h/process-event [:re-frame.query/query-success :books/list {} [{:id 1}]])
      (swap! rf-db/app-db assoc-in
             [:re-frame.query/queries (util/query-id :books/list {}) :fetching?] true)
      (reset! call-count 0)
      ;; poll-refetch should be a no-op since fetching? is true
      (h/process-event [:re-frame.query/poll-refetch :books/list {}])
      (is (zero? @call-count)
          "no HTTP call — poll tick skipped while fetching"))))

(deftest poll-refetch-fires-when-force-mode
  (testing "poll-refetch fires even when fetching if :polling-mode :force"
    (let [call-count (atom 0)]
      (rf/reg-fx :test-http (fn [_] (swap! call-count inc)))
      (rfq/set-default-effect-fn!
       (fn [request on-success on-failure]
         {:test-http (assoc request :on-success on-success :on-failure on-failure)}))
      (rfq/reg-query :books/list
        {:query-fn (fn [_] {:method :get :url "/api/books"})
         :polling-mode :force})
      ;; Populate with data and simulate an in-flight fetch
      (h/process-event [:re-frame.query/query-success :books/list {} [{:id 1}]])
      (swap! rf-db/app-db assoc-in
             [:re-frame.query/queries (util/query-id :books/list {}) :fetching?] true)
      (reset! call-count 0)
      ;; poll-refetch should still fire — opted out of skip
      (h/process-event [:re-frame.query/poll-refetch :books/list {}])
      (is (= 1 @call-count)
          "HTTP call fired — polling-mode :force overrides skip"))))

(deftest poll-refetch-fires-when-not-fetching
  (testing "poll-refetch fires normally when no request is in-flight"
    (let [call-count (atom 0)]
      (rf/reg-fx :test-http (fn [_] (swap! call-count inc)))
      (rfq/set-default-effect-fn!
       (fn [request on-success on-failure]
         {:test-http (assoc request :on-success on-success :on-failure on-failure)}))
      (rfq/reg-query :books/list
        {:query-fn (fn [_] {:method :get :url "/api/books"})})
      ;; Populate with data, not fetching
      (h/process-event [:re-frame.query/query-success :books/list {} [{:id 1}]])
      (reset! call-count 0)
      ;; poll-refetch should fire — nothing in-flight
      (h/process-event [:re-frame.query/poll-refetch :books/list {}])
      (is (= 1 @call-count)
          "HTTP call fired — no in-flight request"))))

(deftest manual-refetch-ignores-skip-polling-if-fetching
  (testing "manual refetch-query always fires regardless of fetching? and config"
    (let [call-count (atom 0)]
      (rf/reg-fx :test-http (fn [_] (swap! call-count inc)))
      (rfq/set-default-effect-fn!
       (fn [request on-success on-failure]
         {:test-http (assoc request :on-success on-success :on-failure on-failure)}))
      (rfq/reg-query :books/list
        {:query-fn (fn [_] {:method :get :url "/api/books"})})
      ;; Populate with data and simulate an in-flight fetch
      (h/process-event [:re-frame.query/query-success :books/list {} [{:id 1}]])
      (swap! rf-db/app-db assoc-in
             [:re-frame.query/queries (util/query-id :books/list {}) :fetching?] true)
      (reset! call-count 0)
      ;; Manual refetch-query should fire unconditionally
      (h/process-event [:re-frame.query/refetch-query :books/list {}])
      (is (= 1 @call-count)
          "manual refetch-query fires even when fetching"))))

(deftest dependent-query-pattern
  (testing "Query B skips until query A has data, then fetches with A's result"
    (rf-test/run-test-sync
     (let [calls (atom [])]
       (rf/reg-fx :test-http (fn [v] (swap! calls conj v)))
       (rfq/set-default-effect-fn!
        (fn [request on-success on-failure]
          {:test-http (assoc request
                             :on-success on-success
                             :on-failure on-failure)}))
       (rfq/reg-query :user/current
         {:query-fn (fn [_] {:method :get :url "/api/me"})})
       (rfq/reg-query :user/todos
         {:query-fn (fn [{:keys [user-id]}]
                      {:method :get :url (str "/api/users/" user-id "/todos")})})
        ;; Query A: fetch current user
       (let [user-sub (rf/subscribe [:re-frame.query/query :user/current {}])]
         @user-sub
         (is (= 1 (count @calls)) "user query fires"))
        ;; Query B: skip until we have user data
       (let [user-id nil ;; no data yet
             todos-sub (rf/subscribe [:re-frame.query/query :user/todos {:user-id user-id}
                                      {:skip? (nil? user-id)}])]
         @todos-sub
         (is (= 1 (count @calls)) "todos query skipped — still only 1 call"))
        ;; Simulate user query success
       (rf/dispatch [:re-frame.query/query-success :user/current {} {:id 42}])
        ;; Now query B subscribes without skip
       (let [user-id 42
             todos-sub (rf/subscribe [:re-frame.query/query :user/todos {:user-id user-id}])]
         @todos-sub
         (is (= 2 (count @calls)) "todos query fires with user-id")
         (is (= "/api/users/42/todos" (:url (second @calls)))
             "todos query uses the correct user-id from query A"))))))
