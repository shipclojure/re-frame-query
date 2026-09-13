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

(deftest normalize-hook-events-test
  (testing "single event vector is wrapped"
    (is (= [[:my/hook]] (util/normalize-hook-events [:my/hook])))
    (is (= [[:my/hook "extra" 42]]
           (util/normalize-hook-events [:my/hook "extra" 42]))))

  (testing "collection of event vectors is returned as a vector"
    (is (= [[:my/hook-a] [:my/hook-b "extra"]]
           (util/normalize-hook-events [[:my/hook-a] [:my/hook-b "extra"]])))
    (is (= [[:my/hook-a]]
           (util/normalize-hook-events (list [:my/hook-a])))))

  (testing "nil and empty are no hooks"
    (is (= [] (util/normalize-hook-events nil)))
    (is (= [] (util/normalize-hook-events [])))))

(defn- normalize-ex
  "Runs `normalize-payload` with `args`, returning the thrown ex-info (or nil)."
  [event-id args positional-keys opts]
  (try
    (util/normalize-payload event-id args positional-keys opts)
    nil
    (catch #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core/ExceptionInfo) e e)))

(deftest normalize-payload-test
  (testing "map form passes through"
    (is (= {:query :a :params {:p 1}}
           (util/normalize-payload :rfq/evt [{:query :a :params {:p 1}}] [:query :params]))))

  (testing "positional form produces the same canonical map"
    (is (= {:query :a :params {:p 1}}
           (util/normalize-payload :rfq/evt [:a {:p 1}] [:query :params])))
    (is (= (util/normalize-payload :rfq/evt [{:query :a :params {:p 1}}] [:query :params])
           (util/normalize-payload :rfq/evt [:a {:p 1}] [:query :params]))))

  (testing "positional form with fewer args leaves :params absent/nil"
    (is (= {:query :a}
           (util/normalize-payload :rfq/evt [:a] [:query :params]))))

  (testing "map form missing :params is passed through"
    (is (= {:query :a}
           (util/normalize-payload :rfq/evt [{:query :a}] [:query :params]))))

  (testing "no-params events get no :params injected"
    (is (= {:tags [[:todos]]}
           (util/normalize-payload :rfq/evt [[[:todos]]] [:tags])))
    (is (= {:tags [[:todos]]}
           (util/normalize-payload :rfq/evt [{:tags [[:todos]]}] [:tags]))))

  (testing "three-slot events"
    (is (= {:query :a :params {:p 1} :data [1 2]}
           (util/normalize-payload :rfq/evt [:a {:p 1} [1 2]] [:query :params :data])))
    (is (= {:query :a :params {:p 1} :data [1 2]}
           (util/normalize-payload :rfq/evt [{:query :a :params {:p 1} :data [1 2]}]
                                   [:query :params :data]))))

  (testing "extra keys pass through"
    (is (= {:query :a :params {} :custom 1}
           (util/normalize-payload :rfq/evt [{:query :a :params {} :custom 1}] [:query :params]))))

  (testing "map form missing the identity key throws"
    (let [ex (normalize-ex :rfq/ensure-query [{:page 1}] [:query :params] {})]
      (is (some? ex))
      (is (re-find #":query" (ex-message ex)))
      (is (re-find #"positional" (ex-message ex)))
      (is (= {:event-id :rfq/ensure-query :payload {:page 1}} (ex-data ex)))))

  (testing "non-keyword identity value throws"
    (is (some? (normalize-ex :rfq/evt [{:query "a"}] [:query :params] {})))
    (is (some? (normalize-ex :rfq/evt [{:mutation "a"}] [:mutation :params] {}))))

  (testing "hook keys pass through unless explicitly rejected"
    (is (= {:mutation :a :on-success [:evt]}
           (util/normalize-payload :rfq/execute-mutation
                                   [{:mutation :a :on-success [:evt]}]
                                   [:mutation :params]))))

  (testing "reject-keys — map form throws, positional form is never validated"
    (let [opts {:reject-keys util/mutation-only-hook-keys}
          ex (normalize-ex :rfq/ensure-query [{:query :a :on-success [:evt]}] [:query :params] opts)]
      (is (some? ex))
      (is (= #{:on-success} (:rejected-keys (ex-data ex))))
      (is (= :rfq/ensure-query (:event-id (ex-data ex))))
      (is (= {:query :a :params {:on-success [:evt]}}
             (util/normalize-payload :rfq/ensure-query [:a {:on-success [:evt]}]
                                     [:query :params] opts))))))

(deftest flatten-opts-test
  (testing "positional trailing opts are lifted to the top level"
    (is (= {:query :a :params {} :sub-id 1 :polling-interval-ms 500}
           (util/flatten-opts {:query :a :params {} :opts {:sub-id 1 :polling-interval-ms 500}}))))

  (testing "a map-form payload without :opts is returned unchanged"
    (is (= {:query :a :params {} :sub-id 1}
           (util/flatten-opts {:query :a :params {} :sub-id 1}))))

  (testing "nil opts (positional form with no trailing map) is a no-op"
    (is (= {:query :a :params {}}
           (util/flatten-opts {:query :a :params {} :opts nil}))))

  (testing "named slots win over a stray identity key in the opts bag"
    (is (= {:query :a :params {:p 1} :sub-id 1}
           (util/flatten-opts {:query :a :params {:p 1} :opts {:query :other :sub-id 1}})))))

(deftest mutation-only-hook-keys-test
  (testing "the set is exactly the mutation lifecycle hook keys"
    (is (= #{:on-start :on-success :on-failure} util/mutation-only-hook-keys))))
