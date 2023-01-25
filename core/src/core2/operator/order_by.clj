(ns core2.operator.order-by
  (:require [clojure.java.io :as io]
            [clojure.spec.alpha :as s]
            [clojure.tools.logging :as log]
            [core2.expression.comparator :as expr.comp]
            [core2.logical-plan :as lp]
            [core2.types :as types]
            [core2.util :as util]
            [core2.vector.indirect :as iv]
            [core2.vector.indirect :as indirect]
            [core2.vector.writer :as vw])
  (:import core2.ICursor
           (core2.vector IIndirectVector IIndirectRelation IRowCopier)
           (core2.vector.indirect DirectVector IndirectVector)
           (java.io InputStream OutputStream ObjectOutputStream ObjectInputStream DataInputStream DataOutputStream
                    ByteArrayInputStream ByteArrayOutputStream)
           java.io.File
           java.nio.ByteBuffer
           ;; (org.apache.arrow.vector.util DecimalUtility)
           (java.nio.channels Channels)
           java.nio.channels.FileChannel
           [java.nio.file OpenOption StandardOpenOption]
           (java.util Arrays Comparator)
           (java.util.function Consumer ToIntFunction)
           java.util.stream.IntStream
           (org.apache.arrow.memory BufferAllocator)
           org.apache.arrow.memory.RootAllocator
           (org.apache.arrow.vector ValueVector BigIntVector VectorLoader VectorSchemaRoot)
           org.apache.arrow.vector.VectorSchemaRoot
           (org.apache.arrow.vector.ipc ArrowFileWriter ArrowStreamWriter ArrowWriter ArrowStreamReader)
           [org.apache.arrow.vector.ipc ArrowFileReader ArrowStreamReader JsonFileWriter]
           (org.apache.arrow.vector.types.pojo Schema)))

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

(defn ivec-remove-indirection [^core2.vector.IIndirectVector ivec ^BufferAllocator allocator]
  (cond->> ivec
    (instance? IndirectVector ivec) (indirect/indirect-vec->direct-vec allocator)))

