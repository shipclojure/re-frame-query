(ns re-frame.query-test
  (:require
   [clojure.test :refer [deftest is testing use-fixtures]]
   [day8.re-frame.test :as rf-test]
   [re-frame.core :as rf]
   [re-frame.db :as rf-db]
   [re-frame.query :as rfq]
   [re-frame.query.gc :as gc]
   [re-frame.query.registry :as registry]
   [re-frame.query.util :as util]))

;; ---------------------------------------------------------------------------
;; Test helpers
;; ---------------------------------------------------------------------------

(defn reset-db! []
  (reset! rf-db/app-db {})
  (rfq/clear-registry!)
  (gc/cancel-all!))

(use-fixtures :each
  {:before reset-db!
   :after  reset-db!})

(defn app-db [] @rf-db/app-db)

(defn process-event
  "Dispatch an event synchronously for testing."
  [event]
  (rf/dispatch-sync event))

;; ---------------------------------------------------------------------------
;; Registration tests
;; ---------------------------------------------------------------------------

(deftest reg-query-test
  (testing "registers a query and returns the key"
    (let [k (rfq/reg-query :todos/list
                           {:query-fn (fn [_] {})})]
      (is (= :todos/list k)))))

(deftest reg-mutation-test
  (testing "registers a mutation and returns the key"
    (let [k (rfq/reg-mutation :todos/add
                              {:mutation-fn (fn [_] {})})]
      (is (= :todos/add k)))))

;; ---------------------------------------------------------------------------
;; Query event tests
;; ---------------------------------------------------------------------------

(deftest ensure-query-sets-loading-for-initial-fetch-test
  (testing "ensure-query sets status to :loading when no data exists"
    (rfq/reg-query :books/list
                   {:query-fn (fn [_] {})})
    (process-event [:re-frame.query/ensure-query :books/list {}])
    (let [qid   (util/query-id :books/list {})
          query (get-in (app-db) [:re-frame.query/queries qid])]
      (is (= :loading (:status query)))
      (is (true? (:fetching? query)))
      (is (nil? (:data query))))))

(deftest ensure-query-keeps-success-on-refetch-test
  (testing "ensure-query keeps :success status when stale data exists"
    (rfq/reg-query :books/list
                   {:query-fn      (fn [_] {})
                    :stale-time-ms 1000})
    ;; Fetch successfully first
    (process-event [:re-frame.query/query-success :books/list {} [{:id 1}]])
    ;; Make it stale: fetched-at=0, now-ms returns 1001 (past stale-time of 1000)
    (let [qid (util/query-id :books/list {})]
      (swap! rf-db/app-db assoc-in [:re-frame.query/queries qid :fetched-at] 0)
      (with-redefs [util/now-ms (constantly 1001)]
        (process-event [:re-frame.query/ensure-query :books/list {}]))
      (let [query (get-in (app-db) [:re-frame.query/queries qid])]
        (is (= :success (:status query))
            "status stays :success so components keep showing stale data")
        (is (true? (:fetching? query))
            "fetching? is true to indicate a background refetch")
        (is (= [{:id 1}] (:data query))
            "stale data is preserved while refetching")))))

(deftest refetch-query-keeps-success-when-data-exists-test
  (testing "refetch-query keeps :success status when data exists"
    (rfq/reg-query :books/list
                   {:query-fn (fn [_] {})})
    ;; Fetch successfully first
    (process-event [:re-frame.query/query-success :books/list {} [{:id 1}]])
    ;; Force refetch
    (process-event [:re-frame.query/refetch-query :books/list {}])
    (let [qid   (util/query-id :books/list {})
          query (get-in (app-db) [:re-frame.query/queries qid])]
      (is (= :success (:status query))
          "status stays :success during background refetch")
      (is (true? (:fetching? query)))
      (is (= [{:id 1}] (:data query))))))

(deftest refetch-query-sets-loading-when-no-data-test
  (testing "refetch-query sets :loading when no prior data exists"
    (rfq/reg-query :books/list
                   {:query-fn (fn [_] {})})
    (process-event [:re-frame.query/refetch-query :books/list {}])
    (let [qid   (util/query-id :books/list {})
          query (get-in (app-db) [:re-frame.query/queries qid])]
      (is (= :loading (:status query)))
      (is (true? (:fetching? query))))))

(deftest ensure-query-sets-loading-after-error-test
  (testing "ensure-query sets :loading when retrying after an error (no prior data)"
    (rfq/reg-query :books/list {:query-fn (fn [_] {})})
    (process-event [:re-frame.query/query-failure :books/list {} {:status 500}])
    (process-event [:re-frame.query/ensure-query :books/list {}])
    (let [qid   (util/query-id :books/list {})
          query (get-in (app-db) [:re-frame.query/queries qid])]
      (is (= :loading (:status query))
          "status resets to :loading on retry after error")
      (is (true? (:fetching? query)))))

  (testing "ensure-query sets :loading after error even when stale data exists"
    (rfq/reg-query :books/detail
                   {:query-fn      (fn [_] {})
                    :stale-time-ms 1000})
    ;; First fetch succeeds
    (process-event [:re-frame.query/query-success :books/detail {} {:title "Dune"}])
    (let [qid (util/query-id :books/detail {})]
      ;; Then a refetch fails — data persists but status is :error
      (process-event [:re-frame.query/query-failure :books/detail {} {:status 500}])
      (is (= :error (get-in (app-db) [:re-frame.query/queries qid :status])))
      (is (some? (get-in (app-db) [:re-frame.query/queries qid :data])))
      ;; Retry
      (process-event [:re-frame.query/ensure-query :books/detail {}])
      (let [query (get-in (app-db) [:re-frame.query/queries qid])]
        (is (= :loading (:status query))
            "status resets to :loading even though stale data exists, because last status was :error")
        (is (true? (:fetching? query)))))))

(deftest refetch-query-sets-loading-after-error-test
  (testing "refetch-query sets :loading when current status is :error"
    (rfq/reg-query :books/list {:query-fn (fn [_] {})})
    ;; First succeeds, then fails
    (process-event [:re-frame.query/query-success :books/list {} [{:id 1}]])
    (process-event [:re-frame.query/query-failure :books/list {} {:status 503}])
    ;; Refetch after error
    (process-event [:re-frame.query/refetch-query :books/list {}])
    (let [qid   (util/query-id :books/list {})
          query (get-in (app-db) [:re-frame.query/queries qid])]
      (is (= :loading (:status query))
          "status is :loading, not :success, because last status was :error")
      (is (true? (:fetching? query))))))

