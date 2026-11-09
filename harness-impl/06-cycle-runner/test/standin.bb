;; A stand-in context's launch entrypoint (the suite's, never the product's):
;; it forwards the JSON-RPC lines the runner writes on its stdin to the suite's
;; stand-in server, which plays the context, and the server's replies to its
;; stdout. It exits when the server closes the connection.
(require '[cheshire.core :as json]
         '[clojure.java.io :as io])

(let [[port context] *command-line-args*
      sock (java.net.Socket. "127.0.0.1" (Integer/parseInt port))
      out (.getOutputStream sock)
      in (io/reader (.getInputStream sock) :encoding "UTF-8")
      stdin (io/reader System/in :encoding "UTF-8")
      write! (fn [^String s] (.write out (.getBytes (str s "\n") "UTF-8")) (.flush out))]
  (write! (json/generate-string
           {:context context
            :store (System/getenv "LOYLY_GRAIN_STORE")
            :commits (System/getenv "LOYLY_PROJECT_COMMITS")
            :digests (System/getenv "LOYLY_SCENARIO_DIGESTS")
            :scoring (System/getenv "LOYLY_SCORING_TOOL_PATHS")
            :loyly-env (vec (filter #(.startsWith ^String % "LOYLY_") (keys (System/getenv))))}))
  (future
    (loop []
      (if-let [line (.readLine in)]
        (do (println line) (flush) (recur))
        (System/exit 0))))
  (loop []
    (if-let [line (.readLine stdin)]
      (do (write! line) (recur))
      (do (.shutdownOutput sock)
          (Thread/sleep 2000)
          (System/exit 0)))))
