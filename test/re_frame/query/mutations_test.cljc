(ns re-frame.query.mutations-test
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
;; Full mutation lifecycle tests
;; ---------------------------------------------------------------------------

(deftest mutation-lifecycle-test
  (testing "execute-mutation immediately sets :loading before the effect fires"
    (let [captured-status (atom nil)]
      (rf/reg-fx :test-http
        (fn [_]
                   ;; Capture the mutation status at the moment the effect fires
          (let [mid (util/query-id :books/create {:title "Dune"})]
            (reset! captured-status
                    (get-in (h/app-db) [:re-frame.query/mutations mid :status])))))
      (rfq/set-default-effect-fn!
       (fn [request on-success on-failure]
         {:test-http (assoc request
                            :on-success on-success
                            :on-failure on-failure)}))
      (rfq/reg-mutation :books/create
        {:mutation-fn (fn [{:keys [title]}]
                        {:method :post :url "/api/books" :body {:title title}})})
      (h/process-event [:re-frame.query/execute-mutation :books/create {:title "Dune"}])
      (let [mid (util/query-id :books/create {:title "Dune"})]
        (is (= :loading (get-in (h/app-db) [:re-frame.query/mutations mid :status]))
            "status is :loading after execute-mutation")
        (is (nil? (get-in (h/app-db) [:re-frame.query/mutations mid :error]))
            "error is nil during :loading")
        (is (= :loading @captured-status)
            "status was :loading when the effect fired"))))

  (testing "mutation-success stores the response data in the mutation state"
    (rfq/reg-mutation :books/create
      {:mutation-fn (fn [_] {})})
    (h/process-event [:re-frame.query/mutation-success :books/create {:title "Dune"} {} {:id 2 :title "Dune"}])
    (let [mid (util/query-id :books/create {:title "Dune"})
          mutation (get-in (h/app-db) [:re-frame.query/mutations mid])]
      (is (= :success (:status mutation)))
      (is (= {:id 2 :title "Dune"} (:data mutation))
          "response data is stored")
      (is (nil? (:error mutation))
          "error is cleared on success"))))

(deftest independent-mutations-have-separate-state
  (testing "Two mutations with different params track independent status"
    (rfq/set-default-effect-fn! h/noop-effect-fn)
    (rfq/reg-mutation :books/update
      {:mutation-fn (fn [{:keys [id title]}]
                      {:method :put :url (str "/api/books/" id) :body {:title title}})})
    ;; Start two mutations
    (h/process-event [:re-frame.query/execute-mutation :books/update {:id 1 :title "Dune Revised"}])
    (h/process-event [:re-frame.query/execute-mutation :books/update {:id 2 :title "Foundation Revised"}])
    (let [mid-1 (util/query-id :books/update {:id 1 :title "Dune Revised"})
          mid-2 (util/query-id :books/update {:id 2 :title "Foundation Revised"})]
      ;; Both should be :loading independently
      (is (= :loading (get-in (h/app-db) [:re-frame.query/mutations mid-1 :status])))
      (is (= :loading (get-in (h/app-db) [:re-frame.query/mutations mid-2 :status])))
      ;; First one succeeds
      (h/process-event [:re-frame.query/mutation-success :books/update {:id 1 :title "Dune Revised"} {} {:id 1}])
      (is (= :success (get-in (h/app-db) [:re-frame.query/mutations mid-1 :status]))
          "mutation 1 is :success")
      (is (= :loading (get-in (h/app-db) [:re-frame.query/mutations mid-2 :status]))
          "mutation 2 is still :loading")
      ;; Second one fails
      (h/process-event [:re-frame.query/mutation-failure :books/update {:id 2 :title "Foundation Revised"} {} {:status 500}])
      (is (= :success (get-in (h/app-db) [:re-frame.query/mutations mid-1 :status]))
          "mutation 1 still :success")
      (is (= :error (get-in (h/app-db) [:re-frame.query/mutations mid-2 :status]))
          "mutation 2 is :error"))))

(deftest mutation-full-lifecycle-test
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
      (h/process-event [:re-frame.query/execute-mutation :books/create {:title "Dune"}])
      (let [mid (util/query-id :books/create {:title "Dune"})]
        (is (= :loading (get-in (h/app-db) [:re-frame.query/mutations mid :status])))
        ;; Verify the effect was captured with correct callbacks
        (is (= [:re-frame.query/mutation-success :books/create {:title "Dune"} {}]
               (:on-success @captured)))
        ;; Simulate success
        (h/process-event [:re-frame.query/mutation-success :books/create {:title "Dune"} {} {:id 1 :title "Dune"}])
        (let [mutation (get-in (h/app-db) [:re-frame.query/mutations mid])]
          (is (= :success (:status mutation)))
          (is (= {:id 1 :title "Dune"} (:data mutation)))
          (is (nil? (:error mutation)))))))

  (testing "Full lifecycle: execute → :error → re-execute → :loading → :success"
    (rfq/reg-mutation :books/create
      {:mutation-fn (fn [{:keys [title]}]
                      {:method :post :url "/api/books" :body {:title title}})})
    (let [mid (util/query-id :books/create {:title "Dune"})]
      ;; First attempt fails
      (h/process-event [:re-frame.query/execute-mutation :books/create {:title "Dune"}])
      (is (= :loading (get-in (h/app-db) [:re-frame.query/mutations mid :status])))
      (h/process-event [:re-frame.query/mutation-failure :books/create {:title "Dune"} {} {:status 500}])
      (is (= :error (get-in (h/app-db) [:re-frame.query/mutations mid :status])))
      (is (= {:status 500} (get-in (h/app-db) [:re-frame.query/mutations mid :error])))
      ;; Retry — should go back to :loading
      (h/process-event [:re-frame.query/execute-mutation :books/create {:title "Dune"}])
      (is (= :loading (get-in (h/app-db) [:re-frame.query/mutations mid :status]))
          "status resets to :loading on retry")
      (is (nil? (get-in (h/app-db) [:re-frame.query/mutations mid :error]))
          "error is cleared on retry")
      ;; This time it succeeds
      (h/process-event [:re-frame.query/mutation-success :books/create {:title "Dune"} {} {:id 1 :title "Dune"}])
      (is (= :success (get-in (h/app-db) [:re-frame.query/mutations mid :status])))
      (is (= {:id 1 :title "Dune"} (get-in (h/app-db) [:re-frame.query/mutations mid :data]))))))

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
      (h/process-event [:re-frame.query/execute-mutation :books/create {:title "Dune"}])
      (is (= 1 @call-count) "first mutation fires the effect")
      ;; Complete it
      (h/process-event [:re-frame.query/mutation-success :books/create {:title "Dune"} {} {:id 1}])
      (let [mid (util/query-id :books/create {:title "Dune"})]
        (is (= :success (get-in (h/app-db) [:re-frame.query/mutations mid :status]))))
      ;; Execute the exact same mutation again — should fire again, no dedup
      (h/process-event [:re-frame.query/execute-mutation :books/create {:title "Dune"}])
      (is (= 2 @call-count)
          "second mutation fires the effect again (no deduplication)")
      (let [mid (util/query-id :books/create {:title "Dune"})]
        (is (= :loading (get-in (h/app-db) [:re-frame.query/mutations mid :status]))
            "status resets to :loading for the new execution")))))

