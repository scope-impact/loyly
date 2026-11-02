(ns loyly.pi-harness.subject)

(defn digest [root]
  (let [directory (java.io.File. root)]
    (when-not (.isDirectory directory)
      (throw (ex-info "generated subject directory does not exist" {:root root})))
    (let [base (.toPath (.getCanonicalFile directory))
          files (->> (file-seq directory)
                     (filter #(.isFile ^java.io.File %))
                     (sort-by #(.toString (.relativize base (.toPath ^java.io.File %)))))
          digest (java.security.MessageDigest/getInstance "SHA-256")
          separator (byte-array [(byte 0)])]
      (doseq [file files]
        (let [relative (.replace (.toString (.relativize base (.toPath ^java.io.File file))) "\\" "/")]
          (.update digest (.getBytes relative "UTF-8"))
          (.update digest separator)
          (with-open [input (java.io.FileInputStream. ^java.io.File file)]
            (let [buffer (byte-array 8192)]
              (loop []
                (let [read-count (.read input buffer)]
                  (when (pos? read-count)
                    (.update digest buffer 0 read-count)
                    (recur))))))
          (.update digest separator)))
      (format "%064x" (BigInteger. 1 (.digest digest))))))
