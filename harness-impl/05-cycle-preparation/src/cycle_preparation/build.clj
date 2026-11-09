(ns cycle-preparation.build
  (:require [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.util.jar JarOutputStream JarEntry JarFile]))

(defn package! [classpath]
  (let [seen (atom #{})]
    (with-open [out (JarOutputStream. (io/output-stream "product.jar"))]
      (letfn [(put! [name stream-fn]
                (when-not (or (contains? @seen name)
                              (= name "META-INF/MANIFEST.MF")
                              (re-find #"(?i)^META-INF/[^/]+\.(SF|RSA|DSA)$" name)
                              (str/ends-with? name "module-info.class"))
                  (swap! seen conj name)
                  (.putNextEntry out (doto (JarEntry. name) (.setTime 0)))
                  (with-open [in (stream-fn)] (io/copy in out))
                  (.closeEntry out)))]
        (doseq [path (cons "classes" (str/split classpath #":"))
                :let [f (io/file path)]
                :when (.exists f)]
          (if (.isDirectory f)
            (doseq [child (file-seq f) :when (.isFile child)]
              (put! (str (.relativize (.toPath f) (.toPath child))) #(io/input-stream child)))
            (with-open [jar (JarFile. f)]
              (doseq [entry (enumeration-seq (.entries jar)) :when (not (.isDirectory entry))]
                (put! (.getName entry) #(.getInputStream jar entry))))))))))

(defn -main [& _]
  (.mkdirs (io/file "classes"))
  (binding [*compile-path* "classes" *out* *err*]
    (compile 'cycle-preparation.product))
  (package! (System/getProperty "java.class.path"))
  (shutdown-agents))
