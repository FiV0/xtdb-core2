(ns core2.operator.order-by-test
  (:require [clojure.test :as t]
            [core2.test-util :as tu]))

(t/use-fixtures :each tu/with-allocator)

(t/deftest test-order-by
  (t/is (= [[{:a 0, :b 15}
             {:a 12, :b 10}
             {:a 83, :b 100}
             {:a 100, :b 83}]]
           (tu/query-ra [:order-by '[[a]]
                         [::tu/blocks
                          [[{:a 12, :b 10}
                            {:a 0, :b 15}]
                           [{:a 100, :b 83}]
                           [{:a 83, :b 100}]]]]
                        {:preserve-blocks? true})))

  (t/is (= [{:a 0, :b 15}
            {:a 12.4, :b 10}
            {:a 83.0, :b 100}
            {:a 100, :b 83}]
           (tu/query-ra '[:order-by [[a]]
                          [:table [{:a 12.4, :b 10}
                                   {:a 0, :b 15}
                                   {:a 100, :b 83}
                                   {:a 83.0, :b 100}]]]
                        {}))
        "mixed numeric types")

  (let [table-with-nil [{:a 12.4, :b 10}, {:a nil, :b 15}, {:a 100, :b 83}, {:a 83.0, :b 100}]]
    (t/is (= [{:a nil, :b 15}, {:a 12.4, :b 10}, {:a 83.0, :b 100}, {:a 100, :b 83}]
             (tu/query-ra '[:order-by [[a {:null-ordering :nulls-first}]]
                            [:table ?table]]
                          {:table-args {'?table table-with-nil}}))
          "nulls first")

    (t/is (= [{:a 12.4, :b 10}, {:a 83.0, :b 100}, {:a 100, :b 83}, {:a nil, :b 15}]
             (tu/query-ra '[:order-by [[a {:null-ordering :nulls-last}]]
                            [:table ?table]]
                          {:table-args {'?table table-with-nil}}))
          "nulls last")

    (t/is (= [{:a 12.4, :b 10}, {:a 83.0, :b 100}, {:a 100, :b 83}, {:a nil, :b 15}]
             (tu/query-ra '[:order-by [[a]]
                            [:table ?table]]
                          {:table-args {'?table table-with-nil}}))
          "default nulls last")))

(defn first-diff [s1 s2]
  (loop [s1 s1 s2 s2]
    (cond
      (and (empty? s1) (empty? s2)) nil

      (empty? s1) [nil (first s2)]

      (empty? s2) [(first s1) nil]

      (= (first s1) (first s2))
      (recur (rest s1) (rest s2))

      :else
      [(first s1) (first s2)])))

(comment
  (require 'sc.api)

  (sc.api/letsc [10 -7]
                (first-diff sorted res)
                [(take 3 sorted) (take 3 res)])


  )

(t/deftest test-order-by-spill
  (let [data (map-indexed (fn [i d] {:a d :b i}) (repeatedly 1000 #(rand-int 1000000)))
        blocks (->> (partition-all 13 data)
                    (map #(into [] %))
                    (into []))
        sorted (sort-by (juxt :a :b) data)
        res (tu/query-ra [:order-by '[[a] [b]]
                          [::tu/blocks blocks]]
                         {})]
    (sc.api/spy)
    (t/is  (= nil (first-diff sorted res))
           #_(= sorted
                (tu/query-ra [:order-by '[[a] [b]]
                              [::tu/blocks blocks]]
                             {}))
           "spilling to disk")

    #_(t/is (= #{}
               (clojure.set/difference
                (into #{} (tu/query-ra [:order-by '[[a]]
                                        [::tu/blocks blocks]]
                                       {}))
                (into #{} sorted))
               )
            "spilling to disk")

    #_(t/is (= #{}
               (clojure.set/difference (into #{} sorted)
                                       (into #{} (tu/query-ra [:order-by '[[a]]
                                                               [::tu/blocks blocks]]
                                                              {})))
               )
            "spilling to disk")))
