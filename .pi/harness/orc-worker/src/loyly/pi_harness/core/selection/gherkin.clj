(ns loyly.pi-harness.core.selection.gherkin
  "Stable identity helpers shared by selection review and oracle resolution."
  (:require [clojure.string :as str]))

(defn scenario-digest [item-id rule scenario-lines]
  (let [normalized (->> scenario-lines
                        (map str/trim)
                        (remove str/blank?)
                        (remove #(str/starts-with? % "#"))
                        vec)
        digest (java.security.MessageDigest/getInstance "SHA-256")
        value (pr-str [item-id (str/trim (or rule "")) normalized])]
    (format "%064x"
            (BigInteger. 1 (.digest digest (.getBytes value "UTF-8"))))))
