(ns loyly.cycle-runner.effects
  "The effects in the real world: the harness self-check, the fence and its
   probe, the builder launch, the copy of the candidate, and the gate on the
   host (legacy verify.sh for the http seam, harness/v2/gate.sh for stdio).
   Each returns what happened; recording it is loyly.cycle-runner.advance's."
  (:refer-clojure :exclude [run!])
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [loyly.cycle-runner.repo :as repo]
            [loyly.cycle-runner.util :as u])
  (:import [java.net InetSocketAddress Socket URI]
           [java.net.http HttpClient HttpRequest HttpResponse$BodyHandlers]
           [java.time Duration]))

;; ---------------------------------------------------------------------------
;; The harness self-check

(defn self-check
  "LOYLY_SELF_CHECK_CMD run in the project repository: exit 0 passed; the last
   line printed is the failing check; each line starting SKIP a skipped check."
  [{:keys [self-check-cmd repo env]}]
  (let [{:keys [exit out]} (u/run! (u/shell-argv self-check-cmd [])
                                    {:dir repo :env (u/strip-env env) :timeout-ms (* 3600 1000)})
        lines (str/split-lines (or out ""))
        skipped (vec (for [l lines
                           :let [t (str/triml l)]
                           :when (str/starts-with? t "SKIP")]
                       (str/trim (subs t 4))))
        last-line (u/last-line out)]
    {:passed? (zero? exit)
     :exit exit
     :skipped skipped
     :failing-check (or last-line (str "the self-check exited " exit))
     :out out}))

;; ---------------------------------------------------------------------------
;; The fence: legacy fence.sh, then its probe.

