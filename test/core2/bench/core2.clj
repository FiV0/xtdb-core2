(ns core2.bench.core2
  (:require
   [clojure.java.io :as io]
   [clojure.tools.logging :as log]
   [core2.api :as c2]
   [core2.bench :as b]
   [core2.bench.measurement :as bm]
   [core2.node :as node]
   [core2.test-util :as tu])
  (:import
   (io.micrometer.core.instrument MeterRegistry Tag Timer)
   (java.io Closeable File)
   (java.time Clock Duration)
   (java.util Random)
   (java.util.concurrent ConcurrentHashMap Executors)
   (java.util.concurrent.atomic AtomicLong)))

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

(defn install-proxy-node-meters!
  [^MeterRegistry meter-reg]
  (let [timer #(-> (Timer/builder %)
                   (.minimumExpectedValue (Duration/ofNanos 1))
                   (.maximumExpectedValue (Duration/ofMinutes 2))
                   (.publishPercentiles (double-array bm/percentiles))
                   (.register meter-reg))]
    {:submit-tx-timer (timer "node.submit-tx")
     :query-timer (timer "node.query")}))

(defmacro reify-protocols-accepting-non-methods
  "On older versions of XT node methods may be missing."
  [& reify-def]
  `(reify ~@(loop [proto nil
                   forms reify-def
                   acc []]
              (if-some [form (first forms)]
                (cond
                  (symbol? form)
                  (if (class? (resolve form))
                    (recur nil (rest forms) (conj acc form))
                    (recur form (rest forms) (conj acc form)))

                  (nil? proto)
                  (recur nil (rest forms) (conj acc form))

                  (list? form)
                  (if-some [{:keys [arglists]} (get (:sigs @(resolve proto)) (keyword (name (first form))))]
                    ;; arity-match
                    (if (some #(= (count %) (count (second form))) arglists)
                      (recur proto (rest forms) (conj acc form))
                      (recur proto (rest forms) acc))
                    (recur proto (rest forms) acc)))
                acc))))

(comment
  (require 'sc.api)

  )

(defn bench-proxy ^Closeable [node ^MeterRegistry meter-reg]
  (let [last-submitted (atom nil)
        last-completed (atom nil)

        submit-counter (AtomicLong.)
        indexed-counter (AtomicLong.)

        _
        (doto meter-reg
          #_(.gauge "node.tx" ^Iterable [(Tag/of "event" "submitted")] submit-counter)
          #_(.gauge "node.tx" ^Iterable [(Tag/of "event" "indexed")] indexed-counter))


        fn-gauge (partial bm/new-fn-gauge meter-reg)

        ;; on-indexed
        ;; (fn [{:keys [submitted-tx, doc-ids, av-count, bytes-indexed] :as event}]
        ;;   (reset! last-completed submitted-tx)
        ;;   (.getAndIncrement indexed-counter)
        ;;   nil)

        compute-lag-nanos #_(partial compute-nanos node last-completed last-submitted)
        (fn []
          (let [{:keys [latest-completed-tx] :as res} (c2/status node)]
            (or
             (when-some [[fut ms] @last-submitted]
               (let [tx-id (:tx-id @fut)]
                 (when-some [{completed-tx-id :tx-id
                              completed-tx-time :sys-time} latest-completed-tx]
                   (when (< completed-tx-id tx-id)
                     (* (long 1e6) (- ms (inst-ms completed-tx-time)))))))
             0)))

        compute-lag-abs
        (fn []
          (let [{:keys [latest-completed-tx] :as res} (c2/status node)]
            (or
             (when-some [[fut _] @last-submitted]
               (let [tx-id (:tx-id @fut)]
                 (when-some [{completed-tx-id :tx-id} latest-completed-tx ]
                   (- tx-id completed-tx-id))))
             0)))]


    (fn-gauge "node.tx.lag seconds" (comp #(/ % 1e9) compute-lag-nanos) {:unit "seconds"})
    (fn-gauge "node.tx.lag tx-id" compute-lag-abs )

    (reify
      c2/PClient
      (-open-datalog-async [_ query args] (c2/-open-datalog-async node query args))
      (-open-sql-async [_ query query-opts] (c2/-open-sql-async node query query-opts))

      node/PNode
      (snapshot-async [_] (node/snapshot-async node))
      (snapshot-async [_ tx] (node/snapshot-async node tx))
      (snapshot-async [_ tx timeout] (node/snapshot-async node tx timeout))

      c2/PStatus
      (status [_]  #_(c2/status node)
              (let [{:keys [latest-completed-tx] :as res} (c2/status node)]
                (reset! last-completed latest-completed-tx)
                res))

      c2/PSubmitNode
      (submit-tx [_ tx-ops] #_(c2/submit-tx node tx-ops)
                 (let [ret (c2/submit-tx node tx-ops)]
                   (reset! last-submitted [ret (System/currentTimeMillis)])
                   ;; (.incrementAndGet submit-counter)
                   ret))
      (submit-tx [_ tx-ops opts] #_(c2/submit-tx node tx-ops opts)
                 (let [ret (c2/submit-tx node tx-ops opts)]
                   (reset! last-submitted [ret (System/currentTimeMillis)])
                   ;; (.incrementAndGet submit-counter)
                   ret))

      Closeable
      ;; o/w some stage closes the node for later stages
      (close [_] nil #_(.close node)))))

(defn wrap-task [task f]
  (let [{:keys [stage]} task]
    (bm/wrap-task
     task
     (if stage
       (fn instrumented-stage [worker]
         (if bm/*stage-reg*
           (with-open [node-proxy (bench-proxy (:sut worker) bm/*stage-reg*)]
             (f (assoc worker :sut node-proxy)))
           (f worker)))
       f))))

(defn run-benchmark
  [{:keys [node-opts
           benchmark-type
           benchmark-opts]}]
  (let [benchmark
        (case benchmark-type
          :auctionmark
          ((requiring-resolve 'core2.bench.auctionmark/benchmark) benchmark-opts)
          #_#_:tpch
          ((requiring-resolve 'xtdb.bench2.tpch/benchmark) benchmark-opts)
          #_#_:trace (trace benchmark-opts))
        benchmark-fn (b/compile-benchmark
                      benchmark
                      ;; @(requiring-resolve `core2.bench.measurement/wrap-task)
                      (fn [task f] (wrap-task task f)))]

    (with-open [node (tu/->local-node node-opts)]
      (benchmark-fn node))))