;; ---------------------------------------------------------------------------
;; Mutation lifecycle hooks tests
;; ---------------------------------------------------------------------------

(deftest mutation-on-start-hooks-test
  (testing "on-start hooks dispatch with params before the effect fires"
    (rf-test/run-test-sync
     (let [hook-calls (atom [])]
       (rf/reg-event-db :test/on-start
         (fn [db [_ params]]
           (swap! hook-calls conj {:event :on-start :params params})
           db))
       (rfq/set-default-effect-fn! h/noop-effect-fn)
       (rfq/reg-mutation :books/create {:mutation-fn (fn [_] {})})
       (rf/dispatch [:re-frame.query/execute-mutation :books/create {:title "Dune"}
                     {:on-start [[:test/on-start]]}])
       (is (= 1 (count @hook-calls)))
       (is (= {:event :on-start :params {:title "Dune"}}
              (first @hook-calls)))))))

(deftest mutation-on-success-hooks-test
  (testing "on-success hooks dispatch with params and response data"
    (rf-test/run-test-sync
     (let [hook-calls (atom [])]
       (rf/reg-event-db :test/on-success
         (fn [db [_ label params data]]
           (swap! hook-calls conj {:label label :params params :data data})
           db))
       (rfq/set-default-effect-fn! h/noop-effect-fn)
       (rfq/reg-mutation :books/create {:mutation-fn (fn [_] {})})
        ;; Execute with on-success hooks
       (rf/dispatch [:re-frame.query/execute-mutation :books/create {:title "Dune"}
                     {:on-success [[:test/on-success "hook1"]]}])
        ;; Simulate success — hooks arg is carried through
       (rf/dispatch [:re-frame.query/mutation-success :books/create {:title "Dune"}
                     {:on-success [[:test/on-success "hook1"]]}
                     {:id 1 :title "Dune"}])
       (is (= 1 (count @hook-calls)))
       (is (= {:label "hook1" :params {:title "Dune"} :data {:id 1 :title "Dune"}}
              (first @hook-calls)))))))

