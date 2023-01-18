(ns core2.operator.order-by
  (:require [clojure.java.io :as io]
            [clojure.spec.alpha :as s]
            [core2.expression.comparator :as expr.comp]
            [core2.logical-plan :as lp]
            [core2.util :as util]
            [core2.vector.indirect :as iv]
            [core2.vector.writer :as vw])
  (:import core2.ICursor
           core2.vector.IIndirectRelation
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
  (require 'sc.api)


  )

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

(def ^:private spill-threshold 16)

(defn take-blocks ^core2.vector.IIndirectRelation [n allocator ^ICursor in-cursor]
  (let [rel-writer (vw/->rel-writer allocator)]
    (try
      (loop [i 0]
        (when (and (< i spill-threshold)
                   (.tryAdvance in-cursor
                                (reify Consumer
                                  (accept [_ src-rel]
                                    (vw/append-rel rel-writer src-rel)))))
          (recur (inc i))))

      (catch Exception e
        (.close rel-writer)
        (throw e)))

    (vw/rel-writer->reader rel-writer)))

(comment
  (def buffer-allocator (sc.api/letsc [1 -1] allocator))
  (def direct-vec (sc.api/letsc [1 -1] (-> (seq read-rel) first)))
  (def direct-rel (sc.api/letsc [1 -1] (->  first)))

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
    (.start writer)
    (.writeBatch writer)
    (.end writer)))

(defn read-dvec [^BufferAllocator allocator ^InputStream is]
  (let [
        dis (DataInputStream. is)
        name-length (.readInt dis)
        ba (byte-array name-length)
        name (.read dis ba 0 name-length)
        reader (ArrowStreamReader. is allocator)
        ^VectorSchemaRoot read-root (.getVectorSchemaRoot reader)]
    (.loadNextBatch reader)
    (iv/->DirectVector (.getVector read-root 0) name)))

(comment
  (def baos (ByteArrayOutputStream.))
  (def bais (ByteArrayInputStream. (.toByteArray baos)))
  (write-dvec direct-vec baos)
  (read-dvec buffer-allocator bais))

(defn write-irel [^core2.vector.IIndirectRelation irel ^OutputStream os]
  (let [dvecs (seq irel)
        dos (DataOutputStream. os)]
    (.writeInt dos (count dvecs))
    (doseq [dvec (seq irel)]
      (write-dvec dvec os))))

(defn read-irel [^BufferAllocator allocator ^InputStream is]
  (let [dis (DataInputStream. is)
        relation-size (.readInt dis)]
    (iv/->indirect-rel (repeatedly relation-size #(read-dvec allocator is)))))

(comment
  (def baos (ByteArrayOutputStream.))
  (def bais (ByteArrayInputStream. (.toByteArray baos)))
  (write-dvec direct-vec baos)
  (read-dvec buffer-allocator bais))


(defn file->mmap-irel [path]
  )

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

(defn- write-object [o file]
  (with-open [os (ObjectOutputStream. (io/output-stream file))]
    (.writeObject os o)
    (.flush os)))

(defn- read-object [file]
  (with-open [is (ObjectInputStream. (io/input-stream file))]
    (.readObject is)))

(comment
  (def indirect-relation
    (sc.api/letsc [5 -2]
                  read-rel
                  #_order-specs))

  (write-object indirect-relation (io/file "test.xtdb"))
  (def var-char-vec (-> (seq indirect-relation) first :v))

  (seq (read-object (io/file "test.xtdb")))

  (with-open [os (ObjectOutputStream. (io/output-stream (io/file "test.xtdb")))]
    (.writeObject os var-char-vec #_indirect-relation)
    (.flush os))

  )

(def ^:private row-limit 1000)

(deftype OrderByCursor [^BufferAllocator allocator
                        ^ICursor in-cursor
                        order-specs]
  ICursor
  (tryAdvance [_ c]
    (with-open [read-rel (accumulate-relations allocator in-cursor)]
      (sc.api/spy)
      (if (pos? (.rowCount read-rel))
        (with-open [out-rel (iv/select read-rel (sorted-idxs read-rel order-specs))]
          (.accept c out-rel)
          true)
        false)))

  (close [_]
    (util/try-close in-cursor)))

(defmethod lp/emit-expr :order-by [{:keys [order-specs relation]} args]
  (lp/unary-expr (lp/emit-expr relation args)
    (fn [col-types]
      {:col-types col-types
       :->cursor (fn [{:keys [allocator]} in-cursor]
                   (OrderByCursor. allocator in-cursor order-specs))})))
