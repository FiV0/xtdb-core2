(ns core2.bench.auctionmark-test
  (:require [clojure.test :as t]
            [core2.bench :as b]
            [core2.bench.auctionmark :as am]
            [core2.test-util :as tu :refer [*node*]]
            [core2.api :as c2]
            [core2.bench.core2 :as bcore2])
  (:import (java.time Clock)
           (java.util Random)
           (java.util.concurrent ConcurrentHashMap)))

(t/use-fixtures :each tu/with-node)

(defn- ->worker [node]
  (let [clock (Clock/systemUTC)
        domain-state (ConcurrentHashMap.)
        custom-state (ConcurrentHashMap.)
        root-random (Random. 112)
        reports (atom [])
        worker (b/->Worker node root-random domain-state custom-state clock reports)]
    worker))

(t/deftest generate-user-test
  (let [worker (->worker *node*)
        tx! (bcore2/generate worker am/generate-user 1 true)]
    (tu/then-await-tx tx! *node*)
    (t/is (= {:count-id 1} (first (c2/datalog-query *node* '{:find [(count id)]
                                                             :where [[id :_table :user]
                                                                     [id :u_id]]}))))
    (t/is (= "u_0" (b/sample-flat worker am/user-id)))))

(t/deftest generate-categories-test
  (let [worker (->worker *node*)
        tx! (do
              (am/load-categories-tsv worker)
              (bcore2/generate worker am/generate-category 1 true))]
    (tu/then-await-tx tx! *node*)
    (t/is (= {:count-id 1} (first (c2/datalog-query *node* '{:find [(count id)]
                                                             :where [[id :_table :category]
                                                                     [id :c_id]]}))))
    (t/is (= "c_0" (b/sample-flat worker am/category-id)))))

(t/deftest generate-region-test
  (let [worker (->worker *node*)
        tx! (bcore2/generate worker am/generate-region 1 true)]
    (tu/then-await-tx tx! *node*)
    (t/is (= {:count-id 1} (first (c2/datalog-query *node* '{:find [(count id)]
                                                             :where [[id :_table :region]
                                                                     [id :r_id]]}))))
    (t/is (= "r_0" (b/sample-flat worker am/region-id)))))

(t/deftest generate-global-attribute-group-test
  (let [worker (->worker *node*)
        tx! (do
              (am/load-categories-tsv worker)
              (bcore2/generate worker am/generate-category 1)
              (bcore2/generate worker am/generate-global-attribute-group 1 true))]
    (tu/then-await-tx tx! *node*)
    (t/is (= {:count-id 1} (first (c2/datalog-query *node* '{:find [(count id)]
                                                             :where [[id :_table :gag]
                                                                     [id :gag_name]]}))))
    (t/is (= "gag_0" (b/sample-flat worker am/gag-id)))))

(t/deftest generate-global-attribute-value-test
  (let [worker (->worker *node*)
        tx! (do
              (am/load-categories-tsv worker)
              (bcore2/generate worker am/generate-category 1)
              (bcore2/generate worker am/generate-global-attribute-group 1)
              (bcore2/generate worker am/generate-global-attribute-value 1 true))]
    (tu/then-await-tx tx! *node*)
    (t/is (= {:count-id 1} (first (c2/datalog-query *node* '{:find [(count id)]
                                                             :where [[id :_table :gav]
                                                                     [id :gav_name]]}))))
    (t/is (= "gav_0" (b/sample-flat worker am/gav-id)))))

(t/deftest generate-user-attributes-test
  (let [worker (->worker *node*)
        tx! (do
              (bcore2/generate worker am/generate-user 1)
              (bcore2/generate worker am/generate-user-attributes 1 true))]
    (tu/then-await-tx tx! *node*)
    (t/is (= {:count-id 1} (first (c2/datalog-query *node* '{:find [(count id)]
                                                             :where [[id :_table :user-attribute]
                                                                     [id :ua_u_id]]}))))
    (t/is (= "ua_0" (b/sample-flat worker am/user-attribute-id)))))

