(ns loyly.cycle-runner.effects
  "The effects in the real world (seam.md, The effects): the harness self-check, the fence
   (legacy fence.sh's steps) and its probe, the builder launch, the install, and the gate on
   the host in legacy verify.sh's order and arguments. Each returns what it found; the
   advance-run processor records it into the context that owns it."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [loyly.cycle-runner.repo :as repo]
            [loyly.cycle-runner.util :as u])
  (:import [java.net HttpURLConnection InetSocketAddress Socket URL]))

(defn clean-env
  "The environment of what the runner launches: its own, without any LOYLY_* value and
   without a configuration value (legacy sources config.sh, it does not export it)."
  []
  (into {} (remove (fn [[k _]] (str/starts-with? k "LOYLY_"))) (System/getenv)))

;; ---------------------------------------------------------------------------
;; E2: the harness self-check
;; ---------------------------------------------------------------------------

(defn skipped-checks
  "Each line starting SKIP names a skipped check: the rest of the line after SKIP and its
   whitespace, trimmed."
  [out]
  (->> (u/lines out)
       (keep #(when-let [[_ rest] (re-matches #"^SKIP(?:\s+(.*))?$" (str/trim %))]
                (str/trim (or rest ""))))
       (remove str/blank?)
       vec))

(defn self-check
  "Run the self-check command in the project repository."
  [{:keys [repo self-check-cmd]}]
  (let [{:keys [exit out timed-out?]} (u/run-process ["sh" "-c" self-check-cmd]
                                                     {:dir repo :env (clean-env) :timeout-ms 3600000})]
    {:exit exit
     :passed? (and (zero? exit) (not timed-out?))
     :last-line (u/last-line out)
     :skipped (skipped-checks out)
     :out out}))

;; ---------------------------------------------------------------------------
;; E3: the fence and its probe
;; ---------------------------------------------------------------------------

