(ns core2.core.pull-test
  (:require [clojure.test :as t :refer [deftest]]
            [core2.datalog :as c2]
            [core2.node :as node]
            [core2.test-util :as tu]
            [core2.util :as util]))

(t/use-fixtures :each tu/with-mock-clock tu/with-node)

;; issue with left-outer-join and keyword ids
(def ivan+petr
  [[:put {:id "ivan" #_:ivan, :first-name "Ivan", :last-name "Ivanov" :age 10}]
   [:put {:id "petr" #_:petr, :first-name "Petr", :last-name "Petrov" :age 20}]])

(deftest test-without-pull
  (let [tx (c2/submit-tx tu/*node* ivan+petr)]
    (t/is (= [{:e :ivan, :name "Ivan"}
              {:e :petr, :name "Petr"}]
             (c2/q tu/*node*
                   (-> '{:find [e age name]
                         :where [[e :first-name name]
                                 [e :age age]
                                 [e2 :age age]]}
                       (assoc :basis {:tx tx})))))))


(deftest test-left-outer-ra
  (let [tx (c2/submit-tx tu/*node* ivan+petr)]
    (tu/with-allocator
      (fn []
        (t/is (= []
                 (tu/query-ra
                  #_'[:mega-join
                      [{e id}]
                      [[:project
                        [{age _r0_age} {e _r0_e}]
                        [:rename
                         {age _r0_age, e _r0_e}
                         [:project
                          [age {e id}]
                          [:scan
                           {:table xt_docs, :for-app-time (at :now), :for-sys-time nil}
                           [age id]]]]]
                       [:project [id first-name]
                        [:scan {:table xt_docs, :for-app-time (at :now), :for-sys-time nil}
                         [id first-name]]]]]
                  '[:project
                    [e result]
                    [:left-outer-join
                     [{e id}]
                     [:project
                      [{age _r0_age} {e _r0_e}]
                      [:rename
                       {age _r0_age, e _r0_e}
                       [:project
                        [age {e id}]
                        [:scan
                         {:table xt_docs, :for-app-time (at :now), :for-sys-time nil}
                         [age id]]]]]
                     [:project
                      [id {result {:age age, :first-name first-name}}]
                      [:scan {:table xt_docs} [id age first-name]]]]]

                  #_'[:left-outer-join
                      [{e id}]
                      [:project
                       [{age _r0_age} {e _r0_e}]
                       [:rename
                        {age _r0_age, e _r0_e}
                        [:project
                         [age {e id}]
                         [:scan
                          {:table xt_docs, :for-app-time (at :now), :for-sys-time nil}
                          [age id]]]]]
                      [:project [id first-name]
                       [:scan {:table xt_docs, :for-app-time (at :now), :for-sys-time nil} [id first-name]]]]
                  {:node tu/*node*})))))))

(deftest test-empty-pull
  (let [tx (c2/submit-tx tu/*node* ivan+petr)]
    (t/is (= [{} {}]
             (c2/q tu/*node*
                   (-> '{:find [e (* age 2) (pull e xt_docs [])]
                         :where [[e :age age]]}
                       (assoc :basis {:tx tx}))))
          "empty pull")))

(deftest test-simple-pull
  (let [tx (c2/submit-tx tu/*node* ivan+petr)]
    (t/is (= [{} {}]
             (c2/q tu/*node*
                   (-> '{:find [e (pull e xt_docs [:age :first-name])]
                         :where [[e :age age]]}
                       (assoc :basis {:tx tx}))))
          "simple pull")))

(def examples
  '[{:label "Empty pattern"
     :eql []
     :params {:table order}
     :plan
     [:project [id {result {}}] [:scan order [id]]]}

    {:label "Root selection"
     :eql [:id, :delivery-date]
     :params {:table order}
     :plan
     [:project [id {result {:id id, :delivery-date delivery-date}}]
      [:scan order [id delivery-date]]]}


    {:label "Single forward join"
     :eql [:id, :firstname, ({:address [:postcode]} {:table address, :card :one})]
     :params {:table customer}
     :plan
     [:project [id {result {:id id,
                            :firstname firstname
                            :address result0}}]
      [:left-outer-join
       [:scan customer [id, delivery-date, address]]
       [:project [{ref0 id}
                  {result0 {:postcode postcode}}]
        [:scan address [id postcode]]]
       [(= address ref0)]]]}

    ;; todo
    {:label "Two forward joins"}

    {:label "Nested forward joins"
     :eql
     [:id :delivery-date
      ({:customer [:firstname, ({:address [:postcode, :city]} {:table address, :card :one})]}
       {:table customer, :card :one})]
     :params {:table order}
     :plan
     [:project [id {result {:id id,
                            :delivery_date delivery-date
                            :customer result0}}]
      [:left-outer-join
       [:scan order [id, delivery-date, customer]]
       [:project [{ref0 id}
                  {result0 result}]
        [:project [id {result {:firstname firstname, :address result0}}]
         [:left-outer-join
          [:scan customer [id firstname address]]
          [:project [{ref0 id}
                     {result0 {:postcode postcode, :city city}}]
           [:scan address [id postcode city]]]
          [(= address ref0)]]]]
       [(= customer ref0)]]]}

    {:label "Many forward join"
     :eql [:id, :delivery-date, ({:items [:sku, :qty]} {:table order-item, :card :many})]
     :params {:table order}
     :plan
     [:project [id {result {:id id,
                            :delivery-date delivery-date
                            :items result0}}]
      [:group-by [id delivery-date {result0 (array-agg elm0)}]
       [:left-outer-join
        [:unwind {elem0 items} {} [:scan order [id, delivery-date, items]]]
        [:project [{ref0 id}
                   {result0 {:sku sku, :qty qty}}]
         [:scan order-item [id sku qty]]]
        [(= elm0 ref0)]]]]}

    {:label "Reverse join"
     :eql [:id, :postcode ({:_address [:firstname]} {:table customer, :card :many})]
     :params {:table address}
     :plan
     [:project [id {result {:id id
                            :postcode postcode
                            :_address _address}}]
      [:group-by [id postcode {_address (array-agg ref0)}]]
      [:left-outer-join
       [:scan address [id postcode]]
       [:project [{ref0 address} {result0 {:firstname firstname}}]
        [:scan customer [id firstname address]]]
       [(= id ref0)]]]}])
