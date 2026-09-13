(ns hive-deepseek.vessel
  "DeepSeek Harness as a hive vessel.

   The vessel speaks hive-vessel's :json dialect; its executor is hive-vessel's
   SSE bridge, consumed by the dsh browser plugin (dsh/ in this repo), which
   paints panels in the right Sidebar, raises notices, opens workspace files
   and re-emits `json/event` messages as Cordis events.

   Two faces over the same target:
   - `target`     -- a hive-vessel target, for plan / dispatch! / broadcast!
   - `->vessel`   -- a hive-addon IVessel, for a host's vessel registry; its
                     :delivery capability accepts ops or presentation
                     envelopes and dispatches them through the registry."
  (:require [hive-addon.vessel :as iv]
            [hive-vessel.core :as v]
            [hive-vessel.executor.sse :as sse]))

;; SPDX-License-Identifier: MIT

(def vessel-id :deepseek)

(def dialect :json)

(def default-port
  "The bridge port the dsh-hive-vessel plugin looks for by default."
  7925)

(def features
  "What the dsh plugin can do beyond the standard :json primitives."
  #{:dsh/sidebar-panels :dsh/notices :dsh/open-resource :dsh/events})

(defn target
  [bridge]
  {:vessel/id vessel-id
   :vessel/dialect dialect
   :vessel/features features
   :vessel/execute! (sse/executor bridge)})

(defn delivery
  "The :delivery capability: a fn taking an op or a presentation envelope and
   dispatching it through REGISTRY (a value or an atom) to TARGET."
  [registry target]
  (v/sink registry target v/envelope->op))

(defrecord DeepseekVessel [registry target]
  iv/IVessel
  (vessel-id [_] vessel-id)
  (capabilities [_] #{:delivery})
  (resolve-context [_ _agent-id] nil)
  (addon [_ capability]
    (when (= :delivery capability)
      {:delivery/deliver! (delivery registry target)}))
  (initialize! [_ _config] nil)
  (shutdown! [_] nil))

(defn ->vessel [registry target] (->DeepseekVessel registry target))

(defn descriptor
  "Host-neutral vessel descriptor (the hive-emacs shape), so consumers that
   read descriptors -- hive-carto-flow's presentation target -- need no
   protocol."
  [registry target]
  (assoc target
         :vessel/capabilities #{:delivery}
         :vessel/addon (fn [capability]
                         (when (= :delivery capability)
                           {:delivery/deliver! (delivery registry target)}))))
