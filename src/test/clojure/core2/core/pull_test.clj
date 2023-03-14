(ns core2.core.pull-test)

(defn compile-eql-to-ra [eql table]
  [:project '[id {result {}}] [:scan table '[id]]])

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