(t/deftest generate-item-test
  (with-redefs [am/sample-status (constantly :open)]
    (let [worker (->worker *node*)
          tx! (do
                (bcore2/generate worker am/generate-user 1)
                (am/load-categories-tsv worker)
                (bcore2/generate worker am/generate-category 1)
                (bcore2/generate worker am/generate-item 1 true))]
      (tu/then-await-tx tx! *node*)
      (t/is (= {:count-id 1} (first (c2/datalog-query *node* '{:find [(count id)]
                                                               :where [[id :_table :item]
                                                                       [id :i_id]]}))))
      (t/is (= "i_0" (:i_id (am/random-item worker :status :open)))))))

(t/deftest proc-get-item-test
  (with-redefs [am/sample-status (constantly :open)]
    (let [worker (->worker *node*)
          tx! (do
                (bcore2/generate worker am/generate-user 1)
                (am/load-categories-tsv worker)
                (bcore2/generate worker am/generate-category 1)
                (bcore2/generate worker am/generate-item 1 true))]
      (tu/then-await-tx tx! *node*)
      (t/is (= "i_0" (-> (am/proc-get-item worker) first :i_id))))))

(t/deftest proc-new-user-test
  (with-redefs [am/sample-status (constantly :open)]
    (let [worker (->worker *node*)
          tx! (do
                (am/load-categories-tsv worker)
                (bcore2/generate worker am/generate-category 1)
                (bcore2/generate worker am/generate-item 1)
                (am/proc-new-user worker))]
      (tu/then-await-tx tx! *node*)
      (t/is (= {:count-id 1} (first (c2/datalog-query *node* '{:find [(count id)]
                                                               :where [[id :_table :user]
                                                                       [id :u_id]]}))))
      (t/is (= "u_0" (b/sample-flat worker am/user-id))))))

(t/deftest proc-new-bid-test
  (with-redefs [am/sample-status (constantly :open)]
    (let [worker (->worker *node*)]
      (t/testing "new bid"
        (let [tx1 (do
                    (bcore2/install-tx-fns worker {:apply-seller-fee am/tx-fn-apply-seller-fee, :new-bid am/tx-fn-new-bid})
                    (bcore2/generate worker am/generate-user 1)
                    (am/load-categories-tsv worker)
                    (bcore2/generate worker am/generate-category 1)
                    (bcore2/generate worker am/generate-item 1 true))
              _ (tu/then-await-tx tx1 *node*)
              tx2 (am/proc-new-bid worker)]
          (tu/then-await-tx tx2 *node*)

          ;; item has a new bid
          ;; (t/is (= nil (am/generate-new-bid-params worker)))
          (t/is (= {:i_num_bids 1}
                   (first (c2/datalog-query *node* '{:find [i_num_bids]
                                                     :where [[id :_table :item]
                                                             [id :i_num_bids i_num_bids]]}))))
          ;; there exists a bid
          (t/is (= {:ib_i_id "i_0", :ib_id "ib_0"}
                   (first (c2/datalog-query *node* '{:find [ib_id ib_i_id]
                                                     :where [[ib :_table :item-bid]
                                                             [ib :ib_id ib_id]
                                                             [ib :ib_i_id ib_i_id]]}))))
          ;; new max bid
          (t/is (= {:imb "ib_0-i_0", :imb_i_id "i_0"}
                   (first (c2/datalog-query *node*
                                            '{:find [imb imb_i_id]
                                              :where [[imb :_table :item-max-bid]
                                                      [imb :imb_i_id imb_i_id]]}))))))

      (t/testing "new bid but does not exceed max"
        (with-redefs [am/random-price (constantly Double/MIN_VALUE)]
          (let [tx (do
                     (bcore2/generate worker am/generate-user 1)
                     (am/proc-new-bid worker))]
            (tu/then-await-tx tx *node*)
            ;; new bid
            (t/is (= 2 (-> (c2/datalog-query *node*
                                             '{:find [i_num_bids]
                                               :where
                                               [[id :_table :item]
                                                [id :i_num_bids i_num_bids]]}
                                             ;; :basis {:tx tx}
                                             )
                           first :i_num_bids))))
          ;; winning bid remains the same
          (t/is (= {:imb "ib_0-i_0", :imb_i_id "i_0"}
                   (first (c2/datalog-query *node* '{:find [imb imb_i_id]
                                                     :where [[imb :_table :item-max-bid]
                                                             [imb :imb_i_id imb_i_id]]} )))))))))