(defn delete-directory-recursive
  "Recursively delete a directory."
  [^java.io.File file]
  (when (.isDirectory file)
    (run! delete-directory-recursive (.listFiles file)))
  (io/delete-file file))

(comment
  ;; ======
  ;; Running in process
  ;; ======

  (require 'dev
           '[core2.api :as c2])

  (do
    (dev/halt)
    (dev/go)
    (c2/status dev/node)
    )

  (def run-duration "PT5S")
  (def run-duration "PT10S")
  (def run-duration "PT2M")
  (def run-duration "PT10M")


  (delete-directory-recursive (io/file "dev/dev-node"))
  (def report-core2
    (run-benchmark
     {:node-opts {:node-dir (.toPath (io/file "dev/dev-node"))}
      :benchmark-type :auctionmark
      :benchmark-opts {:duration run-duration :sync true
                       :scale-factor 0.1 :threads 8}}))

  (->> report-core2-1 :metrics (filter #(clojure.string/starts-with? (:id %) "node" )))

  (def report-rocks (clojure.edn/read-string (slurp (io/file "../xtdb/core1-rocks-10s.edn"))))

  (require 'core2.bench.report)
  (core2.bench.report/show-html-report
   (core2.bench.report/vs
    "core2"
    report-core2-1))

  (core2.bench.report/show-html-report
   (core2.bench.report/vs
    "core2"
    report-core2-1
    "rocks"
    report-rocks))

  (spit (io/file "core2-10s.edn") report-core2)
  (def report-slurped (clojure.edn/read-string (slurp (io/file "core2-10s.edn"))))
  (keys report-slurped)


  (tu/with-tmp-dirs #{node-dir}
    (def report1-rocks
      (run-benchmark
       {:node-opts {:node-dir node-dir}
        :benchmark-type :auctionmark
        :benchmark-opts {:duration run-duration}})))

  (def report1-rocks
    (run-benchmark
     {:node-opts {:index :rocks, :log :rocks, :docs :rocks}
      :benchmark-type :auctionmark
      :benchmark-opts {:duration run-duration}}))


  (def report1-lmdb
    (run-benchmark
     {:node-opts {:index :lmdb, :log :lmdb, :docs :lmdb}
      :benchmark-type :auctionmark
      :benchmark-opts {:duration run-duration}}))

  (xtdb.bench2.report/show-html-report
   (xtdb.bench2.report/vs
    "Rocks"
    report1-rocks
    "LMDB"
    report1-lmdb))

  ;; ======
  ;; TPC-H (temporary while I think)
  ;; ======
  (def sf 0.05)

  (def report-tpch-rocks
    (run-benchmark
     {:node-opts {:index :rocks, :log :rocks, :docs :rocks}
      :benchmark-type :tpch
      :benchmark-opts {:scale-factor sf}}))

  (def report-tpch-lmdb
    (run-benchmark
     {:node-opts {:index :lmdb, :log :lmdb, :docs :lmdb}
      :benchmark-type :tpch
      :benchmark-opts {:scale-factor sf}}))

  (xtdb.bench2.report/show-html-report
   (xtdb.bench2.report/vs
    "Rocks"
    report-tpch-rocks
    "LMDB"
    report-tpch-lmdb))

  ;; ======
  ;; Running in EC2
  ;; ======

  ;; step 1 build system-under-test .jar
  (def jar-file (build-jar {:version "1.22.0", :modules [:lmdb :rocks]}))

  ;; make jar available to download for ec2 nodes
  (def jar-hash (s3-upload-jar jar-file))
  ;; the path to the jar in s3 is given by its hash string
  (s3-jar-path jar-hash)

  ;; step 2 provision resources
  (def ec2-stack-id (str "bench-" (System/currentTimeMillis)))
  (def ec2-stack (ec2/provision ec2-stack-id {:instance "t3.small"}))
  (def ec2 (ec2/handle ec2-stack))

  ;; step 3 setup ec2 for running core1 benchmarks
  (ec2-setup ec2)
  ;; download the jar
  (ec2-get-jar ec2 jar-hash)
  ;; activate this hash (you could grab more than one jar and put it on the box)
  (ec2-use-jar ec2 jar-hash)

  ;; step 4 run your benchmark
  (def report-s3-path (format "s3://xtdb-bench/b2/report/%s.edn" ec2-stack-id))

  ;; todo java opts
  (ec2-run-benchmark
   ec2
   {:run-benchmark-opts
    {:node-opts {:index :rocks, :log :rocks, :docs :rocks}
     :benchmark-type :auctionmark
     :benchmark-opts {:duration run-duration}}
    :report-s3-path report-s3-path})

  ;; step 5 get your report

  (def report-file (File/createTempFile "report" ".edn"))

  (bt/aws "s3" "cp" report-s3-path (.getAbsolutePath report-file))

  (def report2 (edn/read-string (slurp report-file)))

  ;; step 6 visualise your report

  (require 'xtdb.bench2.report)
  (xtdb.bench2.report/show-html-report
   (xtdb.bench2.report/vs
    "Report2"
    report2))

  ;; compare to the earlier in-process report (now imagine running on n nodes with different configs)
  (xtdb.bench2.report/show-html-report
   (xtdb.bench2.report/vs
    "On laptop"
    report1-rocks
    "In EC2"
    report2))

  ;; filter reports to just :oltp stage
  (let [filter-report #(xtdb.bench2.report/stage-only % :oltp)
        report1 (filter-report report1-rocks)
        report2 (filter-report report2)]
    (xtdb.bench2.report/show-html-report
     (xtdb.bench2.report/vs
      "On laptop"
      report1
      "In EC2"
      report2)))


  ;; here is a bigger script comparing a couple of versions

  ;; run 1-21-0
  (def report-1-21-0-path (new-s3-report-path))

  ;; build
  (do
    (def jar-1-21-0 (s3-upload-jar (build-jar {:version "1.21.0", :modules [:rocks]})))
    (ec2-get-jar ec2 jar-1-21-0))

  ;; run
  (do
    (ec2-use-jar ec2 jar-1-21-0)
    (ec2-run-benchmark
     ec2
     {:env {"MALLOC_ARENA_MAX" 2}
      :java-opts ["--add-opens java.base/java.util.concurrent=ALL-UNNAMED"]
      :run-benchmark-opts
      {:node-opts {:index :rocks, :log :rocks, :docs :rocks}
       :benchmark-type :auctionmark
       :benchmark-opts {:duration run-duration}}
      :report-s3-path report-1-21-0-path})

    (def report-1-21-0-file (File/createTempFile "report" ".edn"))
    (bt/aws "s3" "cp" report-1-21-0-path (.getAbsolutePath report-1-21-0-file))
    (def report-1-21-0 (edn/read-string (slurp report-1-21-0-file)))

    )

  ;; run 1-22-0
  (def report-1-22-0-path (new-s3-report-path))

  ;; build
  (do
    (def jar-1-22-0 (s3-upload-jar (build-jar {:version "1.22.0", :modules [:rocks]})))
    (ec2-get-jar ec2 jar-1-22-0))

  ;; run
  (do
    (ec2-use-jar ec2 jar-1-22-0)
    (ec2-run-benchmark
     ec2
     {:run-benchmark-opts
      {:node-opts {:index :rocks, :log :rocks, :docs :rocks}
       :benchmark-type :auctionmark
       :benchmark-opts {:duration run-duration}}
      :report-s3-path report-1-22-0-path})
    (def report-1-22-0-file (File/createTempFile "report" ".edn"))
    (bt/aws "s3" "cp" report-1-22-0-path (.getAbsolutePath report-1-22-0-file))
    (def report-1-22-0 (edn/read-string (slurp report-1-22-0-file))))

  ;; report on both
  (let [filter-report #(xtdb.bench2.report/stage-only % :oltp)
        report1 (filter-report report-1-21-0)
        report2 (filter-report report-1-22-0)]
    (xtdb.bench2.report/show-html-report
     (xtdb.bench2.report/vs
      "1.21.0"
      report1
      "1.22.0"
      report2)))

  ;; ===
  ;; EC2 misc
  ;; ===
  ;; misc tools:
  ;; you can open a repl if something is wrong (close with .close), right now needs 5555 locally to forward over ssh
  (def repl (ec2-repl ec2))
  (.close repl)

  ;; if you lose a handle get it again from the stack
  (def ec2 (ec2/handle (ec2/cfn-stack-describe ec2-stack-id)))

  ;; run something over ssh
  (ec2/ssh ec2 "ls" "-la")

  ;; kill the java process!
  (kill-java ec2)

  ;; find stacks starting "bench-"
  (ec2/cfn-stack-ls)

  (doseq [{:strs [StackName]} (ec2/cfn-stack-ls)]
    (println "run this to delete the stack" (pr-str (list 'ec2/cfn-stack-delete StackName))))

  ;; CLEANUP! delete your node when finished with it
  (ec2/cfn-stack-delete ec2-stack-id)

  )
