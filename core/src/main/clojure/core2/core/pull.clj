(ns core2.core.pull
  (:require [clojure.spec.alpha :as s]
            [core2.logical-plan :as lp]))

(defn compile-eql-to-ra [eql table]
  (let [cols (map symbol eql)]
    (with-meta [:project ['id {'result (zipmap eql cols)}]
                [:scan {:table table} (into '[id] cols)]]
      {::col 'id ::res-col 'result})))

(comment
  (require '[core2.logical-plan :as lp])
  (lp/validate-plan '[:project [id {result {}}] [:scan {:table table} [id]]])

  (lp/validate-plan '[:project
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
                        [:scan {:table xt_docs} [id age first-name]]]]]))