(deftest mutation-on-failure-hooks-test
  (testing "on-failure hooks dispatch with params and error"
    (rf-test/run-test-sync
     (let [hook-calls (atom [])]
       (rf/reg-event-db :test/on-failure
         (fn [db [_ params error]]
           (swap! hook-calls conj {:params params :error error})
           db))
       (rfq/set-default-effect-fn! h/noop-effect-fn)
       (rfq/reg-mutation :books/create {:mutation-fn (fn [_] {})})
       (rf/dispatch [:re-frame.query/execute-mutation :books/create {:title "Dune"}
                     {:on-failure [[:test/on-failure]]}])
       (rf/dispatch [:re-frame.query/mutation-failure :books/create {:title "Dune"}
                     {:on-failure [[:test/on-failure]]}
                     {:status 500}])
       (is (= 1 (count @hook-calls)))
       (is (= {:params {:title "Dune"} :error {:status 500}}
              (first @hook-calls)))))))

(deftest mutation-multiple-hooks-test
  (testing "multiple on-start hooks each receive params"
    (rf-test/run-test-sync
     (let [calls (atom [])]
       (rf/reg-event-db :test/hook-a
         (fn [db [_ params]] (swap! calls conj {:hook :a :params params}) db))
       (rf/reg-event-db :test/hook-b
         (fn [db [_ extra params]] (swap! calls conj {:hook :b :extra extra :params params}) db))
       (rfq/set-default-effect-fn! h/noop-effect-fn)
       (rfq/reg-mutation :books/create {:mutation-fn (fn [_] {})})
       (rf/dispatch [:re-frame.query/execute-mutation :books/create {:title "Dune"}
                     {:on-start [[:test/hook-a] [:test/hook-b "extra-arg"]]}])
       (is (= 2 (count @calls)))
       (is (= {:hook :a :params {:title "Dune"}} (first @calls)))
       (is (= {:hook :b :extra "extra-arg" :params {:title "Dune"}} (second @calls))))))

  (testing "multiple on-success hooks each receive params and response"
    (rf-test/run-test-sync
     (let [calls (atom [])]
       (rf/reg-event-db :test/success-a
         (fn [db [_ params data]] (swap! calls conj {:hook :a :params params :data data}) db))
       (rf/reg-event-db :test/success-b
         (fn [db [_ extra params data]] (swap! calls conj {:hook :b :extra extra :params params :data data}) db))
       (rfq/set-default-effect-fn! h/noop-effect-fn)
       (rfq/reg-mutation :books/create {:mutation-fn (fn [_] {})})
       (rf/dispatch [:re-frame.query/execute-mutation :books/create {:title "Dune"}
                     {:on-success [[:test/success-a] [:test/success-b "ctx"]]}])
       (rf/dispatch [:re-frame.query/mutation-success :books/create {:title "Dune"}
                     {:on-success [[:test/success-a] [:test/success-b "ctx"]]}
                     {:id 1 :title "Dune"}])
       (is (= 2 (count @calls)))
       (is (= {:hook :a :params {:title "Dune"} :data {:id 1 :title "Dune"}} (first @calls)))
       (is (= {:hook :b :extra "ctx" :params {:title "Dune"} :data {:id 1 :title "Dune"}} (second @calls))))))

  (testing "multiple on-failure hooks each receive params and error"
    (rf-test/run-test-sync
     (let [calls (atom [])]
       (rf/reg-event-db :test/fail-a
         (fn [db [_ params error]] (swap! calls conj {:hook :a :params params :error error}) db))
       (rf/reg-event-db :test/fail-b
         (fn [db [_ extra params error]] (swap! calls conj {:hook :b :extra extra :params params :error error}) db))
       (rfq/set-default-effect-fn! h/noop-effect-fn)
       (rfq/reg-mutation :books/create {:mutation-fn (fn [_] {})})
       (rf/dispatch [:re-frame.query/execute-mutation :books/create {:title "Dune"}
                     {:on-failure [[:test/fail-a] [:test/fail-b "ctx"]]}])
       (rf/dispatch [:re-frame.query/mutation-failure :books/create {:title "Dune"}
                     {:on-failure [[:test/fail-a] [:test/fail-b "ctx"]]}
                     {:status 500}])
       (is (= 2 (count @calls)))
       (is (= {:hook :a :params {:title "Dune"} :error {:status 500}} (first @calls)))
       (is (= {:hook :b :extra "ctx" :params {:title "Dune"} :error {:status 500}} (second @calls)))))))

