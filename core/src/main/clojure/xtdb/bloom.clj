(ns xtdb.bloom
  (:require [xtdb.types :as types]
            [xtdb.vector.indirect :as iv]
            [xtdb.vector.writer :as vw])
  (:import (xtdb.vector IIndirectVector IVectorWriter)
           java.nio.ByteBuffer
           org.apache.arrow.memory.BufferAllocator
           (org.apache.arrow.memory.util.hash MurmurHasher SimpleHasher)
           [org.apache.arrow.vector ValueVector VarBinaryVector]
           org.roaringbitmap.buffer.ImmutableRoaringBitmap
           org.roaringbitmap.RoaringBitmap))

(set! *unchecked-math* :warn-on-boxed)

;; max-rows  bloom-bits  false-positive
;; 1000      13           0.0288
;; 10000     16           0.0495
;; 10000     17           0.0085
;; 100000    20           0.0154

(def ^:const ^:private default-bloom-bits (bit-shift-left 1 20))

(defn- init-bloom-bits ^long []
  (let [bloom-bits (Long/getLong "xtdb.bloom.bits" default-bloom-bits)]
    (assert (= 1 (Long/bitCount bloom-bits)))
    bloom-bits))

(def ^:const bloom-bits (init-bloom-bits))
(def ^:const bloom-bit-mask (dec bloom-bits))
(def ^:const bloom-k 3)

(defn bloom-false-positive-probability?
  (^double [^long n]
   (bloom-false-positive-probability? n bloom-k bloom-bits))
  (^double [^long n ^long k ^long m]
   (Math/pow (- 1 (Math/exp (/ (- k) (double (/ m n))))) k)))

(defn bloom->bitmap ^org.roaringbitmap.buffer.ImmutableRoaringBitmap [^VarBinaryVector bloom-vec ^long idx]
  (let [pointer (.getDataPointer bloom-vec idx)
        nio-buffer (.nioBuffer (.getBuf pointer) (.getOffset pointer) (.getLength pointer))]
    (ImmutableRoaringBitmap. nio-buffer)))

(defn bloom-contains? [^VarBinaryVector bloom-vec
                       ^long idx
                       ^ints hashes]
  (let [^ImmutableRoaringBitmap bloom (bloom->bitmap bloom-vec idx)]
    (loop [n 0]
      (if (= n (alength hashes))
        true
        (if (.contains bloom (aget hashes n))
          (recur (inc n))
          false)))))

;; Cassandra-style hashes:
;; https://www.researchgate.net/publication/220770131_Less_Hashing_Same_Performance_Building_a_Better_Bloom_Filter
(defn bloom-hashes
  (^ints [^IIndirectVector col ^long idx]
   (bloom-hashes col idx bloom-k bloom-bit-mask))
  (^ints [^IIndirectVector col ^long idx ^long k ^long mask]
   (let [vec (.getVector col)
         idx (.getIndex col idx)
         hash-1 (.hashCode vec idx SimpleHasher/INSTANCE)
         hash-2 (.hashCode vec idx (MurmurHasher. hash-1))
         acc (int-array k)]
     (dotimes [n k]
       (aset acc n (unchecked-int (bit-and mask (+ hash-1 (* hash-2 n))))))
     acc)))

(defn literal-hashes ^ints [^BufferAllocator allocator literal]
  (let [col-type (types/value->col-type literal)]
    (with-open [^IVectorWriter writer (vw/->vec-writer allocator "_" col-type)]
      (.startValue writer)
      (types/write-value! literal writer)
      (.endValue writer)
      (bloom-hashes (iv/->direct-vec (.getVector writer)) 0))))

(defn write-bloom [^VarBinaryVector bloom-vec, ^long meta-idx, ^IIndirectVector col]
  (let [bloom (RoaringBitmap.)]
    (dotimes [in-idx (.getValueCount col)]
      (let [^ints el-hashes (bloom-hashes col in-idx)]
        (.add bloom el-hashes)))
    (let [ba (byte-array (.serializedSizeInBytes bloom))]
      (.serialize bloom (ByteBuffer/wrap ba))
      (.setSafe bloom-vec meta-idx ba))))
