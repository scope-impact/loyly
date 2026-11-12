(ns runner.main
  (:gen-class)
  (:require [runner.service :as service] [runner.engine :as engine] [runner.model :as model]
            [runner.util :as u] [malli.core :as m]))
(def methods #{:cycle-runner/start-run :cycle-runner/hand-in-verdicts :cycle-runner/run-progress :cycle-runner/runs-to-advance})
(defn response [request]
  (let [id (:id request) method (when (string? (:method request)) (keyword (:method request))) params (:params request {})
        schema (or (get-in model/model [:commands method :schema]) (get-in model/model [:queries method :schema]))]
    (try
      (cond
        (not (methods method)) {:jsonrpc "2.0" :id id :error {:code -32601 :message "Unknown method" :data {}}}
        (not (m/validate schema params)) {:jsonrpc "2.0" :id id :error {:code -32602 :message "Invalid params" :data {}}}
        :else {:jsonrpc "2.0" :id id :result (service/invoke! method params)})
      (catch Throwable t
        (binding [*out* *err*] (println (.getMessage t)))
        {:jsonrpc "2.0" :id id :error {:code -32000 :message (.getMessage t) :data (or (:refusal (ex-data t)) {:detail (str (ex-data t))})}}))))
(defn -main [& _]
  (service/register-handlers!)
  (service/install-processor!)
  (let [ctx (engine/start (or (System/getenv "LOYLY_GRAIN_STORE") "state/events.sqlite"))]
    (reset! service/runtime ctx)
    (try
      (doseq [line (line-seq (java.io.BufferedReader. *in*))]
        (let [request (try (u/decode line) (catch Throwable _ ::parse-error))
              reply (if (= ::parse-error request) {:jsonrpc "2.0" :id nil :error {:code -32700 :message "Parse error" :data {}}} (response request))]
          (println (u/encode reply)) (flush)))
      (finally
        (doseq [[_ s] @service/sessions] (runner.contexts/stop s))
        (engine/stop ctx)
        (shutdown-agents)))))
