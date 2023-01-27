(ns core2.bench.core2
  (:require [core2.api :as c2]
            [core2.test-util :as tu]))

(set! *warn-on-reflection* false)

(defn install-tx-fns [worker fns]
  (->> (for [[id fn-def] fns]
         [:put {:id id, :fn #c2/clj-form fn-def}])
       (c2/submit-tx (:sut worker))))

(defn generate
  ([worker f n]
   (let [doc-seq (remove nil? (repeatedly (long n) (partial f worker)))
         partition-count 512]
     (doseq [chunk (partition-all partition-count doc-seq)]
       (c2/submit-tx (:sut worker) (mapv (partial vector :put) chunk)))))
  ([worker f n await?]
   (if-not await?
     (generate worker f n)
     (let [doc-seq (remove nil? (repeatedly (long n) (partial f worker)))
           partition-count 512]
       (->> (partition-all partition-count doc-seq)
            (map #(c2/submit-tx (:sut worker) (mapv (partial vector :put) %)))
            last
            deref)))))
