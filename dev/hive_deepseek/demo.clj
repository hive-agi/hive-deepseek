(ns hive-deepseek.demo
  "Run hive.deepseek standalone and push a scripted sequence to dsh. Used by
   e2e/run.sh and for trying the vessel by hand:

     clojure -Sdeps \"$(cat local.deps.edn)\" -M:dev -m hive-deepseek.demo \\
             PORT HOLD-SECONDS FILE [READY-OP]

   Waits for a dsh tab to connect -- and, when READY-OP is given, for the page
   to POST {\"op\": READY-OP} to the bridge -- then pushes: an operation panel,
   a failed operation (panel + error notice), a json/event, a second panel,
   and a ui/open-file for FILE. Prints one EDN line per step."
  (:require [clojure.string :as str]
            [hive-addon.protocol :as addon]
            [hive-deepseek.addon :as deepseek]))

;; SPDX-License-Identifier: MIT

(def demo-translators
  "An addon-style intent, lowered to standard primitives like any addon's."
  [{:translator/id :demo/operation->panel
    :translator/op :demo/operation
    :translator/translate
    (fn [{:keys [n phase command file diff]} _target]
      (cond-> [{:op :ui/show-panel
                :panel/id "demo"
                :doc {:doc/title (str "Operation #" n)
                      :doc/blocks [{:block/type :para
                                    :tone (if (= :failed phase) :error :success)
                                    :text (str (name phase) "  " command)}
                                   {:block/type :fields
                                    :fields [["phase" (name phase)] ["command" command]]}
                                   {:block/type :link :text file :file file :line 2}
                                   {:block/type :diff :text diff}]}}]
        (= :failed phase)
        (conj {:op :ui/notify :level :error :message (str command " failed on " file)})))}])

(defn- say [x] (println (pr-str x)) (flush))

(defn- await-until [pred timeout-ms]
  (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
    (loop []
      (cond
        (pred) true
        (> (System/currentTimeMillis) deadline) false
        :else (do (Thread/sleep 200) (recur))))))

(defn- summary [r]
  (if-let [p (:ok r)]
    {:ok (mapv #(get-in % [:native/payload "op"]) (:plan/ops p)) :results (:plan/results p)}
    r))

(defn -main [& [port hold-s file ready-op]]
  (let [a (deepseek/addon-ctor {:deepseek/port (parse-long (or port "7925"))})
        started (addon/initialize! a {})
        hooks (addon/hooks a)
        dispatch! (:vessel/dispatch! hooks)
        bridge-info (:deepseek/bridge hooks)
        file (or file "/tmp/hive-deepseek-demo.txt")]
    (say {:step :started :result started})
    ((:vessel/register-translators! hooks) demo-translators)
    (if-not (and (await-until #(pos? (:clients (bridge-info))) 180000)
                 (or (nil? ready-op)
                     (await-until #(some (fn [m] (str/includes? m (str "\"" ready-op "\"")))
                                         (:inbox (bridge-info)))
                                  180000)))
      (say {:step :not-ready :bridge (bridge-info)})
      (do
        (say {:step :ready :clients (:clients (bridge-info))})
        (say {:step :panel
              :result (summary (dispatch! {:op :demo/operation :n 1 :phase :succeeded
                                           :command "write-form" :file file
                                           :diff "@@ -1 +1 @@\n-(defn f [] 1)\n+(defn f [] 2)"}))})
        (Thread/sleep 1000)
        (say {:step :failure
              :result (summary (dispatch! {:op :demo/operation :n 2 :phase :failed
                                           :command "rename-symbol" :file file
                                           :diff "@@ -3 +3 @@\n-(old-name)\n+(new-name)"}))})
        (Thread/sleep 500)
        (say {:step :event
              :result (summary (dispatch! {:op :json/event :event "demo/tick" :data {:n 3}}))})
        (Thread/sleep 500)
        (say {:step :second-panel
              :result (summary (dispatch! {:op :ui/show-panel :panel/id "notes"
                                           :doc {:doc/title "Notes"
                                                 :doc/blocks [{:block/type :list :items ["from hive" "to dsh"]}]}}))})
        (Thread/sleep 1500)
        (say {:step :open-file
              :result (summary (dispatch! {:op :ui/open-file :file file :line 2}))})))
    (Thread/sleep (* 1000 (parse-long (or hold-s "30"))))
    (say {:step :health :result (addon/health a) :inbox (:inbox (bridge-info))})
    (addon/shutdown! a)
    (shutdown-agents)))