(deftest mutation-hooks-optional-test
  (testing "omitting opts works exactly as before (backwards compatible)"
    (let [captured (atom nil)]
      (rf/reg-fx :test-http (fn [v] (reset! captured v)))
      (rfq/set-default-effect-fn!
       (fn [request on-success on-failure]
         {:test-http (assoc request
                            :on-success on-success
                            :on-failure on-failure)}))
      (rfq/reg-mutation :books/create
        {:mutation-fn (fn [{:keys [title]}]
                        {:method :post :url "/api/books" :body {:title title}})})
      ;; No opts — old-style 3-arg dispatch
      (h/process-event [:re-frame.query/execute-mutation :books/create {:title "Dune"}])
      (is (= {:method :post
              :url "/api/books"
              :body {:title "Dune"}
              :on-success [:re-frame.query/mutation-success :books/create {:title "Dune"} {}]
              :on-failure [:re-frame.query/mutation-failure :books/create {:title "Dune"} {}]}
             @captured)))))

(deftest optimistic-update-full-cycle-test
  (testing "Full optimistic update: on-start patches cache, on-failure rolls back"
    (rf-test/run-test-sync
     (let [snapshot (atom nil)]
        ;; Register hook events
       (rf/reg-event-fx :test/optimistic-patch
         (fn [{:keys [db]} [_ params]]
           (let [qid [:books/list {}]
                 old (get-in db [:re-frame.query/queries qid :data])
                 new (conj (vec old) {:id 99 :title (:title params) :optimistic true})]
             (reset! snapshot old)
             {:dispatch [:re-frame.query/set-query-data :books/list {} new]})))
       (rf/reg-event-fx :test/rollback
         (fn [_ [_ _params _error]]
           {:dispatch [:re-frame.query/set-query-data :books/list {} @snapshot]}))
        ;; Setup
       (rfq/set-default-effect-fn! h/noop-effect-fn)
       (rfq/reg-query :books/list {:query-fn (fn [_] {})})
       (rfq/reg-mutation :books/create {:mutation-fn (fn [_] {})})
        ;; Populate initial data
       (rf/dispatch [:re-frame.query/query-success :books/list {} [{:id 1 :title "Dune"}]])
       (let [qid [:books/list {}]]
         (is (= [{:id 1 :title "Dune"}] (get-in (h/app-db) [:re-frame.query/queries qid :data])))
          ;; Execute mutation with optimistic hooks
         (rf/dispatch [:re-frame.query/execute-mutation :books/create {:title "New Book"}
                       {:on-start [[:test/optimistic-patch]]
                        :on-failure [[:test/rollback]]}])
          ;; on-start fired → cache was optimistically patched
         (is (= 2 (count (get-in (h/app-db) [:re-frame.query/queries qid :data])))
             "cache has optimistic entry")
         (is (= "New Book" (:title (last (get-in (h/app-db) [:re-frame.query/queries qid :data]))))
             "optimistic entry has correct title")
          ;; Mutation fails → on-failure fires → rollback
         (rf/dispatch [:re-frame.query/mutation-failure :books/create {:title "New Book"}
                       {:on-failure [[:test/rollback]]}
                       {:status 500}])
         (is (= [{:id 1 :title "Dune"}] (get-in (h/app-db) [:re-frame.query/queries qid :data]))
             "cache rolled back to snapshot after failure"))))))