#_(t/deftest proc-new-item-test
    (with-redefs [am/sample-status (constantly :open)]
      (let [worker (->worker *node**)]
        (t/testing "new bid"
          (bcore2/install-tx-fns worker {:apply-seller-fee am/tx-fn-apply-seller-fee, :new-bid am/tx-fn-new-bid})
          (bcore2/generate worker am/generate-user 1)
          (am/load-categories-tsv worker)
          (bcore2/generate worker am/generate-category 10)
          (bcore2/generate worker am/generate-global-attribute-group 10)
          (bcore2/generate worker am/generate-global-attribute-value 100)
          (am/proc-new-item worker)
          (xt/sync *node**)
          ;; new item
          (let [{:keys [i_id i_u_id]} (ffirst (xt/q (xt/db *node**) '{:find [(pull id [*])] :where [[id :i_id]]}))]
            (t/is (= "i_0" i_id))
            (t/is (= "u_0" i_u_id)))
          (t/is (< (- (ffirst (xt/q (xt/db *node**) '{:find [u_balance]
                                                      :where [[u :u_id uid]
                                                              [u :u_balance u_balance]]}))
                      (double -1.0))
                   0.0001))))))
#_(def hello-world
    #c2/clj-form (fn hello-world []
                   (println "hello world")
                   [[:put {:id 1 :hello :world}]]))

(t/deftest sci-print-test
  (let [tx1 (c2/submit-tx *node* [[:put {:id :hello-world :fn hello-world
                                         #_(fn hello-world []
                                             (println "hello world")
                                             [[:put {:id 1 :hello :world}]])}]])
        tx2 (c2/submit-tx *node* [[:call :hello-world]])]
    (tu/then-await-tx tx2 *node*)
    (t/is (= 1 1)#_(= {:hello :world} (first (c2/datalog-query *node* '{:find [hello] :where [[1 :hello hello]]}))))
    ))


(t/deftest hello-world-tx-fn-test
  (let [tx1 (c2/submit-tx *node* [[:put {:id :hello-world :fn #_hello-world
                                         #c2/clj-form (fn hello-world []
                                                        (println "hello world")
                                                        [[:put {:id 1 :hello :world}]])}]
                                  [:put {:id :hello-world2 :fn
                                         #c2/clj-form (fn hello-world2 []
                                                        [[:call :hello-world]])}]])
        tx2 (c2/submit-tx *node* [[:call :hello-world2]])]
    (tu/then-await-tx tx2 *node*)
    (t/is (= 1 1)#_(= {:hello :world} (first (c2/datalog-query *node* '{:find [hello] :where [[1 :hello hello]]}))))
    ))

(t/deftest query-in-tx-fn-test
  (let [tx1 (c2/submit-tx *node* [[:put {:id 1 :foo :bar :bar :foo :_table :toto}]
                                  [:put {:id :hello-world-fn
                                         :fn #c2/clj-form (fn hello-world [id]
                                                            [[:put {:id "query" :q (first (q '{:find [id foo bar]
                                                                                               :in [id]
                                                                                               :where
                                                                                               [[id :_table :toto]
                                                                                                [id :foo foo]
                                                                                                [id :bar bar]]}
                                                                                             id))}]])}]])

        _ (tu/then-await-tx tx1 *node*)
        tx2 (c2/submit-tx *node* [[:call :hello-world-fn 1]])]
    (tu/then-await-tx tx2 *node*)
    (t/is (= nil (c2/datalog-query *node* '{:find [q] :where [["query" :q q]]})))))
