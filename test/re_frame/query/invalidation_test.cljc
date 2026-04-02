(ns re-frame.query.invalidation-test
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
;; Invalidation tests
;; ---------------------------------------------------------------------------

(deftest invalidate-tags-marks-matching-queries-stale
  (testing "invalidate-tags marks queries with matching tags as stale"
    (rfq/reg-query :books/list
      {:query-fn (fn [_] {})
       :tags (fn [_] [[:books :all]])})
    ;; Set up a cached query
    (h/process-event [:re-frame.query/query-success :books/list {} [{:id 1}]])
    ;; Invalidate
    (h/process-event [:re-frame.query/invalidate-tags [[:books :all]]])
    (let [qid (util/query-id :books/list {})
          query (get-in (h/app-db) [:re-frame.query/queries qid])]
      (is (true? (:stale? query))))))

;; ---------------------------------------------------------------------------
;; Invalidation refetch behavior tests
;; ---------------------------------------------------------------------------

(deftest invalidation-refetch-behavior-test
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
          :tags (fn [_] [[:books :all]])})
       (rfq/reg-query :books/detail
         {:query-fn (fn [{:keys [id]}] {:method :get :url (str "/api/books/" id)})
          :tags (fn [{:keys [id]}] [[:books :all] [:books :id id]])})
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
       (let [list-qid (util/query-id :books/list {})
             detail-qid (util/query-id :books/detail {:id 1})]
          ;; The active query was refetched automatically (stale? reset to false)
         (is (true? (get-in (h/app-db) [:re-frame.query/queries list-qid :fetching?]))
             "active query is now refetching")
         (is (false? (get-in (h/app-db) [:re-frame.query/queries list-qid :stale?]))
             "stale? reset to false by the refetch")
          ;; The inactive query is just marked stale — no refetch
         (is (true? (get-in (h/app-db) [:re-frame.query/queries detail-qid :stale?]))
             "inactive query is marked stale")
         (is (false? (get-in (h/app-db) [:re-frame.query/queries detail-qid :fetching?]))
             "inactive query is NOT refetching")
          ;; Only the active query triggered a refetch effect
         (is (= 1 (count @calls))
             "only one refetch effect fired (for the active query)")
         (is (= "/api/books" (:url (first @calls)))
             "the refetch effect is for the active query")))))

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
         :tags (fn [_] [[:books :all]])})
      ;; Populate with data but do NOT mark active
      (h/process-event [:re-frame.query/query-success :books/list {} [{:id 1}]])
      (reset! call-count 0)
      ;; Invalidate
      (h/process-event [:re-frame.query/invalidate-tags [[:books :all]]])
      (let [qid (util/query-id :books/list {})]
        (is (true? (get-in (h/app-db) [:re-frame.query/queries qid :stale?]))
            "query is marked stale")
        (is (zero? @call-count)
            "no refetch effect for inactive query")))))

;; ---------------------------------------------------------------------------
;; Invalidation tag matching tests
;; ---------------------------------------------------------------------------

(deftest invalidation-tag-matching-test
  (testing "Exact tag match, partial tag match, and non-matching tags"
    (rfq/reg-query :books/list
      {:query-fn (fn [_] {})
       :tags (fn [_] [[:books :all]])})
    (rfq/reg-query :books/detail
      {:query-fn (fn [_] {})
       :tags (fn [{:keys [id]}] [[:books :id id]])})
    (rfq/reg-query :authors/list
      {:query-fn (fn [_] {})
       :tags (fn [_] [[:authors :all]])})
    ;; Populate all three
    (h/process-event [:re-frame.query/query-success :books/list {} [{:id 1}]])
    (h/process-event [:re-frame.query/query-success :books/detail {:id 1} {:title "Dune"}])
    (h/process-event [:re-frame.query/query-success :authors/list {} [{:name "Herbert"}]])
    (let [books-qid (util/query-id :books/list {})
          detail-qid (util/query-id :books/detail {:id 1})
          authors-qid (util/query-id :authors/list {})]
      ;; Invalidate only [:books :all]
      (h/process-event [:re-frame.query/invalidate-tags [[:books :all]]])
      (is (true? (get-in (h/app-db) [:re-frame.query/queries books-qid :stale?]))
          "books/list matches [:books :all]")
      (is (false? (get-in (h/app-db) [:re-frame.query/queries detail-qid :stale?]))
          "books/detail does NOT match [:books :all] — its tag is [:books :id 1]")
      (is (false? (get-in (h/app-db) [:re-frame.query/queries authors-qid :stale?]))
          "authors/list does NOT match [:books :all]")))

  (testing "Invalidating a specific id tag only affects the matching detail query"
    (rfq/reg-query :books/detail
      {:query-fn (fn [_] {})
       :tags (fn [{:keys [id]}] [[:books :id id]])})
    ;; Populate two detail queries
    (h/process-event [:re-frame.query/query-success :books/detail {:id 1} {:title "Dune"}])
    (h/process-event [:re-frame.query/query-success :books/detail {:id 2} {:title "Foundation"}])
    (let [qid-1 (util/query-id :books/detail {:id 1})
          qid-2 (util/query-id :books/detail {:id 2})]
      ;; Invalidate only book id 1
      (h/process-event [:re-frame.query/invalidate-tags [[:books :id 1]]])
      (is (true? (get-in (h/app-db) [:re-frame.query/queries qid-1 :stale?]))
          "book 1 is invalidated")
      (is (false? (get-in (h/app-db) [:re-frame.query/queries qid-2 :stale?]))
          "book 2 is not affected"))))

