(ns re-frame.query.util-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [re-frame.query.util :as util]))

(deftest query-id-test
  (testing "creates a tuple of key and params"
    (is (= [:todos/list {:user-id 42}]
           (util/query-id :todos/list {:user-id 42}))))

  (testing "nil params default to empty map"
    (is (= [:todos/list {}]
           (util/query-id :todos/list nil)))))

(deftest stale?-test
  (testing "nil query is stale"
    (is (true? (util/stale? nil 1000))))

  (testing "explicitly stale query"
    (is (true? (util/stale? {:stale? true :fetched-at 999} 1000))))

  (testing "query within stale-time is not stale"
    (is (false? (util/stale?
                 {:stale? false
                  :stale-time-ms 30000
                  :fetched-at 990}
                 1000))))

  (testing "query past stale-time is stale"
    (is (true? (util/stale?
                {:stale? false
                 :stale-time-ms 30000
                 :fetched-at 100}
                100000))))

  (testing "query without stale-time-ms and not marked stale is not stale"
    (is (false? (util/stale?
                 {:stale? false
                  :fetched-at 100}
                 100000))))

  (testing "errored query is stale"
    (is (true? (util/stale?
                {:stale? false
                 :status :error
                 :error {:status 500}}
                1000))))

  (testing "successful query is not stale"
    (is (false? (util/stale?
                 {:stale? false
                  :status :success
                  :fetched-at 990
                  :stale-time-ms 30000}
                 1000)))))

(deftest tag-match?-test
  (testing "matching tag"
    (is (true? (util/tag-match?
                #{[:todos :user 42] [:todos :all]}
                [[:todos :user 42]]))))

  (testing "no matching tag"
    (is (false? (util/tag-match?
                 #{[:todos :user 42]}
                 [[:todos :user 99]]))))

  (testing "empty query tags"
    (is (false? (util/tag-match? #{} [[:todos :all]]))))

  (testing "empty invalidation tags"
    (is (false? (util/tag-match? #{[:todos :all]} []))))

  (testing "nil tags"
    (is (false? (util/tag-match? nil [[:todos :all]])))
    (is (false? (util/tag-match? #{[:todos :all]} nil)))))

(deftest parse-result-event-test
  (testing "query-success"
    (is (= {:event-id :re-frame.query/query-success
            :k :todos/list
            :params {:user-id 42}
            :data [{:id 1}]}
           (util/parse-result-event
            [:re-frame.query/query-success :todos/list {:user-id 42} [{:id 1}]]))))

  (testing "query-failure"
    (is (= {:event-id :re-frame.query/query-failure
            :k :todos/list
            :params {:user-id 42}
            :error {:status 500}}
           (util/parse-result-event
            [:re-frame.query/query-failure :todos/list {:user-id 42} {:status 500}]))))

  (testing "infinite-page-success — initial page (mode = nil)"
    (is (= {:event-id :re-frame.query/infinite-page-success
            :k :feed/items
            :params {}
            :mode nil
            :data {:items [1 2] :next 1}}
           (util/parse-result-event
            [:re-frame.query/infinite-page-success :feed/items {} nil
             {:items [1 2] :next 1}]))))

  (testing "infinite-page-success — :append"
    (is (= {:event-id :re-frame.query/infinite-page-success
            :k :feed/items
            :params {}
            :mode :append
            :data {:items [3 4] :next 2}}
           (util/parse-result-event
            [:re-frame.query/infinite-page-success :feed/items {} :append
             {:items [3 4] :next 2}]))))

  (testing "infinite-page-success — :prepend"
    (is (= :prepend
           (:mode (util/parse-result-event
                   [:re-frame.query/infinite-page-success :feed/items {} :prepend
                    {:items [-1 0] :prev nil}])))))

  (testing "infinite-page-failure"
    (is (= {:event-id :re-frame.query/infinite-page-failure
            :k :feed/items
            :params {}
            :error {:status 503}}
           (util/parse-result-event
            [:re-frame.query/infinite-page-failure :feed/items {} {:status 503}]))))

  (testing "unrecognized event returns nil"
    (is (nil? (util/parse-result-event
               [:re-frame.query/ensure-query :todos/list {}])))
    (is (nil? (util/parse-result-event
               [:my.app/some-event :foo :bar])))
    (is (nil? (util/parse-result-event [])))
    (is (nil? (util/parse-result-event nil)))))