(deftest query-success-test
  (testing "query-success stores data and metadata"
    (rfq/reg-query :books/list
                   {:query-fn      (fn [_] {})
                    :stale-time-ms 30000
                    :cache-time-ms 300000
                    :tags          (fn [_] [[:books :all]])})
    ;; Simulate success
    (process-event [:re-frame.query/query-success :books/list {} [{:id 1 :title "Dune"}]])
    (let [qid   (util/query-id :books/list {})
          query (get-in (app-db) [:re-frame.query/queries qid])]
      (is (= :success (:status query)))
      (is (= [{:id 1 :title "Dune"}] (:data query)))
      (is (nil? (:error query)))
      (is (false? (:fetching? query)))
      (is (number? (:fetched-at query)))
      (is (= #{[:books :all]} (:tags query)))
      (is (= 30000 (:stale-time-ms query)))
      (is (= 300000 (:cache-time-ms query))))))

(deftest query-success-uses-default-cache-time-test
  (testing "query-success stores default cache-time-ms when not specified in config"
    (rfq/reg-query :books/detail
                   {:query-fn (fn [_] {})})
    (process-event [:re-frame.query/query-success :books/detail {:id 1} {:title "Dune"}])
    (let [qid   (util/query-id :books/detail {:id 1})
          query (get-in (app-db) [:re-frame.query/queries qid])]
      (is (= gc/default-cache-time-ms (:cache-time-ms query))))))

(deftest query-failure-test
  (testing "query-failure stores error"
    (rfq/reg-query :books/list {:query-fn (fn [_] {})})
    (process-event [:re-frame.query/query-failure :books/list {} {:status 500}])
    (let [qid   (util/query-id :books/list {})
          query (get-in (app-db) [:re-frame.query/queries qid])]
      (is (= :error (:status query)))
      (is (= {:status 500} (:error query)))
      (is (false? (:fetching? query))))))

;; ---------------------------------------------------------------------------
;; Mutation event tests
;; ---------------------------------------------------------------------------

(deftest mutation-success-test
  (testing "mutation-success sets status and triggers invalidation dispatch"
    (rfq/reg-mutation :books/create
                      {:mutation-fn (fn [_] {})
                       :invalidates (fn [_] [[:books :all]])})
    (process-event [:re-frame.query/mutation-success :books/create {} {:id 2}])
    (let [mid      (util/query-id :books/create {})
          mutation (get-in (app-db) [:re-frame.query/mutations mid])]
      (is (= :success (:status mutation))))))

(deftest mutation-failure-test
  (testing "mutation-failure stores error"
    (rfq/reg-mutation :books/create {:mutation-fn (fn [_] {})})
    (process-event [:re-frame.query/mutation-failure :books/create {} {:status 422}])
    (let [mid      (util/query-id :books/create {})
          mutation (get-in (app-db) [:re-frame.query/mutations mid])]
      (is (= :error (:status mutation)))
      (is (= {:status 422} (:error mutation))))))

;; ---------------------------------------------------------------------------
;; Invalidation tests
;; ---------------------------------------------------------------------------

(deftest invalidate-tags-marks-matching-queries-stale
  (testing "invalidate-tags marks queries with matching tags as stale"
    (rfq/reg-query :books/list
                   {:query-fn (fn [_] {})
                    :tags     (fn [_] [[:books :all]])})
    ;; Set up a cached query
    (process-event [:re-frame.query/query-success :books/list {} [{:id 1}]])
    ;; Invalidate
    (process-event [:re-frame.query/invalidate-tags [[:books :all]]])
    (let [qid   (util/query-id :books/list {})
          query (get-in (app-db) [:re-frame.query/queries qid])]
      (is (true? (:stale? query))))))

;; ---------------------------------------------------------------------------
;; Garbage collection tests
;; ---------------------------------------------------------------------------

(deftest garbage-collect-removes-expired-inactive-queries
  (testing "GC removes expired inactive queries"
    (rfq/reg-query :books/list
                   {:query-fn      (fn [_] {})
                    :cache-time-ms 1000})
    ;; Simulate a query that was fetched 2 seconds ago
    (process-event [:re-frame.query/query-success :books/list {} [{:id 1}]])
    ;; Manually set fetched-at to the past
    (let [qid (util/query-id :books/list {})]
      (swap! rf-db/app-db assoc-in [:re-frame.query/queries qid :fetched-at] 1000)
      ;; GC at time 3000 (2 seconds after fetch, past 1s cache-time)
      (process-event [:re-frame.query/garbage-collect 3000])
      (is (nil? (get-in (app-db) [:re-frame.query/queries qid]))))))

(deftest garbage-collect-keeps-active-queries
  (testing "GC keeps active queries even if expired"
    (rfq/reg-query :books/list
                   {:query-fn      (fn [_] {})
                    :cache-time-ms 1000})
    (process-event [:re-frame.query/query-success :books/list {} [{:id 1}]])
    (let [qid (util/query-id :books/list {})]
      (swap! rf-db/app-db assoc-in [:re-frame.query/queries qid :fetched-at] 1000)
      (swap! rf-db/app-db assoc-in [:re-frame.query/queries qid :active?] true)
      (process-event [:re-frame.query/garbage-collect 3000])
      (is (some? (get-in (app-db) [:re-frame.query/queries qid]))))))

;; ---------------------------------------------------------------------------
;; Active tracking tests
;; ---------------------------------------------------------------------------

(deftest mark-active-inactive-test
  (testing "mark-active sets active? to true"
    (rfq/reg-query :books/list {:query-fn (fn [_] {})})
    (process-event [:re-frame.query/query-success :books/list {} []])
    (process-event [:re-frame.query/mark-active :books/list {}])
    (let [qid (util/query-id :books/list {})]
      (is (true? (get-in (app-db) [:re-frame.query/queries qid :active?])))))

  (testing "mark-inactive sets active? to false"
    (process-event [:re-frame.query/mark-inactive :books/list {}])
    (let [qid (util/query-id :books/list {})]
      (is (false? (get-in (app-db) [:re-frame.query/queries qid :active?]))))))

;; ---------------------------------------------------------------------------
;; Per-query GC timer tests
;; ---------------------------------------------------------------------------

(deftest mark-inactive-schedules-gc-timer
  (testing "mark-inactive schedules a GC timer when query has cache-time-ms"
    (rfq/reg-query :books/list
                   {:query-fn      (fn [_] {})
                    :cache-time-ms 60000})
    (process-event [:re-frame.query/query-success :books/list {} [{:id 1}]])
    (process-event [:re-frame.query/mark-inactive :books/list {}])
    (let [qid (util/query-id :books/list {})]
      (is (contains? (gc/active-timers) qid)))))

(deftest mark-inactive-uses-default-cache-time
  (testing "mark-inactive schedules a GC timer using default cache time when query has no cache-time-ms"
    (rfq/reg-query :books/list {:query-fn (fn [_] {})})
    (process-event [:re-frame.query/query-success :books/list {} [{:id 1}]])
    (process-event [:re-frame.query/mark-inactive :books/list {}])
    (let [qid (util/query-id :books/list {})]
      (is (contains? (gc/active-timers) qid)))))

(deftest mark-active-cancels-gc-timer
  (testing "mark-active cancels any pending GC timer"
    (rfq/reg-query :books/list
                   {:query-fn      (fn [_] {})
                    :cache-time-ms 60000})
    (process-event [:re-frame.query/query-success :books/list {} [{:id 1}]])
    ;; Go inactive → timer starts
    (process-event [:re-frame.query/mark-inactive :books/list {}])
    (let [qid (util/query-id :books/list {})]
      (is (contains? (gc/active-timers) qid))
      ;; Go active again → timer cancelled
      (process-event [:re-frame.query/mark-active :books/list {}])
      (is (not (contains? (gc/active-timers) qid))))))

(deftest remove-query-evicts-inactive-query
  (testing "remove-query removes an inactive query from cache"
    (rfq/reg-query :books/list {:query-fn (fn [_] {})})
    (process-event [:re-frame.query/query-success :books/list {} [{:id 1}]])
    (let [qid (util/query-id :books/list {})]
      ;; Mark inactive, then remove
      (process-event [:re-frame.query/mark-inactive :books/list {}])
      (process-event [:re-frame.query/remove-query qid])
      (is (nil? (get-in (app-db) [:re-frame.query/queries qid]))))))

(deftest remove-query-keeps-active-query
  (testing "remove-query is a no-op if query became active again"
    (rfq/reg-query :books/list {:query-fn (fn [_] {})})
    (process-event [:re-frame.query/query-success :books/list {} [{:id 1}]])
    (let [qid (util/query-id :books/list {})]
      ;; Mark active
      (process-event [:re-frame.query/mark-active :books/list {}])
      ;; Attempt removal — should be a no-op
      (process-event [:re-frame.query/remove-query qid])
      (is (some? (get-in (app-db) [:re-frame.query/queries qid]))))))

;; ---------------------------------------------------------------------------
;; Effect-fn (auto-injected callbacks) tests
;; ---------------------------------------------------------------------------

(deftest effect-fn-auto-injects-query-callbacks
  (testing "execute-query-effect uses effect-fn to inject success/failure callbacks"
    (let [captured (atom nil)]
      ;; Register a test effect that captures its arguments
      (rf/reg-fx :test-http (fn [v] (reset! captured v)))
      ;; Set global effect-fn
      (rfq/set-default-effect-fn!
       (fn [request on-success on-failure]
         {:test-http (assoc request
                            :on-success on-success
                            :on-failure on-failure)}))
      ;; Register query — query-fn returns only the request description
      (rfq/reg-query :books/list
                     {:query-fn (fn [{:keys [page]}]
                                  {:method :get
                                   :url    (str "/api/books?page=" page)})})
      (process-event [:re-frame.query/refetch-query :books/list {:page 1}])
      (is (= {:method     :get
              :url        "/api/books?page=1"
              :on-success [:re-frame.query/query-success :books/list {:page 1}]
              :on-failure [:re-frame.query/query-failure :books/list {:page 1}]}
             @captured)))))

(deftest effect-fn-auto-injects-mutation-callbacks
  (testing "execute-mutation uses effect-fn to inject success/failure callbacks"
    (let [captured (atom nil)]
      (rf/reg-fx :test-http (fn [v] (reset! captured v)))
      (rfq/set-default-effect-fn!
       (fn [request on-success on-failure]
         {:test-http (assoc request
                            :on-success on-success
                            :on-failure on-failure)}))
      (rfq/reg-mutation :books/create
                        {:mutation-fn (fn [{:keys [title]}]
                                        {:method :post
                                         :url    "/api/books"
                                         :body   {:title title}})})
      (process-event [:re-frame.query/execute-mutation :books/create {:title "Dune"}])
      (is (= {:method     :post
              :url        "/api/books"
              :body       {:title "Dune"}
              :on-success [:re-frame.query/mutation-success :books/create {:title "Dune"}]
              :on-failure [:re-frame.query/mutation-failure :books/create {:title "Dune"}]}
             @captured)))))

(deftest per-query-effect-fn-overrides-global
  (testing "per-query :effect-fn takes precedence over the global one"
    (let [captured (atom nil)]
      (rf/reg-fx :custom-http (fn [v] (reset! captured v)))
      ;; Global effect-fn (should NOT be used)
      (rfq/set-default-effect-fn!
       (fn [request on-success on-failure]
         {:global-http (assoc request
                              :on-success on-success
                              :on-failure on-failure)}))
      ;; Per-query effect-fn override
      (rfq/reg-query :books/special
                     {:query-fn  (fn [_] {:method :get :url "/api/special"})
                      :effect-fn (fn [request on-success on-failure]
                                   {:custom-http (assoc request
                                                        :on-success on-success
                                                        :on-failure on-failure)})})
      (process-event [:re-frame.query/refetch-query :books/special {}])
      (is (= {:method     :get
              :url        "/api/special"
              :on-success [:re-frame.query/query-success :books/special {}]
              :on-failure [:re-frame.query/query-failure :books/special {}]}
             @captured)))))

(deftest legacy-query-fn-still-works-without-effect-fn
  (testing "without effect-fn, query-fn returning a full effects map still works"
    (let [captured (atom nil)]
      (rf/reg-fx :test-http (fn [v] (reset! captured v)))
      ;; No set-default-effect-fn! call — legacy mode
      (rfq/reg-query :books/legacy
                     {:query-fn (fn [{:keys [page]}]
                                  {:test-http {:method     :get
                                               :url        "/api/books"
                                               :on-success [:re-frame.query/query-success :books/legacy {:page page}]
                                               :on-failure [:re-frame.query/query-failure :books/legacy {:page page}]}})})
      (process-event [:re-frame.query/refetch-query :books/legacy {:page 1}])
      (is (= {:method     :get
              :url        "/api/books"
              :on-success [:re-frame.query/query-success :books/legacy {:page 1}]
              :on-failure [:re-frame.query/query-failure :books/legacy {:page 1}]}
             @captured)))))

(deftest no-custom-callbacks-still-works
  (testing "queries without custom effect-fn work normally"
    (rfq/reg-query :books/plain {:query-fn (fn [_] {})})
    (process-event [:re-frame.query/query-success :books/plain {} [{:id 1}]])
    (let [qid (util/query-id :books/plain {})]
      (is (= :success (get-in (app-db) [:re-frame.query/queries qid :status])))
      (is (= [{:id 1}] (get-in (app-db) [:re-frame.query/queries qid :data]))))))

;; ---------------------------------------------------------------------------
;; init! (declarative registry) tests
;; ---------------------------------------------------------------------------

(deftest init-registers-queries-and-mutations
  (testing "init! sets up queries, mutations, and default-effect-fn in one shot"
    (let [captured (atom nil)]
      (rf/reg-fx :test-http (fn [v] (reset! captured v)))
      (rfq/init!
       {:default-effect-fn (fn [request on-success on-failure]
                             {:test-http (assoc request
                                                :on-success on-success
                                                :on-failure on-failure)})
        :queries
        {:books/list {:query-fn (fn [{:keys [page]}]
                                  {:method :get
                                   :url    (str "/api/books?page=" page)})
                      :stale-time-ms 30000
                      :tags (fn [_] [[:books]])}}
        :mutations
        {:books/create {:mutation-fn (fn [{:keys [title]}]
                                       {:method :post
                                        :url    "/api/books"
                                        :body   {:title title}})
                        :invalidates (fn [_] [[:books]])}}})
      ;; Query works
      (process-event [:re-frame.query/refetch-query :books/list {:page 1}])
      (is (= {:method     :get
              :url        "/api/books?page=1"
              :on-success [:re-frame.query/query-success :books/list {:page 1}]
              :on-failure [:re-frame.query/query-failure :books/list {:page 1}]}
             @captured))
      ;; Mutation works
      (reset! captured nil)
      (process-event [:re-frame.query/execute-mutation :books/create {:title "Dune"}])
      (is (= {:method     :post
              :url        "/api/books"
              :body       {:title "Dune"}
              :on-success [:re-frame.query/mutation-success :books/create {:title "Dune"}]
              :on-failure [:re-frame.query/mutation-failure :books/create {:title "Dune"}]}
             @captured)))))

(deftest init-clears-previous-state
  (testing "init! replaces any previously registered queries/mutations"
    ;; Register something first via the incremental API
    (rfq/reg-query :old/query {:query-fn (fn [_] {})})
    (rfq/reg-mutation :old/mutation {:mutation-fn (fn [_] {})})
    ;; Now init! with a fresh config
    (rfq/init!
     {:queries {:new/query {:query-fn (fn [_] {:url "/new"})}}})
    ;; Old registrations are gone
    (is (nil? (registry/get-query :old/query)))
    (is (nil? (registry/get-mutation :old/mutation)))
    ;; New registration exists
    (is (some? (registry/get-query :new/query)))))

(deftest init-with-empty-map
  (testing "init! with empty map clears everything"
    (rfq/reg-query :some/query {:query-fn (fn [_] {})})
    (rfq/init! {})
    (is (nil? (registry/get-query :some/query)))
    (is (nil? (registry/get-default-effect-fn)))))

(deftest init-incremental-after-init
  (testing "reg-query and reg-mutation still work after init!"
    (rfq/init!
     {:queries {:books/list {:query-fn (fn [_] {:url "/books"})}}})
    ;; Add another query incrementally
    (rfq/reg-query :books/detail {:query-fn (fn [{:keys [id]}] {:url (str "/books/" id)})})
    ;; Both exist
    (is (some? (registry/get-query :books/list)))
    (is (some? (registry/get-query :books/detail)))))

;; ---------------------------------------------------------------------------
;; Query deduplication tests
;; ---------------------------------------------------------------------------

(deftest ensure-query-deduplicates-inflight-requests
  (testing "ensure-query is a no-op when the query is already fetching"
    (let [call-count (atom 0)]
      (rf/reg-fx :test-http (fn [_] (swap! call-count inc)))
      (rfq/set-default-effect-fn!
       (fn [request on-success on-failure]
         {:test-http (assoc request
                            :on-success on-success
                            :on-failure on-failure)}))
      (rfq/reg-query :books/list
                     {:query-fn (fn [_] {:method :get :url "/api/books"})})
      ;; First dispatch — should fire the effect
      (process-event [:re-frame.query/ensure-query :books/list {}])
      (is (= 1 @call-count) "first ensure-query fires the effect")
      ;; Verify fetching? is now true
      (let [qid (util/query-id :books/list {})]
        (is (true? (get-in (app-db) [:re-frame.query/queries qid :fetching?]))))
      ;; Second dispatch — should be a no-op because fetching? is true
      (process-event [:re-frame.query/ensure-query :books/list {}])
      (is (= 1 @call-count)
          "second ensure-query is a no-op while already fetching"))))

(deftest ensure-query-different-params-are-independent
  (testing "ensure-query with different params produces separate effects"
    (let [calls (atom [])]
      (rf/reg-fx :test-http (fn [v] (swap! calls conj v)))
      (rfq/set-default-effect-fn!
       (fn [request on-success on-failure]
         {:test-http (assoc request
                            :on-success on-success
                            :on-failure on-failure)}))
      (rfq/reg-query :books/detail
                     {:query-fn (fn [{:keys [id]}]
                                  {:method :get :url (str "/api/books/" id)})})
      ;; Two dispatches with different params
      (process-event [:re-frame.query/ensure-query :books/detail {:id 1}])
      (process-event [:re-frame.query/ensure-query :books/detail {:id 2}])
      (is (= 2 (count @calls))
          "different params produce separate effects")
      (is (= "/api/books/1" (:url (first @calls))))
      (is (= "/api/books/2" (:url (second @calls)))))))

(deftest ensure-query-fresh-cache-produces-no-effect
  (testing "ensure-query does nothing when cached data is within stale-time"
    (let [call-count (atom 0)]
      (rf/reg-fx :test-http (fn [_] (swap! call-count inc)))
      (rfq/set-default-effect-fn!
       (fn [request on-success on-failure]
         {:test-http (assoc request
                            :on-success on-success
                            :on-failure on-failure)}))
      (rfq/reg-query :books/list
                     {:query-fn      (fn [_] {:method :get :url "/api/books"})
                      :stale-time-ms 60000})
      ;; Populate cache with fresh data
      (process-event [:re-frame.query/query-success :books/list {} [{:id 1}]])
      ;; Reset counter after the setup
      (reset! call-count 0)
      ;; ensure-query on fresh data — should produce no effect
      (process-event [:re-frame.query/ensure-query :books/list {}])
      (is (zero? @call-count)
          "no effect produced when data is fresh"))))

;; ---------------------------------------------------------------------------
;; Invalidation refetch behavior tests
;; ---------------------------------------------------------------------------

(deftest invalidation-refetches-active-queries-only
  (testing "Active queries matching invalidated tags are refetched;
            inactive queries are only marked stale."
    (rf-test/run-test-sync
     (let [calls (atom [])]
       (rf/reg-fx :test-http (fn [v] (swap! calls conj v)))
       (rfq/set-default-effect-fn!
        (fn [request on-success on-failure]
          {:test-http (assoc request
                             :on-success on-success
                             :on-failure on-failure)}))
       ;; Register two queries sharing the same tag
       (rfq/reg-query :books/list
                      {:query-fn (fn [_] {:method :get :url "/api/books"})
                       :tags     (fn [_] [[:books :all]])})
       (rfq/reg-query :books/detail
                      {:query-fn (fn [{:keys [id]}] {:method :get :url (str "/api/books/" id)})
                       :tags     (fn [{:keys [id]}] [[:books :all] [:books :id id]])})
       ;; Populate both with data
       (rf/dispatch [:re-frame.query/query-success :books/list {} [{:id 1}]])
       (rf/dispatch [:re-frame.query/query-success :books/detail {:id 1} {:id 1 :title "Dune"}])
       ;; Mark only list as active, detail stays inactive
       (rf/dispatch [:re-frame.query/mark-active :books/list {}])
       ;; Reset call log after setup
       (reset! calls [])
       ;; Invalidate — run-test-sync resolves the full chain:
       ;; invalidate-tags → (marks stale) → refetch-query (for active only)
       (rf/dispatch [:re-frame.query/invalidate-tags [[:books :all]]])
       (let [list-qid   (util/query-id :books/list {})
             detail-qid (util/query-id :books/detail {:id 1})]
         ;; The active query was refetched automatically (stale? reset to false)
         (is (true? (get-in (app-db) [:re-frame.query/queries list-qid :fetching?]))
             "active query is now refetching")
         (is (false? (get-in (app-db) [:re-frame.query/queries list-qid :stale?]))
             "stale? reset to false by the refetch")
         ;; The inactive query is just marked stale — no refetch
         (is (true? (get-in (app-db) [:re-frame.query/queries detail-qid :stale?]))
             "inactive query is marked stale")
         (is (false? (get-in (app-db) [:re-frame.query/queries detail-qid :fetching?]))
             "inactive query is NOT refetching")
         ;; Only the active query triggered a refetch effect
         (is (= 1 (count @calls))
             "only one refetch effect fired (for the active query)")
         (is (= "/api/books" (:url (first @calls)))
             "the refetch effect is for the active query"))))))

(deftest invalidation-does-not-refetch-inactive-queries
  (testing "Inactive queries get marked stale but no refetch dispatch happens"
    (let [call-count (atom 0)]
      (rf/reg-fx :test-http (fn [_] (swap! call-count inc)))
      (rfq/set-default-effect-fn!
       (fn [request on-success on-failure]
         {:test-http (assoc request
                            :on-success on-success
                            :on-failure on-failure)}))
      (rfq/reg-query :books/list
                     {:query-fn (fn [_] {:method :get :url "/api/books"})
                      :tags     (fn [_] [[:books :all]])})
      ;; Populate with data but do NOT mark active
      (process-event [:re-frame.query/query-success :books/list {} [{:id 1}]])
      (reset! call-count 0)
      ;; Invalidate
      (process-event [:re-frame.query/invalidate-tags [[:books :all]]])
      (let [qid (util/query-id :books/list {})]
        (is (true? (get-in (app-db) [:re-frame.query/queries qid :stale?]))
            "query is marked stale")
        (is (zero? @call-count)
            "no refetch effect for inactive query")))))

(deftest invalidation-with-multiple-tag-types
  (testing "Exact tag match, partial tag match, and non-matching tags"
    (rfq/reg-query :books/list
                   {:query-fn (fn [_] {})
                    :tags     (fn [_] [[:books :all]])})
    (rfq/reg-query :books/detail
                   {:query-fn (fn [_] {})
                    :tags     (fn [{:keys [id]}] [[:books :id id]])})
    (rfq/reg-query :authors/list
                   {:query-fn (fn [_] {})
                    :tags     (fn [_] [[:authors :all]])})
    ;; Populate all three
    (process-event [:re-frame.query/query-success :books/list {} [{:id 1}]])
    (process-event [:re-frame.query/query-success :books/detail {:id 1} {:title "Dune"}])
    (process-event [:re-frame.query/query-success :authors/list {} [{:name "Herbert"}]])
    (let [books-qid   (util/query-id :books/list {})
          detail-qid  (util/query-id :books/detail {:id 1})
          authors-qid (util/query-id :authors/list {})]
      ;; Invalidate only [:books :all]
      (process-event [:re-frame.query/invalidate-tags [[:books :all]]])
      (is (true? (get-in (app-db) [:re-frame.query/queries books-qid :stale?]))
          "books/list matches [:books :all]")
      (is (false? (get-in (app-db) [:re-frame.query/queries detail-qid :stale?]))
          "books/detail does NOT match [:books :all] — its tag is [:books :id 1]")
      (is (false? (get-in (app-db) [:re-frame.query/queries authors-qid :stale?]))
          "authors/list does NOT match [:books :all]"))))

(deftest invalidation-with-id-specific-tags
  (testing "Invalidating a specific id tag only affects the matching detail query"
    (rfq/reg-query :books/detail
                   {:query-fn (fn [_] {})
                    :tags     (fn [{:keys [id]}] [[:books :id id]])})
    ;; Populate two detail queries
    (process-event [:re-frame.query/query-success :books/detail {:id 1} {:title "Dune"}])
    (process-event [:re-frame.query/query-success :books/detail {:id 2} {:title "Foundation"}])
    (let [qid-1 (util/query-id :books/detail {:id 1})
          qid-2 (util/query-id :books/detail {:id 2})]
      ;; Invalidate only book id 1
      (process-event [:re-frame.query/invalidate-tags [[:books :id 1]]])
      (is (true? (get-in (app-db) [:re-frame.query/queries qid-1 :stale?]))
          "book 1 is invalidated")
      (is (false? (get-in (app-db) [:re-frame.query/queries qid-2 :stale?]))
          "book 2 is not affected"))))

(deftest full-mutation-invalidation-cycle
  (testing "mutation-success → invalidate-tags → refetch active query → new data"
    (rf-test/run-test-sync
     (let [calls (atom [])]
       (rf/reg-fx :test-http (fn [v] (swap! calls conj v)))
       (rfq/set-default-effect-fn!
        (fn [request on-success on-failure]
          {:test-http (assoc request
                             :on-success on-success
                             :on-failure on-failure)}))
       (rfq/reg-query :books/list
                      {:query-fn (fn [_] {:method :get :url "/api/books"})
                       :tags     (fn [_] [[:books :all]])})
       (rfq/reg-mutation :books/create
                         {:mutation-fn (fn [{:keys [title]}]
                                         {:method :post :url "/api/books" :body {:title title}})
                          :invalidates (fn [_] [[:books :all]])})
       ;; Populate list and mark active
       (rf/dispatch [:re-frame.query/query-success :books/list {} [{:id 1}]])
       (rf/dispatch [:re-frame.query/mark-active :books/list {}])
       (reset! calls [])
       ;; Mutation succeeds — run-test-sync resolves the full chain:
       ;; mutation-success → invalidate-tags → refetch-query
       (rf/dispatch [:re-frame.query/mutation-success :books/create {:title "Dune"} {:id 2}])
       ;; Verify mutation state
       (let [mid (util/query-id :books/create {:title "Dune"})]
         (is (= :success (get-in (app-db) [:re-frame.query/mutations mid :status]))
             "mutation is :success"))
       ;; The full chain resolved: query should be refetching
       (let [qid (util/query-id :books/list {})]
         (is (true? (get-in (app-db) [:re-frame.query/queries qid :fetching?]))
             "active query is refetching after mutation-triggered invalidation")
         (is (= :success (get-in (app-db) [:re-frame.query/queries qid :status]))
             "status stays :success during background refetch (stale data visible)")
         (is (= 1 (count @calls))
             "exactly one refetch effect fired")
         (is (= "/api/books" (:url (first @calls))))
         ;; Simulate the refetch completing with updated data
         (rf/dispatch [:re-frame.query/query-success :books/list {} [{:id 1} {:id 2}]])
         (is (= [{:id 1} {:id 2}]
                (get-in (app-db) [:re-frame.query/queries qid :data]))
             "cache now has the updated data"))))))

;; ---------------------------------------------------------------------------
;; Full mutation lifecycle tests
;; ---------------------------------------------------------------------------

(deftest execute-mutation-sets-loading
  (testing "execute-mutation immediately sets :loading before the effect fires"
    (let [captured-status (atom nil)]
      (rf/reg-fx :test-http
                 (fn [_]
                   ;; Capture the mutation status at the moment the effect fires
                   (let [mid (util/query-id :books/create {:title "Dune"})]
                     (reset! captured-status
                             (get-in (app-db) [:re-frame.query/mutations mid :status])))))
      (rfq/set-default-effect-fn!
       (fn [request on-success on-failure]
         {:test-http (assoc request
                            :on-success on-success
                            :on-failure on-failure)}))
      (rfq/reg-mutation :books/create
                        {:mutation-fn (fn [{:keys [title]}]
                                        {:method :post :url "/api/books" :body {:title title}})})
      (process-event [:re-frame.query/execute-mutation :books/create {:title "Dune"}])
      (let [mid (util/query-id :books/create {:title "Dune"})]
        (is (= :loading (get-in (app-db) [:re-frame.query/mutations mid :status]))
            "status is :loading after execute-mutation")
        (is (nil? (get-in (app-db) [:re-frame.query/mutations mid :error]))
            "error is nil during :loading")
        (is (= :loading @captured-status)
            "status was :loading when the effect fired")))))

(deftest mutation-success-stores-response-data
  (testing "mutation-success stores the response data in the mutation state"
    (rfq/reg-mutation :books/create
                      {:mutation-fn (fn [_] {})})
    (process-event [:re-frame.query/mutation-success :books/create {:title "Dune"} {:id 2 :title "Dune"}])
    (let [mid      (util/query-id :books/create {:title "Dune"})
          mutation (get-in (app-db) [:re-frame.query/mutations mid])]
      (is (= :success (:status mutation)))
      (is (= {:id 2 :title "Dune"} (:data mutation))
          "response data is stored")
      (is (nil? (:error mutation))
          "error is cleared on success"))))

(deftest independent-mutations-have-separate-state
  (testing "Two mutations with different params track independent status"
    (rfq/reg-mutation :books/update
                      {:mutation-fn (fn [{:keys [id title]}]
                                      {:method :put :url (str "/api/books/" id) :body {:title title}})})
    ;; Start two mutations
    (process-event [:re-frame.query/execute-mutation :books/update {:id 1 :title "Dune Revised"}])
    (process-event [:re-frame.query/execute-mutation :books/update {:id 2 :title "Foundation Revised"}])
    (let [mid-1 (util/query-id :books/update {:id 1 :title "Dune Revised"})
          mid-2 (util/query-id :books/update {:id 2 :title "Foundation Revised"})]
      ;; Both should be :loading independently
      (is (= :loading (get-in (app-db) [:re-frame.query/mutations mid-1 :status])))
      (is (= :loading (get-in (app-db) [:re-frame.query/mutations mid-2 :status])))
      ;; First one succeeds
      (process-event [:re-frame.query/mutation-success :books/update {:id 1 :title "Dune Revised"} {:id 1}])
      (is (= :success (get-in (app-db) [:re-frame.query/mutations mid-1 :status]))
          "mutation 1 is :success")
      (is (= :loading (get-in (app-db) [:re-frame.query/mutations mid-2 :status]))
          "mutation 2 is still :loading")
      ;; Second one fails
      (process-event [:re-frame.query/mutation-failure :books/update {:id 2 :title "Foundation Revised"} {:status 500}])
      (is (= :success (get-in (app-db) [:re-frame.query/mutations mid-1 :status]))
          "mutation 1 still :success")
      (is (= :error (get-in (app-db) [:re-frame.query/mutations mid-2 :status]))
          "mutation 2 is :error"))))

(deftest mutation-full-lifecycle-loading-to-success
  (testing "Full lifecycle: execute → :loading → success → :success with data"
    (let [captured (atom nil)]
      (rf/reg-fx :test-http (fn [v] (reset! captured v)))
      (rfq/set-default-effect-fn!
       (fn [request on-success on-failure]
         {:test-http (assoc request
                            :on-success on-success
                            :on-failure on-failure)}))
      (rfq/reg-mutation :books/create
                        {:mutation-fn (fn [{:keys [title]}]
                                        {:method :post
                                         :url "/api/books"
                                         :body {:title title}})})
      ;; Execute
      (process-event [:re-frame.query/execute-mutation :books/create {:title "Dune"}])
      (let [mid (util/query-id :books/create {:title "Dune"})]
        (is (= :loading (get-in (app-db) [:re-frame.query/mutations mid :status])))
        ;; Verify the effect was captured with correct callbacks
        (is (= [:re-frame.query/mutation-success :books/create {:title "Dune"}]
               (:on-success @captured)))
        ;; Simulate success
        (process-event [:re-frame.query/mutation-success :books/create {:title "Dune"} {:id 1 :title "Dune"}])
        (let [mutation (get-in (app-db) [:re-frame.query/mutations mid])]
          (is (= :success (:status mutation)))
          (is (= {:id 1 :title "Dune"} (:data mutation)))
          (is (nil? (:error mutation))))))))

(deftest mutation-full-lifecycle-error-then-retry-success
  (testing "Full lifecycle: execute → :error → re-execute → :loading → :success"
    (rfq/reg-mutation :books/create
                      {:mutation-fn (fn [{:keys [title]}]
                                      {:method :post :url "/api/books" :body {:title title}})})
    (let [mid (util/query-id :books/create {:title "Dune"})]
      ;; First attempt fails
      (process-event [:re-frame.query/execute-mutation :books/create {:title "Dune"}])
      (is (= :loading (get-in (app-db) [:re-frame.query/mutations mid :status])))
      (process-event [:re-frame.query/mutation-failure :books/create {:title "Dune"} {:status 500}])
      (is (= :error (get-in (app-db) [:re-frame.query/mutations mid :status])))
      (is (= {:status 500} (get-in (app-db) [:re-frame.query/mutations mid :error])))
      ;; Retry — should go back to :loading
      (process-event [:re-frame.query/execute-mutation :books/create {:title "Dune"}])
      (is (= :loading (get-in (app-db) [:re-frame.query/mutations mid :status]))
          "status resets to :loading on retry")
      (is (nil? (get-in (app-db) [:re-frame.query/mutations mid :error]))
          "error is cleared on retry")
      ;; This time it succeeds
      (process-event [:re-frame.query/mutation-success :books/create {:title "Dune"} {:id 1 :title "Dune"}])
      (is (= :success (get-in (app-db) [:re-frame.query/mutations mid :status])))
      (is (= {:id 1 :title "Dune"} (get-in (app-db) [:re-frame.query/mutations mid :data]))))))

(deftest mutations-do-not-deduplicate
  (testing "Unlike queries, mutations always fire — even with the same params after success"
    (let [call-count (atom 0)]
      (rf/reg-fx :test-http (fn [_] (swap! call-count inc)))
      (rfq/set-default-effect-fn!
       (fn [request on-success on-failure]
         {:test-http (assoc request
                            :on-success on-success
                            :on-failure on-failure)}))
      (rfq/reg-mutation :books/create
                        {:mutation-fn (fn [{:keys [title]}]
                                        {:method :post :url "/api/books" :body {:title title}})})
      ;; First execution
      (process-event [:re-frame.query/execute-mutation :books/create {:title "Dune"}])
      (is (= 1 @call-count) "first mutation fires the effect")
      ;; Complete it
      (process-event [:re-frame.query/mutation-success :books/create {:title "Dune"} {:id 1}])
      (let [mid (util/query-id :books/create {:title "Dune"})]
        (is (= :success (get-in (app-db) [:re-frame.query/mutations mid :status]))))
      ;; Execute the exact same mutation again — should fire again, no dedup
      (process-event [:re-frame.query/execute-mutation :books/create {:title "Dune"}])
      (is (= 2 @call-count)
          "second mutation fires the effect again (no deduplication)")
      (let [mid (util/query-id :books/create {:title "Dune"})]
        (is (= :loading (get-in (app-db) [:re-frame.query/mutations mid :status]))
            "status resets to :loading for the new execution")))))

;; ---------------------------------------------------------------------------
;; Stale-time integration tests
;; ---------------------------------------------------------------------------

(deftest ensure-query-skips-fresh-data
  (testing "ensure-query produces no effect when data is within stale-time"
    (let [call-count (atom 0)]
      (rf/reg-fx :test-http (fn [_] (swap! call-count inc)))
      (rfq/set-default-effect-fn!
       (fn [request on-success on-failure]
         {:test-http (assoc request
                            :on-success on-success
                            :on-failure on-failure)}))
      (rfq/reg-query :books/list
                     {:query-fn      (fn [_] {:method :get :url "/api/books"})
                      :stale-time-ms 60000})
      ;; Populate with fresh data
      (process-event [:re-frame.query/query-success :books/list {} [{:id 1}]])
      (reset! call-count 0)
      ;; ensure-query — data is fresh, should be a no-op
      (process-event [:re-frame.query/ensure-query :books/list {}])
      (let [qid (util/query-id :books/list {})]
        (is (zero? @call-count)
            "no effect produced for fresh data")
        (is (false? (get-in (app-db) [:re-frame.query/queries qid :fetching?]))
            "fetching? remains false")
        (is (= :success (get-in (app-db) [:re-frame.query/queries qid :status]))
            "status remains :success")))))

(deftest refetch-query-ignores-freshness
  (testing "refetch-query always fires, even when data is within stale-time"
    (let [call-count (atom 0)]
      (rf/reg-fx :test-http (fn [_] (swap! call-count inc)))
      (rfq/set-default-effect-fn!
       (fn [request on-success on-failure]
         {:test-http (assoc request
                            :on-success on-success
                            :on-failure on-failure)}))
      (rfq/reg-query :books/list
                     {:query-fn      (fn [_] {:method :get :url "/api/books"})
                      :stale-time-ms 60000})
      ;; Populate with fresh data
      (process-event [:re-frame.query/query-success :books/list {} [{:id 1}]])
      (reset! call-count 0)
      ;; refetch-query — should fire regardless of freshness
      (process-event [:re-frame.query/refetch-query :books/list {}])
      (let [qid (util/query-id :books/list {})]
        (is (= 1 @call-count)
            "effect fires even though data is fresh")
        (is (true? (get-in (app-db) [:re-frame.query/queries qid :fetching?]))
            "fetching? is true")
        (is (= :success (get-in (app-db) [:re-frame.query/queries qid :status]))
            "status stays :success during background refetch")))))

(deftest stale-time-resets-on-success
  (testing "After a successful fetch, the stale-time window restarts from the new fetched-at"
    (let [call-count (atom 0)]
      (rf/reg-fx :test-http (fn [_] (swap! call-count inc)))
      (rfq/set-default-effect-fn!
       (fn [request on-success on-failure]
         {:test-http (assoc request
                            :on-success on-success
                            :on-failure on-failure)}))
      (rfq/reg-query :books/list
                     {:query-fn      (fn [_] {:method :get :url "/api/books"})
                      :stale-time-ms 30000})
      ;; First fetch succeeds at time 1000
      (with-redefs [util/now-ms (constantly 1000)]
        (process-event [:re-frame.query/query-success :books/list {} [{:id 1}]]))
      (let [qid        (util/query-id :books/list {})
            fetched-at (get-in (app-db) [:re-frame.query/queries qid :fetched-at])]
        (is (= 1000 fetched-at) "fetched-at is recorded at controlled time")
        (reset! call-count 0)
        ;; ensure-query at time 31001 (31001 - 1000 = 30001 > stale-time 30000)
        (with-redefs [util/now-ms (constantly 31001)]
          (process-event [:re-frame.query/ensure-query :books/list {}]))
        (is (= 1 @call-count) "stale data triggers a refetch")
        ;; Simulate success at time 31001 — fetched-at should update
        (with-redefs [util/now-ms (constantly 31001)]
          (process-event [:re-frame.query/query-success :books/list {} [{:id 1} {:id 2}]]))
        (let [new-fetched-at (get-in (app-db) [:re-frame.query/queries qid :fetched-at])]
          (is (= 31001 new-fetched-at) "fetched-at is set to the controlled timestamp")
          (is (> new-fetched-at fetched-at) "new fetched-at is more recent than the original")
          ;; Now the data is fresh — ensure-query at 31002 should be a no-op
          ;; (31002 - 31001 = 1ms, well within stale-time of 30000ms)
          (reset! call-count 0)
          (with-redefs [util/now-ms (constantly 31002)]
            (process-event [:re-frame.query/ensure-query :books/list {}]))
          (is (zero? @call-count)
              "data is fresh after refetch — no effect produced"))))))

(deftest ensure-query-fires-when-stale-time-elapsed
  (testing "ensure-query refetches when stale-time has elapsed since last fetch"
    (let [call-count (atom 0)]
      (rf/reg-fx :test-http (fn [_] (swap! call-count inc)))
      (rfq/set-default-effect-fn!
       (fn [request on-success on-failure]
         {:test-http (assoc request
                            :on-success on-success
                            :on-failure on-failure)}))
      (rfq/reg-query :books/list
                     {:query-fn (fn [_] {:method :get
                                         :url "/api/books"})
                      :stale-time-ms 1000})
      ;; Populate and push fetched-at far into the past
      (process-event [:re-frame.query/query-success :books/list {} [{:id 1}]])
      (let [qid (util/query-id :books/list {})]
        (swap! rf-db/app-db assoc-in
               [:re-frame.query/queries qid :fetched-at] 1000)
        (reset! call-count 0)
        ;; ensure-query at time 2001: (2001 - 1000) = 1001 > stale-time-ms 1000
        (with-redefs [util/now-ms (constantly 2001)]
          (process-event [:re-frame.query/ensure-query :books/list {}]))
        (is (= 1 @call-count)
            "effect fires because stale-time has elapsed")
        (is (true? (get-in (app-db) [:re-frame.query/queries qid :fetching?])))))))

(deftest no-stale-time-means-never-auto-stale
  (testing "Without stale-time-ms, a successful query is never considered stale by time"
    (let [call-count (atom 0)]
      (rf/reg-fx :test-http (fn [_] (swap! call-count inc)))
      (rfq/set-default-effect-fn!
       (fn [request on-success on-failure]
         {:test-http (assoc request
                            :on-success on-success
                            :on-failure on-failure)}))
      (rfq/reg-query :books/list
                     {:query-fn (fn [_] {:method :get
                                         :url "/api/books"})})
      ;; Populate and push fetched-at far into the past
      (process-event [:re-frame.query/query-success :books/list {} [{:id 1}]])
      (let [qid (util/query-id :books/list {})]
        (swap! rf-db/app-db assoc-in
               [:re-frame.query/queries qid :fetched-at] 0)
        (reset! call-count 0)
        ;; ensure-query at time 999999 — no stale-time-ms, so NOT stale by time
        (with-redefs [util/now-ms (constantly 999999)]
          (process-event [:re-frame.query/ensure-query :books/list {}]))
        (is (zero? @call-count)
            "no effect — query without stale-time-ms never auto-stales")))))

;; ---------------------------------------------------------------------------
;; Error recovery cycle tests
;; ---------------------------------------------------------------------------

(deftest query-error-then-success-clears-error
  (testing "After a failed query, a successful retry clears the error and restores :success"
    (rfq/reg-query :books/list {:query-fn (fn [_] {})})
    (let [qid (util/query-id :books/list {})]
      ;; Initial failure
      (process-event [:re-frame.query/query-failure :books/list {} {:status 500 :body "Internal Server Error"}])
      (is (= :error (get-in (app-db) [:re-frame.query/queries qid :status])))
      (is (= {:status 500 :body "Internal Server Error"}
             (get-in (app-db) [:re-frame.query/queries qid :error])))
      ;; Retry succeeds
      (process-event [:re-frame.query/query-success :books/list {} [{:id 1}]])
      (let [query (get-in (app-db) [:re-frame.query/queries qid])]
        (is (= :success (:status query))
            "status transitions to :success")
        (is (= [{:id 1}] (:data query))
            "data is stored")
        (is (nil? (:error query))
            "error is cleared on success")
        (is (false? (:fetching? query)))))))

(deftest query-repeated-failures-update-error
  (testing "Each failure updates the error value, not accumulates"
    (rfq/reg-query :books/list {:query-fn (fn [_] {})})
    (let [qid (util/query-id :books/list {})]
      ;; First failure
      (process-event [:re-frame.query/query-failure :books/list {} {:status 500}])
      (is (= {:status 500} (get-in (app-db) [:re-frame.query/queries qid :error])))
      ;; Second failure with different error
      (process-event [:re-frame.query/query-failure :books/list {} {:status 503}])
      (is (= {:status 503} (get-in (app-db) [:re-frame.query/queries qid :error]))
          "error is replaced, not accumulated")
      (is (= :error (get-in (app-db) [:re-frame.query/queries qid :status]))))))

(deftest query-success-then-failure-preserves-data
  (testing "After success then failure, data persists but status is :error"
    (rfq/reg-query :books/list {:query-fn (fn [_] {})})
    (let [qid (util/query-id :books/list {})]
      ;; Success first
      (process-event [:re-frame.query/query-success :books/list {} [{:id 1}]])
      (is (= :success (get-in (app-db) [:re-frame.query/queries qid :status])))
      ;; Then a refetch fails
      (process-event [:re-frame.query/query-failure :books/list {} {:status 502}])
      (let [query (get-in (app-db) [:re-frame.query/queries qid])]
        (is (= :error (:status query))
            "status is :error")
        (is (= {:status 502} (:error query))
            "error is stored")
        (is (= [{:id 1}] (:data query))
            "previous data is preserved across failure")))))

(deftest query-full-error-recovery-cycle
  (testing "Full cycle: success → stale → refetch fails → retry → success with new data"
    (let [call-count (atom 0)]
      (rf/reg-fx :test-http (fn [_] (swap! call-count inc)))
      (rfq/set-default-effect-fn!
       (fn [request on-success on-failure]
         {:test-http (assoc request
                            :on-success on-success
                            :on-failure on-failure)}))
      (rfq/reg-query :books/list
                     {:query-fn      (fn [_] {:method :get :url "/api/books"})
                      :stale-time-ms 1000})
      (let [qid (util/query-id :books/list {})]
        ;; 1. Initial success
        (process-event [:re-frame.query/query-success :books/list {} [{:id 1}]])
        (is (= :success (get-in (app-db) [:re-frame.query/queries qid :status])))
        ;; 2. Make stale: fetched-at=0, now-ms=1001 (past stale-time of 1000)
        (swap! rf-db/app-db assoc-in [:re-frame.query/queries qid :fetched-at] 0)
        (reset! call-count 0)
        ;; 3. ensure-query detects staleness → refetch
        (with-redefs [util/now-ms (constantly 1001)]
          (process-event [:re-frame.query/ensure-query :books/list {}]))
        (is (= 1 @call-count))
        ;; 4. Refetch fails
        (process-event [:re-frame.query/query-failure :books/list {} {:status 500}])
        (is (= :error (get-in (app-db) [:re-frame.query/queries qid :status])))
        (is (= [{:id 1}] (get-in (app-db) [:re-frame.query/queries qid :data]))
            "stale data preserved after failure")
        ;; 5. Retry — error status is stale, so ensure-query triggers
        (reset! call-count 0)
        (process-event [:re-frame.query/ensure-query :books/list {}])
        (is (= 1 @call-count) "retry fires because error status is stale")
        (is (= :loading (get-in (app-db) [:re-frame.query/queries qid :status]))
            "status resets to :loading on retry after error")
        ;; 6. Success with new data
        (process-event [:re-frame.query/query-success :books/list {} [{:id 1} {:id 2}]])
        (let [query (get-in (app-db) [:re-frame.query/queries qid])]
          (is (= :success (:status query)))
          (is (= [{:id 1} {:id 2}] (:data query)))
          (is (nil? (:error query))
              "error is cleared after successful recovery"))))))

(deftest mutation-error-then-retry-success
  (testing "A failed mutation followed by re-execution transitions to :success"
    (rfq/reg-mutation :books/create
                      {:mutation-fn (fn [{:keys [title]}]
                                      {:method :post :url "/api/books" :body {:title title}})})
    (let [mid (util/query-id :books/create {:title "Dune"})]
      ;; First attempt fails
      (process-event [:re-frame.query/execute-mutation :books/create {:title "Dune"}])
      (process-event [:re-frame.query/mutation-failure :books/create {:title "Dune"} {:status 500}])
      (is (= :error (get-in (app-db) [:re-frame.query/mutations mid :status])))
      (is (= {:status 500} (get-in (app-db) [:re-frame.query/mutations mid :error])))
      ;; Retry succeeds
      (process-event [:re-frame.query/execute-mutation :books/create {:title "Dune"}])
      (is (= :loading (get-in (app-db) [:re-frame.query/mutations mid :status]))
          "status resets to :loading")
      (is (nil? (get-in (app-db) [:re-frame.query/mutations mid :error]))
          "error cleared on retry")
      (process-event [:re-frame.query/mutation-success :books/create {:title "Dune"} {:id 1}])
      (is (= :success (get-in (app-db) [:re-frame.query/mutations mid :status])))
      (is (= {:id 1} (get-in (app-db) [:re-frame.query/mutations mid :data])))
      (is (nil? (get-in (app-db) [:re-frame.query/mutations mid :error]))))))

;; ---------------------------------------------------------------------------
;; Registration error handling tests
;; ---------------------------------------------------------------------------

(deftest ensure-query-throws-for-unregistered-key
  (testing "Dispatching ensure-query for an unregistered key throws an error"
    (is (thrown-with-msg?
         #?(:clj clojure.lang.ExceptionInfo :cljs ExceptionInfo)
         #"No query registered for key"
         (process-event [:re-frame.query/ensure-query :nonexistent/query {}])))))

(deftest refetch-query-throws-for-unregistered-key
  (testing "Dispatching refetch-query for an unregistered key throws an error"
    (is (thrown-with-msg?
         #?(:clj clojure.lang.ExceptionInfo :cljs ExceptionInfo)
         #"No query registered for key"
         (process-event [:re-frame.query/refetch-query :nonexistent/query {}])))))

(deftest execute-mutation-throws-for-unregistered-key
  (testing "Dispatching execute-mutation for an unregistered key throws an error"
    (is (thrown-with-msg?
         #?(:clj clojure.lang.ExceptionInfo :cljs ExceptionInfo)
         #"No mutation registered for key"
         (process-event [:re-frame.query/execute-mutation :nonexistent/mutation {}])))))

(deftest subscribe-query-throws-for-unregistered-key
  (testing "Subscribing to an unregistered query key throws because ensure-query is dispatched"
    (rf-test/run-test-sync
     (is (thrown-with-msg?
          #?(:clj clojure.lang.ExceptionInfo :cljs ExceptionInfo)
          #"No query registered for key"
          (rf/subscribe [:re-frame.query/query :nonexistent/query {}]))))))

;; ---------------------------------------------------------------------------
;; Query state shape completeness tests
;; ---------------------------------------------------------------------------

(deftest query-state-nil-before-any-fetch
  (testing "Before any fetch, query state is nil in app-db"
    (rfq/reg-query :books/list {:query-fn (fn [_] {})})
    (let [qid (util/query-id :books/list {})]
      (is (nil? (get-in (app-db) [:re-frame.query/queries qid]))
          "query state does not exist before any event is dispatched"))))

(deftest mutation-state-nil-before-any-execution
  (testing "Before any execution, mutation state is nil in app-db"
    (rfq/reg-mutation :books/create {:mutation-fn (fn [_] {})})
    (let [mid (util/query-id :books/create {})]
      (is (nil? (get-in (app-db) [:re-frame.query/mutations mid]))
          "mutation state does not exist before any event is dispatched"))))

(deftest query-loading-state-shape
  (testing "Full state shape after ensure-query (initial load, no prior data)"
    (rfq/reg-query :books/list {:query-fn (fn [_] {})})
    (process-event [:re-frame.query/ensure-query :books/list {}])
    (let [qid   (util/query-id :books/list {})
          query (get-in (app-db) [:re-frame.query/queries qid])]
      (is (= {:status    :loading
              :data      nil
              :error     nil
              :fetching? true
              :stale?    false
              :active?   false
              :tags      #{}}
             query)))))

(deftest query-success-state-shape
  (testing "Full state shape after query-success"
    (rfq/reg-query :books/list
                   {:query-fn      (fn [_] {})
                    :stale-time-ms 30000
                    :cache-time-ms 300000
                    :tags          (fn [_] [[:books :all]])})
    (process-event [:re-frame.query/query-success :books/list {} [{:id 1 :title "Dune"}]])
    (let [qid   (util/query-id :books/list {})
          query (get-in (app-db) [:re-frame.query/queries qid])]
      (is (= {:status        :success
              :data          [{:id 1 :title "Dune"}]
              :error         nil
              :fetching?     false
              :stale?        false
              :active?       false
              :tags          #{[:books :all]}
              :stale-time-ms 30000
              :cache-time-ms 300000}
             (dissoc query :fetched-at)))
      (is (number? (:fetched-at query))
          "fetched-at is a numeric timestamp"))))

(deftest query-error-state-shape
  (testing "Full state shape after query-failure"
    (rfq/reg-query :books/list {:query-fn (fn [_] {})})
    (process-event [:re-frame.query/query-failure :books/list {} {:status 500 :body "error"}])
    (let [qid   (util/query-id :books/list {})
          query (get-in (app-db) [:re-frame.query/queries qid])]
      (is (= {:status    :error
              :data      nil
              :error     {:status 500 :body "error"}
              :fetching? false
              :stale?    true
              :active?   false
              :tags      #{}}
             query)))))

(deftest query-background-refetch-state-shape
  (testing "Full state shape during a background refetch (stale data visible)"
    (rfq/reg-query :books/list
                   {:query-fn      (fn [_] {})
                    :stale-time-ms 1000
                    :cache-time-ms 300000
                    :tags          (fn [_] [[:books :all]])})
    ;; Initial success
    (process-event [:re-frame.query/query-success :books/list {} [{:id 1}]])
    ;; Make stale: fetched-at=0, now-ms=1001 (past stale-time of 1000)
    (let [qid (util/query-id :books/list {})]
      (swap! rf-db/app-db assoc-in [:re-frame.query/queries qid :fetched-at] 0)
      (with-redefs [util/now-ms (constantly 1001)]
        (process-event [:re-frame.query/ensure-query :books/list {}]))
      (let [query (get-in (app-db) [:re-frame.query/queries qid])]
        (is (= {:status        :success
                :data          [{:id 1}]
                :error         nil
                :fetching?     true
                :stale?        false
                :active?       false
                :tags          #{[:books :all]}
                :stale-time-ms 1000
                :cache-time-ms 300000}
               (dissoc query :fetched-at))
            "status stays :success, fetching? true, stale data preserved")))))

(deftest mutation-loading-state-shape
  (testing "Full state shape after execute-mutation"
    (rfq/reg-mutation :books/create {:mutation-fn (fn [_] {})})
    (process-event [:re-frame.query/execute-mutation :books/create {:title "Dune"}])
    (let [mid      (util/query-id :books/create {:title "Dune"})
          mutation (get-in (app-db) [:re-frame.query/mutations mid])]
      (is (= {:status :loading
              :error  nil}
             mutation)))))

(deftest mutation-success-state-shape
  (testing "Full state shape after mutation-success"
    (rfq/reg-mutation :books/create {:mutation-fn (fn [_] {})})
    (process-event [:re-frame.query/mutation-success :books/create {:title "Dune"} {:id 1 :title "Dune"}])
    (let [mid      (util/query-id :books/create {:title "Dune"})
          mutation (get-in (app-db) [:re-frame.query/mutations mid])]
      (is (= {:status :success
              :data   {:id 1 :title "Dune"}
              :error  nil}
             mutation)))))

(deftest mutation-error-state-shape
  (testing "Full state shape after mutation-failure"
    (rfq/reg-mutation :books/create {:mutation-fn (fn [_] {})})
    (process-event [:re-frame.query/mutation-failure :books/create {:title "Dune"} {:status 422 :body "Unprocessable"}])
    (let [mid      (util/query-id :books/create {:title "Dune"})
          mutation (get-in (app-db) [:re-frame.query/mutations mid])]
      (is (= {:status :error
              :error  {:status 422 :body "Unprocessable"}}
             mutation)))))
