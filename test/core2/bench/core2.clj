(ns core2.bench.core2
  (:require [core2.api :as c2]
            [core2.bench :as b]
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
                      (fn [_task f] f)
                      #_(let [rocks-wrap (xtdb.bench2.rocksdb/stage-wrapper
                                          (fn [_]
                                            *rocks-stats-cubby-hole*))]
                          (fn [task f]
                            (-> (wrap-task task f)
                                (cond->> (:stage task) (rocks-wrap task))))))]

    (with-open [node (tu/->local-node node-opts)]
      (benchmark-fn node))
    #_(binding [*rocks-stats-cubby-hole* {}]
        (with-open [node (xt/start-node (undata-node-opts node-opts))]
          (benchmark-fn node)))))


(comment
  ;; ======
  ;; Running in process
  ;; ======

  (def run-duration "PT10S")
  (def run-duration "PT2M")
  (def run-duration "PT10M")

  (require '[clojure.java.io :as io])

  (defn delete-directory-recursive
    "Recursively delete a directory."
    [^java.io.File file]
    (when (.isDirectory file)
      (run! delete-directory-recursive (.listFiles file)))
    (io/delete-file file))

  (delete-directory-recursive (io/file "dev/dev-node"))

  (def report-core2
    (run-benchmark
     {:node-opts {:node-dir (.toPath (io/file "dev/dev-node"))}
      :benchmark-type :auctionmark
      :benchmark-opts {:duration run-duration}}))

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

  (require 'core2.bench.report)
  (core2.bench.report/show-html-report
   (core2.bench.report/vs
    "core2"
    report-core2))

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
