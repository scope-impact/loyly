(ns runner.store-reader
  (:require [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.event-store-sqlite-v3.interface]
            [taoensso.nippy :as nippy]))
(defn -main [path output]
  (let [store (es/start {:conn {:type :sqlite :database-file path}})]
    (try
      (nippy/freeze-to-file output (into [] (es/read store {:tenant-id #uuid "00000000-0000-0000-0000-000000000000"})))
      (finally (es/stop store) (shutdown-agents)))))
