(ns channeler.test-lib)

;; TODO: does deleteOnExit work when the JVM exits due to lein test failure??

(defn tmp-dir
  []
  (let [empty-attrib (into-array java.nio.file.attribute.FileAttribute [])
        path (-> (System/getProperty "java.io.tmpdir")
                  (java.nio.file.Path/of (into-array String []))
                  (java.nio.file.Files/createTempDirectory nil empty-attrib))
        file (.toFile path)]
    (.deleteOnExit file)
    file))

(defn dir-mock-ctx
  [dir]
  {:conf {"dir" dir}})

(defn wait-for
  [cond-fn timeout]
  (if (cond-fn)
    true
    (if (<= timeout 0)
      false
      (do (Thread/sleep 10)
          (recur cond-fn (- timeout 10))))))