(defn- under? [p entry]
  (let [e (str/replace entry #"/+$" "")]
    (or (= p e) (str/starts-with? p (str e "/")))))

(defn fence!
  "Build the clean room of the commit at `room` (which must not exist yet):
   a clone of depth 1, the fenced paths removed, one root commit, no origin,
   reflog expired and pruned; then probe it."
  [{:keys [repo commit fenced-paths]} room]
  (when (u/exists? room)
    (throw (ex-info (str "The clean room " room " exists already.") {})))
  (u/mkdirs! room)
  (let [g (fn [& args] (apply repo/git-ok room args))
        gc (fn [& args] (apply g "-c" "user.name=cycle-runner" "-c" "user.email=cycle-runner@loyly.invalid"
                               "-c" "commit.gpgsign=false" args))]
    (g "init" "-q")
    (g "fetch" "-q" "--depth" "1" (str "file://" (u/abs-path repo)) commit)
    (g "checkout" "-q" "--detach" "FETCH_HEAD")
    (let [tree-paths (str/split-lines (g "ls-tree" "-r" "--name-only" "-t" "HEAD"))
          found? (fn [entry] (some #(under? % entry) tree-paths))
          unmatched (vec (remove found? fenced-paths))
          matched (vec (filter found? fenced-paths))]
      (doseq [entry matched]
        (g "rm" "-r" "-q" "--cached" "--ignore-unmatch" "--" (str/replace entry #"/+$" ""))
        (u/delete-tree! (str room "/" (str/replace entry #"/+$" ""))))
      (gc "commit" "-q" "--allow-empty" "-m" "fence: remove fenced paths")
      ;; squash to one root commit
      (g "checkout" "-q" "--orphan" "clean-room")
      (gc "commit" "-q" "--allow-empty" "-m" (str "clean room of " commit))
      (doseq [b (remove str/blank? (map str/trim (str/split-lines (g "for-each-ref" "--format=%(refname)"))))
              :when (not= b "refs/heads/clean-room")]
        (g "update-ref" "-d" b))
      (repo/git room "remote" "remove" "origin")
      (doseq [f ["shallow" "FETCH_HEAD" "ORIG_HEAD"]]
        (u/delete-tree! (str room "/.git/" f)))
      (g "reflog" "expire" "--expire=now" "--all")
      (g "gc" "-q" "--prune=now")
      ;; the probe
      (let [roots (remove str/blank? (str/split-lines (g "rev-list" "--max-parents=0" "--all")))
            commits (parse-long (str/trim (g "rev-list" "--all" "--count")))
            tree-now (str/split-lines (g "ls-tree" "-r" "--name-only" "-t" "HEAD"))
            objects (for [l (str/split-lines (g "rev-list" "--objects" "--all"))
                          :let [[_ p] (str/split l #" " 2)]
                          :when p]
                      p)
            left (vec (filter (fn [e] (some #(under? % e) tree-now)) matched))
            reachable (vec (filter (fn [e] (some #(under? % e) objects)) fenced-paths))]
        {:clean-room-commit (str/trim (g "rev-parse" "HEAD"))
         :root-commits (count roots)
         :commits commits
         :unmatched-paths unmatched
         :paths-left-in-tree left
         :paths-reachable-in-object-graph reachable}))))

;; ---------------------------------------------------------------------------
;; The builder

(defn builder-room!
  "A fresh builder room: a copy of the clean room and an empty candidate scope."
  [clean-room room app-dir]
  (u/delete-tree! room)
  (u/copy-tree! clean-room room)
  (u/delete-tree! (str room "/" app-dir))
  (u/mkdirs! (str room "/" app-dir))
  room)

(defn launch-builder!
  "LOYLY_BUILDER_CMD <builder-room> <instruction-file>, waited for within the
   timeout. Its exit status is no evidence."
  [{:keys [builder-cmd env]} room instruction-file timeout-seconds log-file]
  (let [{:keys [exit out timed-out?]}
        (u/run! (u/shell-argv builder-cmd [room instruction-file])
                {:env (u/strip-env env) :timeout-ms (* 1000 (long timeout-seconds)) :out-file log-file})]
    {:exit exit :out out :timed-out? timed-out?}))

(defn take-candidate!
  "A copy of what the builder left in its candidate scope, and its digest."
  [room app-dir copy]
  (u/delete-tree! copy)
  (u/mkdirs! copy)
  (u/copy-tree! (str room "/" app-dir) copy)
  {:files (count (u/regular-files copy))
   :digest (u/tree-digest copy)})

(defn gate-room!
  "A fresh gate room for the attempt: the candidate's copy at APP_DIR; for the
   stdio seam also the clean room's files (not its history)."
  [{:keys [app-dir app-seam]} clean-room candidate-copy room]
  (u/delete-tree! room)
  (u/mkdirs! room)
  (when (= "stdio" app-seam)
    (u/copy-tree! clean-room room #(or (= % ".git") (str/starts-with? % ".git/"))))
  (u/delete-tree! (str room "/" app-dir))
  (u/copy-tree! candidate-copy (str room "/" app-dir))
  room)

;; ---------------------------------------------------------------------------
;; The gate on the host

(defn- answering? [port]
  (try
    (with-open [s (Socket.)]
      (.connect s (InetSocketAddress. "127.0.0.1" (int port)) 500)
      true)
    (catch Exception _ false)))

(defn- http-get [url]
  (try
    (let [client (-> (HttpClient/newBuilder) (.connectTimeout (Duration/ofMillis 500)) .build)
          req (-> (HttpRequest/newBuilder (URI. url)) (.timeout (Duration/ofSeconds 2)) .GET .build)
          resp (.send client req (HttpResponse$BodyHandlers/ofString))]
      {:status (.statusCode resp) :body (.body resp)})
    (catch Exception _ nil)))

(defn- write-evidence [dir name text]
  (u/spit-utf8 (str dir "/" name) (or text "")))

(defn- contract-missing [room app-dir]
  (let [t (str room "/" app-dir "/test.sh") s (str room "/" app-dir "/start.sh")]
    (cond-> []
      (not (u/executable? t)) (conj (str app-dir "/test.sh"))
      (not (u/executable? s)) (conj (str app-dir "/start.sh")))))

(defn- run-suite [room app-dir env evidence]
  (let [{:keys [exit out]} (u/run! [(str room "/" app-dir "/test.sh")]
                                   {:dir room :env env :timeout-ms (* 3 3600 1000)})]
    (write-evidence evidence "test.log" out)
    {:exit exit :out out}))

(defn gate-http
  "Legacy verify.sh's order and arguments, in the gate room. Returns the gate's
   result: {:refusal-kind ..} for a refusal to grade, else {:outcome ..}."
  [{:keys [app-dir port app-marker env]} {:keys [room probe evidence]}]
  (let [env (u/strip-env env)
        url (str "http://127.0.0.1:" port)
        base {:boot-budget-seconds 10 :boot-polls 20}]
    (u/delete-tree! evidence)
    (u/mkdirs! evidence)
    (let [missing (contract-missing room app-dir)]
      (cond
        (seq missing)
        (assoc base :outcome "failed" :reason "contract-missing"
               :detail (str "contract-missing: not executable: " (str/join ", " missing)))

        (not (:present? probe))
        (assoc base :refused "identity-probe-missing"
               :detail (str "identity-probe-missing: " (:path probe) " is not present and executable at the cut"))

        (answering? port)
        (assoc base :refused "seam-answered-before-boot"
               :detail (str "seam-answered-before-boot: something answers on port " port " before boot"))

        :else
        (let [{:keys [exit]} (run-suite room app-dir env evidence)]
          (if-not (zero? exit)
            (assoc base :outcome "failed" :reason "suite-red"
                   :detail (str "suite-red: " app-dir "/test.sh exited " exit))
            (let [proc (u/spawn! [(str room "/" app-dir "/start.sh") (str port)] {:dir room :env env})]
              (try
                (let [answer (loop [poll 1]
                               (if (> poll 20)
                                 nil
                                 (do (Thread/sleep 500)
                                     (let [r (http-get url)]
                                       (if (and r (< (:status r) 400))
                                         (assoc r :poll poll)
                                         (recur (inc poll)))))))]
                  (cond
                    (nil? answer)
                    (assoc base :outcome "failed" :reason "boot-budget-exhausted"
                           :detail (str "boot-budget-exhausted: " url " did not answer within 20 polls (10 seconds)"))

                    (not (str/includes? (str/lower-case (or (:body answer) ""))
                                        (str/lower-case (or app-marker ""))))
                    (assoc base :outcome "failed" :reason "identity-probe-failed" :answered-on-poll (:poll answer)
                           :detail (str "identity-probe-failed: the page at " url " does not carry the marker " app-marker))

                    :else
                    (let [{:keys [exit out]} (u/run! [(:path probe) url] {:dir room :env env :timeout-ms 120000})
                          said (str/trim (or out ""))]
                      (write-evidence evidence "probe.log" out)
                      (if (zero? exit)
                        (assoc base :outcome "passed" :answered-on-poll (:poll answer)
                               :identity-probe-result (if (str/blank? said) "the identity probe exited 0" said)
                               :detail (str "passed: answered on poll " (:poll answer) "; " said))
                        (assoc base :outcome "failed" :reason "identity-probe-failed" :answered-on-poll (:poll answer)
                               :detail (str "identity-probe-failed: " (or (u/last-line out) (str "the probe exited " exit))))))))
                (finally (u/kill-tree! proc))))))))))

(defn gate-stdio
  "harness/v2/gate.sh's order and arguments: the contract, the probe at the
   cut, the suite, the probe over the gate room's start.sh with the attempt's
   gate store, then the event-store check of that store."
  [{:keys [app-dir env store-check-cmd repo]} {:keys [room probe evidence]}]
  (let [env (u/strip-env env)
        base {:boot-budget-seconds 30 :boot-polls 1}
        store (str evidence "/events.sqlite")]
    (u/delete-tree! evidence)
    (u/mkdirs! evidence)
    (let [missing (contract-missing room app-dir)]
      (cond
        (seq missing)
        (assoc base :outcome "failed" :reason "contract-missing"
               :detail (str "contract-missing: not executable: " (str/join ", " missing)))

        (not (:present? probe))
        (assoc base :refused "identity-probe-missing"
               :detail (str "identity-probe-missing: " (:path probe) " is not present and executable (100755) at the cut"))

        :else
        (let [{:keys [exit]} (run-suite room app-dir env evidence)]
          (if-not (zero? exit)
            (assoc base :outcome "failed" :reason "suite-red"
                   :detail (str "suite-red: " app-dir "/test.sh exited " exit))
            (let [start-sh (u/abs-path (str room "/" app-dir "/start.sh"))
                  {:keys [exit out]} (u/run! [(:path probe) start-sh "30"]
                                             {:dir room :env (assoc env "GATE_STORE" store)
                                              :timeout-ms (* 300 1000)})
                  probe-said (str/trim (or out ""))]
              (write-evidence evidence "probe.log" out)
              (if-not (zero? exit)
                (assoc base :outcome "failed" :reason "identity-probe-failed"
                       :detail (str "identity-probe-failed: " (if (str/blank? probe-said) (str "the probe exited " exit) probe-said)))
                (if-not (u/program-runnable? store-check-cmd repo env)
                  {:could-not-run (str "the store check " (pr-str (or store-check-cmd ""))
                                       " cannot be started: no such executable program")}
                  (let [{:keys [exit out]} (u/run! (u/shell-argv store-check-cmd [store])
                                                   {:dir repo :env env :timeout-ms (* 600 1000)})
                        said (str/trim (or out ""))]
                    (write-evidence evidence "store-check.log" out)
                    (if (zero? exit)
                      (assoc base :outcome "passed" :answered-on-poll 1
                             :identity-probe-result (if (str/blank? probe-said) "the identity probe exited 0" probe-said)
                             :detail (str "passed: " probe-said (when-not (str/blank? said) (str "; store check: " said))))
                      (assoc base :outcome "failed" :reason "identity-probe-failed"
                             :detail (str "identity-probe-failed: the event-store check failed: "
                                          (if (str/blank? said) (str "it exited " exit) said))))))))))))))

(defn probe-at-cut!
  "Take the identity probe APP_PROBE from the cut into the run's directory,
   outside every room. Present means present and executable (git mode 100755)."
  [{:keys [repo commit app-probe]} dir]
  (let [bs (when-not (str/blank? app-probe) (repo/git-bytes repo commit app-probe))
        mode (when bs (repo/mode-at repo commit app-probe))
        target (str dir "/" (last (str/split (or app-probe "probe") #"/")))]
    (u/mkdirs! dir)
    (when bs
      (io/copy bs (io/file target))
      (when (= "100755" mode) (u/chmod-exec! target)))
    {:path target
     :bytes bs
     :present? (boolean (and bs (= "100755" mode)))}))
