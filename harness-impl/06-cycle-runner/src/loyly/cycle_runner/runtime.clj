(ns loyly.cycle-runner.runtime
  "What a run holds in memory while it runs: what was handed in when it
   started, its contexts' processes, and the facts of the real world its
   effects produced (the copies taken, the rooms built). The contracts
   ProjectRepository (is_commit) and ContextSeams (contexts_respond) are
   answered from here."
  (:require [loyly.cycle-runner.repo :as repo]))

(defonce runs (atom {}))

(defn get-run [run-id] (get @runs run-id))

(defn put-run! [run-id m] (swap! runs assoc run-id m))

(defn update-run! [run-id f & args] (apply swap! runs update run-id f args))

(defn forget-run! [run-id] (swap! runs dissoc run-id))

(defn commit? [run-id commit]
  (repo/commit? (get-in @runs [run-id :repo]) commit))

(defn contexts-not-responding [run-id]
  (vec (get-in @runs [run-id :not-responding])))