;; ---------------------------------------------------------------------------
;; Full mutation invalidation cycle test
;; ---------------------------------------------------------------------------

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
          :tags (fn [_] [[:books :all]])})
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
       (rf/dispatch [:re-frame.query/mutation-success :books/create {:title "Dune"} {} {:id 2}])
        ;; Verify mutation state
       (let [mid (util/query-id :books/create {:title "Dune"})]
         (is (= :success (get-in (h/app-db) [:re-frame.query/mutations mid :status]))
             "mutation is :success"))
        ;; The full chain resolved: query should be refetching
       (let [qid (util/query-id :books/list {})]
         (is (true? (get-in (h/app-db) [:re-frame.query/queries qid :fetching?]))
             "active query is refetching after mutation-triggered invalidation")
         (is (= :success (get-in (h/app-db) [:re-frame.query/queries qid :status]))
             "status stays :success during background refetch (stale data visible)")
         (is (= 1 (count @calls))
             "exactly one refetch effect fired")
         (is (= "/api/books" (:url (first @calls))))
          ;; Simulate the refetch completing with updated data
         (rf/dispatch [:re-frame.query/query-success :books/list {} [{:id 1} {:id 2}]])
         (is (= [{:id 1} {:id 2}]
                (get-in (h/app-db) [:re-frame.query/queries qid :data]))
             "cache now has the updated data"))))))

;; ---------------------------------------------------------------------------
;; Invalidate tags only refetches matched queries test
;; ---------------------------------------------------------------------------

(deftest invalidate-tags-only-refetches-matched-queries
  (testing "unmatched active queries are NOT refetched"
    (rf-test/run-test-sync
     (let [calls (atom [])]
       (rf/reg-fx :test-http (fn [v] (swap! calls conj v)))
       (rfq/set-default-effect-fn!
        (fn [request on-success on-failure]
          {:test-http (assoc request
                             :on-success on-success
                             :on-failure on-failure)}))
       (rfq/reg-query :items/table
         {:query-fn (fn [_] {:method :get :url "/api/items"})
          :tags (fn [_] [[:items :table]])})
       (rfq/reg-query :items/stats
         {:query-fn (fn [_] {:method :get :url "/api/items/stats"})
          :tags (fn [_] [[:items :stats]])})
       ;; Populate both and mark active
       (rf/dispatch [:re-frame.query/query-success :items/table {} [{:id 1}]])
       (rf/dispatch [:re-frame.query/mark-active :items/table {}])
       (rf/dispatch [:re-frame.query/query-success :items/stats {} {:count 10}])
       (rf/dispatch [:re-frame.query/mark-active :items/stats {}])
       (reset! calls [])
       ;; Invalidate only [:items :table]
       (rf/dispatch [:re-frame.query/invalidate-tags [[:items :table]]])
       (let [table-qid (util/query-id :items/table {})
             stats-qid (util/query-id :items/stats {})]
         ;; We can't assert :stale? true here. run-test-sync processes
         ;; dispatches synchronously, so the chain is:
         ;;   1. invalidate-tags sets :stale? true
         ;;   2. invalidate-tags dispatches refetch-query
         ;;   3. refetch-query runs immediately, sets :stale? false + :fetching? true
         ;; By the time we assert, step 3 has already happened.
         ;; Instead we verify :fetching? — proof that refetch was triggered.
         (is (true? (get-in (h/app-db) [:re-frame.query/queries table-qid :fetching?]))
             "table is being refetched")
         (is (false? (get-in (h/app-db) [:re-frame.query/queries stats-qid :stale?]))
             "stats is NOT marked stale")
         (is (= 1 (count @calls))
             "exactly one refetch — only the matched query")
         (is (= "/api/items" (:url (first @calls)))
             "refetch is for the table query, not stats"))))))