(defn- in-tree? [room path]
  (let [p (str/replace path #"/+$" "")]
    (seq (str/trim (or (repo/git-out room "ls-tree" "HEAD" "--" p) "")))))

(defn fence
  "Legacy fence.sh, its one argument the clean room's path (which must not exist yet): a
   file:// clone of depth 1 at the commit, each fenced path found or matching nothing,
   removed, committed, squashed to one root commit, origin removed, reflog expired, pruned.
   Then the probe."
  [{:keys [repo commit config]} room]
  (when (.exists (io/file room))
    (throw (ex-info (str "The clean room " room " already exists.") {})))
  (u/mkdirs room)
  (let [g (fn [& args] (let [r (apply repo/git room args)]
                         (when-not (zero? (:exit r))
                           (throw (ex-info (str "git " (str/join " " args) " failed: " (:out r)) r)))
                         (:out r)))
        id ["-c" "user.name=cycle-runner" "-c" "user.email=cycle-runner@loyly.invalid"
            "-c" "commit.gpgsign=false"]]
    (g "init" "-q")
    (g "remote" "add" "origin" (str "file://" (u/abs-path repo)))
    (g "fetch" "-q" "--depth" "1" "origin" commit)
    (g "checkout" "-q" "-B" "main" "FETCH_HEAD")
    (let [fenced (:fenced-paths config)
          found (filterv #(in-tree? room %) fenced)
          unmatched (filterv #(not (in-tree? room %)) fenced)]
      (doseq [p found] (g "rm" "-r" "-q" "--" (str/replace p #"/+$" "")))
      (doseq [p found] (u/delete-tree! (io/file room (str/replace p #"/+$" ""))))
      (apply g (concat id ["commit" "-q" "--allow-empty" "-m" "fence"]))
      (g "checkout" "-q" "--orphan" "clean")
      (apply g (concat id ["commit" "-q" "--allow-empty" "-m" (str "clean room of " commit)]))
      (g "branch" "-D" "main")
      (g "branch" "-m" "main")
      (g "remote" "remove" "origin")
      (u/delete-tree! (io/file room ".git" "FETCH_HEAD"))
      (u/delete-tree! (io/file room ".git" "shallow"))
      (g "reflog" "expire" "--expire=now" "--all")
      (g "gc" "-q" "--prune=now")
      ;; The probe.
      (let [roots (count (u/lines (g "rev-list" "--max-parents=0" "--all")))
            commits (count (u/lines (g "rev-list" "--all")))
            tree (u/lines (g "ls-tree" "-r" "--name-only" "HEAD"))
            objects (keep #(second (str/split % #" " 2)) (u/lines (g "rev-list" "--objects" "--all")))
            entry (fn [p] (str/replace p #"/+$" ""))
            hit? (fn [paths p] (some #(or (= % (entry p)) (str/starts-with? % (str (entry p) "/"))) paths))
            left (filterv #(or (hit? tree %) (.exists (io/file room (entry %)))) fenced)
            reachable (filterv #(hit? objects %) fenced)]
        {:room room
         :clean-room-commit (str/trim (g "rev-parse" "HEAD"))
         :fenced-paths fenced
         :found found
         :root-commits roots
         :commits commits
         :unmatched-paths unmatched
         :paths-left-in-tree left
         :paths-reachable-in-object-graph reachable}))))

;; ---------------------------------------------------------------------------
;; E4, E6: a builder room, the launch, the candidate left behind
;; ---------------------------------------------------------------------------

(defn candidate-digest
  "sha256 of one line per file of the candidate scope, <path> TAB <sha256 hex> LF, by path."
  [dir]
  (u/sha256-of-string
   (apply str (map (fn [p] (str p "\t" (u/sha256-hex (u/file-bytes (io/file dir p))) "\n"))
                   (u/files-under dir)))))

(defn prepare-builder-room
  "A fresh builder room: a copy of the clean room and an empty candidate scope."
  [clean-room room app-dir]
  (u/copy-tree! clean-room room)
  (u/delete-tree! (io/file room app-dir))
  (u/mkdirs (io/file room app-dir))
  room)

(defn launch-builder
  "Launch `<cmd> <builder-room> <instruction-file>` and wait for it within timeout-seconds.
   When it ends, take a copy of what it left in its candidate scope. Its exit is no verdict."
  [{:keys [builder-cmd]} room instruction-file app-dir snapshot-dir timeout-seconds log-file]
  (let [p (try (u/start-process ["sh" "-c" (str builder-cmd " \"$1\" \"$2\"") "sh" room instruction-file]
                                {:dir room :env (clean-env) :out (io/file log-file)})
               (catch Exception e e))]
    (if (instance? Exception p)
      {:could-not-run (str "The builder could not be started: " (.getMessage ^Exception p))}
      (do
        (.close (.getOutputStream ^Process p))
        (let [done? (.waitFor ^Process p (long timeout-seconds) java.util.concurrent.TimeUnit/SECONDS)]
          (if-not done?
            (do (u/kill-tree! p)
                {:timed-out? true :timeout-seconds timeout-seconds})
            (let [exit (.exitValue ^Process p)
                  scope (io/file room app-dir)]
              ;; A builder that leaves processes behind is stopped with it.
              (u/kill-tree! p)
              (u/copy-tree! scope snapshot-dir)
              (let [files (u/files-under snapshot-dir)]
                {:exit exit
                 :files (count files)
                 :snapshot snapshot-dir
                 :candidate-digest (when (seq files) (candidate-digest snapshot-dir))}))))))))

;; ---------------------------------------------------------------------------
;; E5: install and gate
;; ---------------------------------------------------------------------------

(defn install
  "A fresh gate room under the run's directory: the clean room and a copy of the candidate
   as taken when its builder ended, writable by the candidate's own scripts. The digest is
   the copy's as taken, before the gate runs."
  [clean-room snapshot gate-room app-dir]
  (u/copy-tree! clean-room gate-room)
  (u/delete-tree! (io/file gate-room app-dir))
  (u/copy-tree! snapshot (io/file gate-room app-dir))
  {:gate-room gate-room :candidate-digest (candidate-digest (io/file gate-room app-dir))})

(defn port-answers? [port]
  (try
    (with-open [s (Socket.)]
      (.connect s (InetSocketAddress. "127.0.0.1" (int port)) 500)
      true)
    (catch Exception _ false)))

(defn http-get
  "GET url like `curl -s`: {:status n :body text}, or nil when nothing answers."
  [url]
  (try
    (let [c ^HttpURLConnection (.openConnection (URL. url))]
      (.setConnectTimeout c 1000)
      (.setReadTimeout c 5000)
      (let [status (.getResponseCode c)
            body (try (slurp (if (< status 400) (.getInputStream c) (.getErrorStream c)))
                      (catch Exception _ ""))]
        {:status status :body (or body "")}))
    (catch Exception _ nil)))

(defn gate
  "Legacy verify.sh's order and arguments, on the host, in the gate room. Returns the
   gate's result: {:outcome :reason :seam-answered-before-boot :identity-probe-present
   :answered-on-poll :identity-probe-result :detail}; evidence written under evidence-root."
  [{:keys [config]} gate-room evidence-root timeout-seconds]
  (u/delete-tree! evidence-root)
  (u/mkdirs evidence-root)
  (let [{:keys [app-dir port app-marker app-probe boot-polls]} config
        log (io/file evidence-root "gate.log")
        note (fn [& xs] (spit log (str (apply str xs) "\n") :append true))
        env (clean-env)
        test-sh (io/file gate-room app-dir "test.sh")
        start-sh (io/file gate-room app-dir "start.sh")
        probe (io/file gate-room (or app-probe ""))
        url (str "http://127.0.0.1:" port)
        probe-present? (and app-probe (u/executable? probe))
        result (fn [m] (note "verdict: " (u/->json m)) m)]
    (note "gate room " gate-room "; URL " url)
    (cond
      (not (and (u/executable? test-sh) (u/executable? start-sh)))
      (result {:outcome "failed" :reason "contract-missing" :identity-probe-present (boolean probe-present?)
               :seam-answered-before-boot false
               :detail (str app-dir "/test.sh and " app-dir "/start.sh must both be executable"
                            (when-not (u/executable? start-sh) (str "; no executable " app-dir "/start.sh"))
                            (when-not (u/executable? test-sh) (str "; no executable " app-dir "/test.sh")))})

      (not probe-present?)
      (result {:outcome "failed" :reason "identity-probe-failed" :identity-probe-present false
               :seam-answered-before-boot false
               :detail (str "the identity probe " app-probe " is missing or not executable")})

      (port-answers? port)
      (result {:outcome "failed" :reason "boot-budget-exhausted" :identity-probe-present true
               :seam-answered-before-boot true
               :detail (str "something already answers on port " port " before boot")})

      :else
      (let [suite (u/run-process [(u/abs-path test-sh)] {:dir gate-room :env env
                                                         :timeout-ms (* 1000 timeout-seconds)})]
        (spit (io/file evidence-root "test.log") (:out suite))
        (note "test.sh exit " (:exit suite) (when (:timed-out? suite) " (timed out)"))
        (if-not (zero? (:exit suite))
          (result {:outcome "failed" :reason "suite-red" :identity-probe-present true
                   :seam-answered-before-boot false
                   :detail (str "test.sh exited " (:exit suite)
                                (when (:timed-out? suite) " (stopped at the gate timeout)")
                                ": " (u/last-line (:out suite)))})
          (let [server (u/start-process [(u/abs-path start-sh) (str port)]
                                        {:dir gate-room :env env :out :discard})]
            (try
              (.close (.getOutputStream server))
              (let [answered (loop [i 1]
                               (cond
                                 (let [r (http-get url)] (and r (< (:status r) 400))) i
                                 (>= i boot-polls) nil
                                 :else (do (Thread/sleep 500) (recur (inc i)))))]
                (note "boot answered on poll " answered)
                (if-not answered
                  (result {:outcome "failed" :reason "boot-budget-exhausted" :identity-probe-present true
                           :seam-answered-before-boot false
                           :detail (str url " did not answer within " boot-polls " polls")})
                  (let [page (:body (http-get url))]
                    (spit (io/file evidence-root "page.html") (or page ""))
                    (if-not (str/includes? (str/lower-case (or page "")) (str/lower-case (or app-marker "")))
                      (result {:outcome "failed" :reason "identity-probe-failed" :identity-probe-present true
                               :seam-answered-before-boot false :answered-on-poll answered
                               :detail (str "the page at " url " does not carry the marker " app-marker)})
                      (let [pr (u/run-process [(u/abs-path probe) url] {:dir gate-room :env env :timeout-ms 60000})
                            line (u/last-line (:out pr))]
                        (note "probe exit " (:exit pr) ": " line)
                        (if (zero? (:exit pr))
                          (result {:outcome "passed" :identity-probe-present true
                                   :seam-answered-before-boot false :answered-on-poll answered
                                   :identity-probe-result (if (str/blank? line) "identity proved" line)
                                   :detail (str "suite green, booted on poll " answered ", identity proved")})
                          (result {:outcome "failed" :reason "identity-probe-failed" :identity-probe-present true
                                   :seam-answered-before-boot false :answered-on-poll answered
                                   :identity-probe-result line
                                   :detail (if (str/blank? line) (str "the identity probe exited " (:exit pr)) line)})))))))
              (finally (u/kill-tree! server)))))))))
