(ns core2.operator.order-by
  (:require [clojure.java.io :as io]
            [clojure.spec.alpha :as s]
            [core2.expression.comparator :as expr.comp]
            [core2.logical-plan :as lp]
            [core2.util :as util]
            [core2.vector.indirect :as iv]
            [core2.vector.writer :as vw]
            [core2.vector.indirect :as indirect])
  (:import core2.ICursor
           (core2.vector IIndirectRelation IRowCopier)
           (core2.vector.indirect DirectVector IndirectVector)
           (java.io InputStream OutputStream ObjectOutputStream ObjectInputStream DataInputStream DataOutputStream
                    ByteArrayInputStream ByteArrayOutputStream)
           (java.util Arrays Comparator)
           (java.util.function Consumer ToIntFunction)
           java.util.stream.IntStream
           (org.apache.arrow.memory BufferAllocator)
           ;; (org.apache.arrow.vector.util DecimalUtility)
           (java.nio.channels Channels)
           (org.apache.arrow.vector BigIntVector VectorLoader VectorSchemaRoot)
           (org.apache.arrow.vector.ipc ArrowFileWriter ArrowStreamWriter ArrowWriter ArrowStreamReader)))

(comment
  (require 'sc.api))

(s/def ::direction #{:asc :desc})
(s/def ::null-ordering #{:nulls-first :nulls-last})

(defmethod lp/ra-expr :order-by [_]
  (s/cat :op '#{:τ :tau :order-by order-by}
         :order-specs (s/coll-of (-> (s/cat :column ::lp/column
                                            :spec-opts (s/? (s/keys :opt-un [::direction ::null-ordering])))
                                     (s/nonconforming))
                                 :kind vector?)
         :relation ::lp/ra-expression))

(set! *unchecked-math* :warn-on-boxed)

(defn- sorted-idxs ^ints [^IIndirectRelation read-rel, order-specs]
  (-> (IntStream/range 0 (.rowCount read-rel))
      (.boxed)
      (.sorted (reduce (fn [^Comparator acc, [column {:keys [direction null-ordering]
                                                      :or {direction :asc, null-ordering :nulls-last}}]]
                         (let [read-col (.vectorForName read-rel (name column))
                               col-comparator (expr.comp/->comparator read-col read-col null-ordering)

                               ^Comparator
                               comparator (cond-> (reify Comparator
                                                    (compare [_ left right]
                                                      (.applyAsInt col-comparator left right)))
                                            (= :desc direction) (.reversed))]
                           (if acc
                             (.thenComparing acc comparator)
                             comparator)))
                       nil
                       order-specs))
      (.mapToInt (reify ToIntFunction
                   (applyAsInt [_ x] x)))
      (.toArray)))

(defn ivec-remove-indirection [^BufferAllocator allocator ^core2.vector.IIndirectVector ivec]
  (cond->> ivec
    (instance? IndirectVector ivec) (indirect/indirect-vec->direct-vec allocator)))

(defn irel-remove-indirection [^BufferAllocator allocator ^core2.vector.IIndirectVector irel]
  (->> (seq irel) (map #(ivec-remove-indirection allocator %)) indirect/->indirect-rel))

(defn write-irel [^core2.vector.IIndirectRelation irel ^OutputStream os column-order]
  (let [ivecs (for [column-name column-order]
                (.vectorForName irel column-name))
        root (VectorSchemaRoot. (map #(.getField %) ivecs) ivecs)
        writer (ArrowStreamWriter. root nil (Channels/newChannel os))]
    (.start writer)
    (.writeBatch writer)
    (.end writer)))

(defn read-irel [^BufferAllocator allocator ^InputStream is column-order]
  (let [reader (ArrowStreamReader. is allocator)
        ^VectorSchemaRoot read-root (.getVectorSchemaRoot reader)]
    (.loadNextBatch reader)
    (->> (map-indexed (fn [i column-name] (iv/->DirectVector (.getVector read-root i) column-name)) column-order)
         indirect/->indirect-rel)))

(defn sort-irel [^BufferAllocator allocator ^core2.vector.IIndirectRelation irel order-specs]
  (let [sorted-idxs (sorted-idxs irel order-specs)]
    (->> (iv/select irel sorted-idxs) (irel-remove-indirection allocator))))

(def ^:private split-threshold 200000)

(defn mk-irel-comparator [^core2.vector.IIndirectRelation irel1 ^core2.vector.IIndirectRelation irel2 order-specs]
  (reduce (fn [^Comparator acc, [column {:keys [direction null-ordering]
                                         :or {direction :asc, null-ordering :nulls-last}}]]
            (let [read-col1 (.vectorForName irel1 (name column))
                  read-col2 (.vectorForName irel2 (name column))
                  col-comparator (expr.comp/->comparator read-col1 read-col2 null-ordering)

                  ^Comparator
                  comparator (cond-> (reify Comparator
                                       (compare [_ left right]
                                         (.applyAsInt col-comparator left right)))
                               (= :desc direction) (.reversed))]
              (if acc
                (.thenComparing acc comparator)
                comparator)))
          nil
          order-specs))

(defn two-merge-irels [^BufferAllocator allocator ^core2.vector.IIndirectRelation irel1
                       ^core2.vector.IIndirectRelation irel2 order-specs]
  (let [out-rel-writer (vw/->rel-writer allocator)
        ;; TODO move out for speed
        ^Comparator cmp (mk-irel-comparator irel1 irel2 order-specs)
        len1 (.rowCount irel1)
        len2 (.rowCount irel2)
        ^IRowCopier row-copier1 (.rowCopier out-rel-writer irel1)
        ^IRowCopier row-copier2 (.rowCopier out-rel-writer irel2)
        [idx1 idx2] (loop [idx1 0 idx2 0]
                      (cond  (or (= idx1 len1) (= idx2 len2))
                             [idx1 idx2]
                             (neg? (.compare cmp idx1 idx2))
                             (do
                               (.copyRow row-copier1 idx1)
                               (recur (inc idx1) idx2))
                             :else
                             (do
                               (.copyRow row-copier2 idx2)
                               (recur idx1 (inc idx2)))))]
    (when (< idx1 len1)
      (doseq [idx (range idx1 len1)]
        (.copyRow row-copier1 idx)))
    (when (< idx2 len2)
      (doseq [idx (range idx2 len2)]
        (.copyRow row-copier2 idx)))
    (vw/rel-writer->reader out-rel-writer)))

(defn sort-irels [^BufferAllocator allocator irels order-specs]
  (assert (< 1 (count irels)))
  (loop [irels (map #(sort-irel allocator % order-specs) irels)]
    (if-not (< 1 (count irels))
      (first irels)
      (let [new-irels
            (->> (partition 2 irels)
                 (map (fn [[irel1 irel2]] (two-merge-irels allocator irel1 irel2 order-specs))))]
        (recur (cond-> new-irels
                 (odd? (count irels)) (conj (last irels))))))))

(defn- irel->column-names [irel]
  (map :name (seq irel)))

(comment
  (irel->column-names direct-rel))

(def ^:private spill-threshold 200000)

(defn- accumulate-relations ^core2.vector.IIndirectRelation [allocator ^ICursor in-cursor]
  (let [rel-writer (vw/->rel-writer allocator)]
    (try
      (.forEachRemaining in-cursor
                         (reify Consumer
                           (accept [_ src-rel]
                             (vw/append-rel rel-writer src-rel))))
      (catch Exception e
        (.close rel-writer)
        (throw e)))

    (vw/rel-writer->reader rel-writer)))



(comment
  (def indirect-relation
    (sc.api/letsc [1 -2]
                  read-rel
                  #_order-specs))
  (-> indirect-relation seq first :v seq)

  (sc.api/letsc [2 -2] (-> indirect-relation seq first :v (.get 0)))

  )


(ns-unalias *ns* 'c2)
(require '[core2.api :as c2])


(defn split-blocks ^core2.vector.IIndirectRelation [n ^BufferAllocator allocator ^ICursor in-cursor]
  (let [cnt (atom 0)]
    (loop [i 0 res [] rel-writer (vw/->rel-writer allocator)]
      ;; (println "block cnt " (swap! cnt inc))
      (if (.tryAdvance in-cursor
                       (reify Consumer
                         (accept [_ src-rel]
                           #_(clojure.pprint/pprint (seq src-rel))
                           (vw/append-rel rel-writer src-rel))))
        (if (< i n)
          (recur (inc i) res rel-writer)
          (recur 0 (conj res (vw/rel-writer->reader rel-writer)) (vw/->rel-writer allocator)))
        (if (= i 0)
          res
          (conj res (vw/rel-writer->reader rel-writer)))))))

#_(do
    (defn split-blocks ^core2.vector.IIndirectRelation [n ^BufferAllocator allocator ^ICursor in-cursor]
      (let [cnt (atom 0)]
        (loop [i 0 res [] rel-writer (vw/->rel-writer allocator)]
          (println "block cnt " (swap! cnt inc))
          (if (.tryAdvance in-cursor
                           (reify Consumer
                             (accept [_ src-rel]
                               #_(clojure.pprint/pprint (seq src-rel))
                               (vw/append-rel rel-writer src-rel))))
            (if (< i n)
              (recur (inc i) res rel-writer)
              (recur 0 (conj res (vw/rel-writer->reader rel-writer)) (vw/->rel-writer allocator)))
            (if (= i 0)
              res
              (conj res (vw/rel-writer->reader rel-writer)))))))


    (with-open [node (core2.node/start-node {})]
      (c2/submit-tx node [[:sql "INSERT INTO x(id, data) VALUES(1, 'foo')"]])
      (def tx (c2/submit-tx node [[:sql "INSERT INTO x(id, data) VALUES(2, 'bar')"]]))
      (c2/sql-query node "SELECT x.id, x.data FROM x ORDER BY x.data" {:basis {:tx tx}}))
    )

(def ^:private block-limit 2)

(defn calculate-out-rels [^BufferAllocator allocator ^ICursor in-cursor order-specs]
  (let [out-rels (split-blocks block-limit allocator in-cursor)]
    ;; (sc.api/spy)
    (case (count out-rels)
      0 []
      1 (let [read-rel (first out-rels)]
          [(iv/select read-rel (sorted-idxs read-rel order-specs))])
      [(sort-irels allocator out-rels order-specs)])))

(comment
  (require 'sc.api)

  (sc.api/letsc [1 -2]
                (let [read-rel (first out-rels)]
                  (iv/select read-rel (sorted-idxs read-rel order-specs)))
                )
  )

(deftype OrderByCursor [^BufferAllocator allocator
                        ^ICursor in-cursor
                        order-specs
                        ^:unsynchronized-mutable ^boolean consumed?
                        ^:unsynchronized-mutable out-rels]
  ICursor
  #_(tryAdvance [_ c]
      (with-open [read-rel (accumulate-relations allocator in-cursor)]
        (if (pos? (.rowCount read-rel))
          (with-open [out-rel (iv/select read-rel (sorted-idxs read-rel order-specs))]
            (.accept c out-rel)
            true)
          false)))
  (tryAdvance [this c]
    (cond consumed? false

          out-rels
          (if-let [^core2.vector.IIndirectRelation out-rel (first out-rels)]
            (try
              (set! (.out-rels this) (lazy-seq (next out-rels)))
              (.accept c out-rel)
              true
              (finally
                (.close out-rel)))
            (do
              (set! (.out-rels this) nil)
              (set! (.consumed? this) true)
              false))

          :else
          (let [out-rels (calculate-out-rels allocator in-cursor order-specs)]
            (if-let [^core2.vector.IIndirectRelation out-rel (first out-rels)]
              (try
                (set! (.out-rels this) (lazy-seq (next out-rels)))
                (.accept c out-rel)
                true
                (finally
                  (.close out-rel)))
              (do
                (set! (.out-rels this) nil)
                (set! (.consumed? this) true)
                false)))))

  (close [_]
    (util/try-close in-cursor)))

(defmethod lp/emit-expr :order-by [{:keys [order-specs relation]} args]
  (lp/unary-expr (lp/emit-expr relation args)
      (fn [col-types]
        {:col-types col-types
         :->cursor (fn [{:keys [allocator]} in-cursor]
                     (OrderByCursor. allocator in-cursor order-specs false nil))})))

;; to remove

(defn test-lazy-seq [n]
  (if (zero? n)
    '()
    (lazy-seq (println n) (cons n (test-lazy-seq (dec n))))))

(comment
  (def t (test-lazy-seq 10))

  (def t2 (next t))
  )

(defn- write-object [o file]
  (with-open [os (ObjectOutputStream. (io/output-stream file))]
    (.writeObject os o)
    (.flush os)))

(defn- read-object [file]
  (with-open [is (ObjectInputStream. (io/input-stream file))]
    (.readObject is)))

(comment
  (def buffer-allocator (sc.api/letsc [1 -1] allocator))
  (def direct-vec (sc.api/letsc [1 -1] (-> (seq read-rel) first)))
  (def direct-vec (sc.api/letsc [1 -1] (-> (seq read-rel) second)))

  (=
   (-> direct-vec :v (.getField) (.getName))
   (-> direct-vec :name))

  (def direct-rel (sc.api/letsc [1 -1] read-rel))

  (sc.api/letsc [1 -1]
                read-rel
                ;; (sorted-idxs read-rel order-specs)
                ;; (.rowCount read-rel)
                #_order-specs
                #_(-> (seq read-rel)
                      first
                      ;; :v
                      ;; (.getFieldBuffers)
                      #_(.getBuffers))))

(defn write-dvec [^core2.vector.IIndirectVector ivec ^OutputStream os]
  (let [v (.getVector ivec)
        name (.getName ivec)
        root (VectorSchemaRoot. [(.getField v)] [v])
        writer (ArrowStreamWriter. root nil (Channels/newChannel os))
        dos (DataOutputStream. os)]
    (.writeInt dos (count (.getBytes name)))
    (.write dos (.getBytes name))
    (.flush dos)
    (.start writer)
    (.writeBatch writer)
    (.end writer)))

(defn read-dvec [^BufferAllocator allocator ^InputStream is]
  (let [dis (DataInputStream. is)
        name-length (.readInt dis)
        ba (byte-array name-length)
        _ (.read dis ba 0 name-length)
        name (String. ba)
        reader (ArrowStreamReader. is allocator)
        ^VectorSchemaRoot read-root (.getVectorSchemaRoot reader)]
    (.loadNextBatch reader)
    (iv/->DirectVector (.getVector read-root 0) name)))

(comment
  (sc.api/letsc [7 -6]
                ;; (.getSchema read-root)
                (count (.getFieldVectors read-root))))

(comment
  (def baos (ByteArrayOutputStream.))
  (write-dvec direct-vec baos)
  (def bais (ByteArrayInputStream. (.toByteArray baos)))
  (read-dvec buffer-allocator bais))

(defn write-drel [^core2.vector.IIndirectRelation irel ^OutputStream os]
  (let [dvecs (seq irel)
        dos (DataOutputStream. os)]
    (.writeInt dos (count dvecs))
    (.flush dos)
    (doseq [dvec (take 1 (seq irel))]
      (write-dvec dvec os))))

(defn read-drel [^BufferAllocator allocator ^InputStream is]
  (let [dis (DataInputStream. is)
        relation-size (.readInt dis)]
    ;; (println relation-size)
    (iv/->indirect-rel (repeatedly relation-size #(read-dvec allocator is)))))


(comment
  (def baos (ByteArrayOutputStream.))
  (write-drel direct-rel baos)
  (def bais (ByteArrayInputStream. (.toByteArray ^ByteArrayOutputStream baos)))
  (read-drel buffer-allocator bais))