(defn irel-remove-indirection ^core2.vector.IIndirectRelation [^core2.vector.IIndirectRelation irel ^BufferAllocator allocator]
  (->> (seq irel) (map #(ivec-remove-indirection % allocator)) indirect/->indirect-rel))

(defn write-irel* [^core2.vector.IIndirectRelation irel ^OutputStream os column-order]
  (let [ivecs (for [column-name column-order]
                (.vectorForName irel column-name))
        value-vecs (map (fn [^IIndirectVector iv] (.getVector iv)) ivecs)
        root (VectorSchemaRoot. (map (fn [^ValueVector vv] (.getField vv)) value-vecs) value-vecs)
        writer (ArrowStreamWriter. root nil (Channels/newChannel os))]
    (.start writer)
    (.writeBatch writer)
    (.end writer)))

(defn write-irel [^core2.vector.IIndirectRelation irel file column-order]
  (with-open [os (io/output-stream file)]
    (write-irel* irel os column-order)
    file))

(defn read-irel* [^BufferAllocator allocator ^InputStream is column-order]
  (let [reader (ArrowStreamReader. is allocator)
        ^VectorSchemaRoot read-root (.getVectorSchemaRoot reader)]
    (.loadNextBatch reader)
    (->> (map-indexed (fn [i column-name] (iv/->DirectVector (.getVector read-root i) column-name)) column-order)
         indirect/->indirect-rel)))

(defn read-irel [^BufferAllocator allocator file column-order]
  (with-open [is (io/input-stream file)]
    (read-irel* allocator is column-order)))

(defn sort-irel [^BufferAllocator allocator ^core2.vector.IIndirectRelation irel order-specs]
  (let [sorted-idxs (sorted-idxs irel order-specs)]
    (-> (iv/select irel sorted-idxs) (irel-remove-indirection allocator))))

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

;; TODO sort out closing things
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

(defn sort-irels [^BufferAllocator allocator irel-files order-specs unique-file-fn column-order]
  (assert (< 1 (count irel-files)))
  (loop [irel-files irel-files]
    (if-not (< 1 (count irel-files))
      (first irel-files)
      (let [new-irels-files
            (->> (partition 2 irel-files)
                 (map (fn [[f1 f2]]
                        (let [^core2.vector.IIndirectRelation irel1 (read-irel allocator f1 column-order)
                              ^core2.vector.IIndirectRelation irel2 (read-irel allocator f2 column-order)
                              ^core2.vector.IIndirectRelation out-irel (two-merge-irels allocator irel1 irel2 order-specs)
                              new-irel-file (write-irel out-irel (unique-file-fn) column-order)]
                          (.close irel1)
                          (.close irel2)
                          (.close out-irel)
                          (util/delete-file (.toPath f1))
                          (util/delete-file (.toPath f2))
                          new-irel-file))))]
        (recur (cond-> new-irels-files
                 (odd? (count irel-files)) (conj (last irel-files))))))))

(defn- irel->column-names [irel]
  (map :name (seq irel)))

(comment
  (irel->column-names direct-rel))

(def ^:private block-threshold 16)
(def ^:private spill-threshold 200000)





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

(defn take-blocks ^core2.vector.IIndirectRelation [n ^BufferAllocator allocator ^ICursor in-cursor]
  (let [rel-writer (vw/->rel-writer allocator)]
    (try
      (loop [i 0]
        (if (.tryAdvance in-cursor
                         (reify Consumer
                           (accept [_ src-rel]
                             (vw/append-rel rel-writer src-rel))))
          (if (< i n)
            (recur (inc i))
            [(vw/rel-writer->reader rel-writer) true])
          [(vw/rel-writer->reader rel-writer) false]))
      (catch Exception e
        (.close rel-writer)
        (throw e)))))

(defn split-blocks ^core2.vector.IIndirectRelation [n ^BufferAllocator allocator ^ICursor in-cursor
                                                    unique-file-fn column-order order-specs]
  (println "foo")
  (loop [i 0 res [] rel-writer (vw/->rel-writer allocator)]
    (if (try
          (.tryAdvance in-cursor
                       (reify Consumer
                         (accept [_ src-rel]
                           (vw/append-rel rel-writer src-rel))))
          (catch Exception e
            (.close rel-writer)
            (throw e)))
      (if (< i n)
        ;; normal case
        (recur (inc i) res rel-writer)
        ;; split case not yet finished
        (let [read-rel (vw/rel-writer->reader rel-writer)
              sorted-rel (iv/select read-rel (sorted-idxs read-rel order-specs))
              without-indirection-rel (irel-remove-indirection sorted-rel allocator)
              new-irel-file (write-irel without-indirection-rel (unique-file-fn) column-order)]
          (.close without-indirection-rel)
          (.close sorted-rel)
          (.close read-rel)
          (util/try-close rel-writer)
          (recur 0 (conj res new-irel-file) (vw/->rel-writer allocator))))
      (if (= i 0)
        ;; finished, nothing in last rel
        (do
          (util/try-close rel-writer)
          res)
        ;; finished, something in last rel
        (let [read-rel (vw/rel-writer->reader rel-writer)
              sorted-rel (iv/select read-rel (sorted-idxs read-rel order-specs))
              without-indirection-rel (irel-remove-indirection sorted-rel allocator)
              new-irel-file (write-irel without-indirection-rel (unique-file-fn) column-order)]
          (.close without-indirection-rel)
          (.close sorted-rel)
          (.close read-rel)
          (util/try-close rel-writer)
          (conj res new-irel-file))))))

(defn unique-irel-file-fn [tmp-dir]
  (let [file (io/file (.getPath (.toUri tmp-dir)))
        s (atom (map #(io/file file (str "irel-" % ".arrow")) (range)))]
    (fn []
      (ffirst (swap-vals! s next)))))

(comment
  (util/with-tmp-dirs #{sort-dir}
    (let [file-fn (unique-irel-file-fn sort-dir)]
      [(file-fn) (file-fn)])))

#_(defn calculate-out-rels [^BufferAllocator allocator ^ICursor in-cursor order-specs]
   (let [[^IIndirectRelation first-rel continue?] (take-blocks block-threshold allocator in-cursor)]
     (if-not continue?
       [(iv/select first-rel (sorted-idxs first-rel order-specs))]
       (util/with-tmp-dirs #{sort-dir}
         (let [file1 (io/file (.getPath (.toUri sort-dir)) "tmp_order_by1.arrow")
               file2 (io/file (.getPath (.toUri sort-dir)) "tmp_order_by1.arrow")
               column-order (irel->column-names first-rel)
               first-irel-file (write-irel first-rel (unique-file-fn) column-order)
               irel-files (into [first-irel-file] (split-blocks block-threshold allocator in-cursor
                                                                unique-file-fn column-order order-specs))]
           (.close first-rel)
           [(read-irel allocator (sort-irels allocator irel-files order-specs unique-file-fn column-order) column-order)])))))


(defn number-of-batches [^BufferAllocator allocator file]
  (with-open [file-ch (FileChannel/open (.toPath file)
                                        (into-array OpenOption #{StandardOpenOption/READ}))
              file-reader (ArrowFileReader. file-ch allocator)]
    (count (.getRecordBlocks file-reader))))

(defn get-irel-from-vsr [^VectorSchemaRoot root]
  (indirect/->indirect-rel (map indirect/->direct-vec (.getFieldVectors root))))

(defn copy-irel-to-vsr [^IIndirectRelation irel ^VectorSchemaRoot vsr]
  (doseq [iv irel]
    (.copyTo iv (.getVector vsr (.getName iv))))
  (.setRowCount vsr (.rowCount irel)))

(defn load-batch ^IIndirectRelation [n ^ArrowFileReader file-reader]
  (let [blocks (.getRecordBlocks file-reader)
        _ (println (count blocks))
        arrow-block (nth blocks n)
        root (.getVectorSchemaRoot file-reader)]
    (when-not (.loadRecordBatch file-reader arrow-block)
      (log/error "Unable to load batch!"))
    (println (.contentToTSVString root))
    (get-irel-from-vsr root))
  #_(with-open [file-ch (FileChannel/open (.toPath file) (into-array OpenOption #{StandardOpenOption/READ}))
                file-reader (doto (ArrowFileReader. file-ch allocator)
                              (.initialize))]
      (let [blocks (.getRecordBlocks file-reader)
            _ (println (count blocks))
            arrow-block (nth blocks n)
            root (.getVectorSchemaRoot file-reader)]
        (when-not (.loadRecordBatch file-reader arrow-block)
          (log/error "Unable to load batch!"))
        (println (.contentToTSVString root))
        (batch->irel root))))

(comment
  (load-batch 2  (io/file "/tmp/arrow/order-by2.arrow"))


  )

(defn open-file-reader [^BufferAllocator allocator file]
  (let [file-ch (FileChannel/open (.toPath file) (into-array OpenOption #{StandardOpenOption/READ}))]
    (doto (ArrowFileReader. file-ch allocator)
      (.initialize))))

(defn col-types->schema [col-types]
  (Schema. (map (fn [[cn ct]] (types/col-type->field cn ct)) col-types)))

(defn- accumulate-relations ^core2.vector.IIndirectRelation
  [^BufferAllocator allocator ^ICursor in-cursor file order-specs col-types]
  (let [schema (col-types->schema col-types)]
    (with-open [os (io/output-stream file)
                write-root (VectorSchemaRoot/create schema allocator)
                writer (ArrowFileWriter. write-root nil (Channels/newChannel os))]
      (.start writer)
      (.forEachRemaining in-cursor
                         (reify Consumer
                           (accept [_ src-rel]
                             (with-open [out-rel (iv/select src-rel (sorted-idxs src-rel order-specs))]
                               (doseq [iv out-rel]
                                 (.copyTo iv (.getVector write-root (.getName iv))))
                               (.setRowCount write-root (.rowCount src-rel))
                               (.writeBatch writer))
                             #_(doseq [iv src-rel]
                                 (.copyTo iv (.getVector write-root (.getName iv))))
                             #_(.writeBatch writer)
                             #_(println (.contentToTSVString write-root))
                             #_(->> (seq src-rel)
                                    (map (fn [iv] )))

                             #_(vw/append-rel rel-writer src-rel))))
      (.end writer)
      #_(clojure.pprint/pprint (.getSchema write-root)))
    #_(load-batch 2 allocator (io/file "/tmp/arrow/order-by2.arrow"))


    #_(try
        (.forEachRemaining in-cursor
                           (reify Consumer
                             (accept [_ src-rel]
                               (vw/append-rel rel-writer src-rel))))
        (catch Exception e
          (.close rel-writer)
          (throw e)))

    #_(vw/rel-writer->reader rel-writer)))

#_(defn write-arrow-json-files
    ([^File arrow-dir]
     (write-arrow-json-files arrow-dir #".*"))
    ([^File arrow-dir file-pattern]
     (
      (doseq [^File file (.listFiles arrow-dir)
              :when (and (.endsWith (.getName file) ".arrow") (re-matches file-pattern (.getName file)))]
        (with-open [file-ch (FileChannel/open (.toPath file)
                                              (into-array OpenOption #{StandardOpenOption/READ}))
                    file-reader (ArrowFileReader. file-ch allocator)
                    file-writer (JsonFileWriter. (file->json-file file)
                                                 (.. (JsonFileWriter/config) (pretty true)))]
          (let [root (.getVectorSchemaRoot file-reader)]
            (.start file-writer (.getSchema root) nil)
            (while (.loadNextBatch file-reader)
              (.write file-writer root))))))))

(comment
  (require 'sc.api)

  (sc.api/letsc [1 -2]
                (-> (seq out-rel)
                    first
                    :v
                    (.getValueCount))))


(defn sort-irels [^BufferAllocator allocator irel-files order-specs unique-file-fn column-order]
  (assert (< 1 (count irel-files)))
  (loop [irel-files irel-files]
    (if-not (< 1 (count irel-files))
      (first irel-files)
      (let [new-irels-files
            (->> (partition 2 irel-files)
                 (map (fn [[f1 f2]]
                        (let [^core2.vector.IIndirectRelation irel1 (read-irel allocator f1 column-order)
                              ^core2.vector.IIndirectRelation irel2 (read-irel allocator f2 column-order)
                              ^core2.vector.IIndirectRelation out-irel (two-merge-irels allocator irel1 irel2 order-specs)
                              new-irel-file (write-irel out-irel (unique-file-fn) column-order)]
                          (.close irel1)
                          (.close irel2)
                          (.close out-irel)
                          (util/delete-file (.toPath f1))
                          (util/delete-file (.toPath f2))
                          new-irel-file))))]
        (recur (cond-> new-irels-files
                 (odd? (count irel-files)) (conj (last irel-files))))))))

(defn sort-batches [[file1 file2] allocator order-specs col-types]
  (let [schema (col-types->schema col-types)]
    (loop [file1 file1 file2 file2]
      (let [batches (number-of-batches allocator file1)]
        (if (<= (count batches) 1)
          file1
          (do
            (with-open [file-reader (open-file-reader allocator file1)
                        os (io/output-stream file2)
                        write-root (VectorSchemaRoot/create schema allocator)
                        file-writer (ArrowFileWriter. write-root nil (Channels/newChannel os))]
              (doseq [[i1 i2] (partition 2 (range batches))]
                (with-open [irel1 (load-batch i1 file-reader)
                            irel2 (load-batch i2 file-reader)
                            out-irel (get-irel-from-vsr write-root)]
                  (two-merge-irels allocator irel1 irel2)
                  )
                )

              )
            (util/delete-file file1)
            (recur file2 file1))))))

  )


(deftype OrderByCursor [^BufferAllocator allocator
                        ^ICursor in-cursor
                        col-types
                        order-specs
                        ^:unsynchronized-mutable done
                        ;; ^:unsynchronized-mutable sorted-file
                        ;; ^:unsynchronized-mutable batch-nb
                        ;; ^:unsynchronized-mutable ^boolean consumed?
                        ;; ^:unsynchronized-mutable out-rels
                        ]
  ICursor
  (tryAdvance [this c]
    (if (.done this)
      false
      (util/with-tmp-dirs #{sort-dir}
        (let [file1 (io/file (.getPath (.toUri sort-dir)) "tmp_order_by1.arrow")
              file2 (io/file (.getPath (.toUri sort-dir)) "tmp_order_by1.arrow")
              _ (io/make-parents file1)
              _ (accumulate-relations allocator in-cursor file1 order-specs col-types)
              sorted-file (sort-batches [file1 file2] allocator order-specs col-types)]
          (with-open [file-reader (open-file-reader allocator file1)
                      out-rel (load-batch 0 file-reader)]
            (set! (.done this) true)
            (if (pos? (.rowCount out-rel))
              (do
                (.accept c out-rel)
                true)
              false))
          #_(with-open [read-rel (accumulate-relations allocator in-cursor col-types)]
              (if (pos? (.rowCount read-rel))
                (with-open [out-rel (iv/select read-rel (sorted-idxs read-rel order-specs))]
                  (.accept c out-rel)
                  true)
                false))))))
  #_(tryAdvance [this c]

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
                     (OrderByCursor. allocator in-cursor col-types order-specs false))})))
