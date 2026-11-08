(ns measurement.schemas
  "Registers the model's command, event and query schemas in Grain's schema
   registry, under the block's name, exactly as the model gives them."
  (:require [ai.obney.grain.schema-util.interface :as schema-util]
            [measurement.model :as model]))

(defn model-schemas []
  (into {}
        (for [[_ area-map] model/model
              kind [:commands :events :queries]
              [block-name block] (get area-map kind)]
          [block-name (:schema block)])))

(schema-util/register! (model-schemas))
