(ns re-frame.query-test
  (:require
   [clojure.test :refer [deftest is testing use-fixtures]]
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
    ;; Make it stale by pushing fetched-at into the past
    (let [qid (util/query-id :books/list {})]
      (swap! rf-db/app-db assoc-in [:re-frame.query/queries qid :fetched-at] 0)
      (process-event [:re-frame.query/ensure-query :books/list {}])
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
