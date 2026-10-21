(ns hive-deepseek.addon
  "hive.deepseek IAddon: runs the SSE bridge (hive-vessel.executor.sse), owns
   the vessel registry, and
   presents on behalf of the dependencies it is told to.

   Config (manifest :addon/config merged with runtime config):
     :deepseek/port          bridge port (default 7925, 0 = any)
     :deepseek/host          bind host (default loopback)
     :deepseek/token         shared secret the dsh plugin must send
     :deepseek/presenter-id  id registered on presenter seats (default :deepseek)
     :deepseek/present-on    addon ids whose presenter seat this vessel takes
                             (default #{}: none)

   Registry: hive-vessel's standard translators, then the `:vessel/translators`
   hook of every injected dependency, then translators added at runtime
   through the `:vessel/register-translators!` hook.

   Presenter seats: a dependency named in :deepseek/present-on whose hooks
   include a key named `register-presenter!` (hive.carto-flow's is one) gets
   this vessel's delivery registered under the presenter id, so its frames
   reach dsh with no code on either side naming the other. Opt-in, because a
   brick (hive-carto-flow-deepseek) registers under :deepseek itself, and two
   registrars would replace each other on every mount."
  (:require [hive-addon.protocol :as addon]
            [hive-deepseek.vessel :as vessel]
            [hive-vessel.core :as v]
            [hive-vessel.executor.sse :as bridge]
            [hive-spi.vessel :as render-port]
            [hive-vessel.renderer :as renderer]))

;; SPDX-License-Identifier: MIT

(def addon-id-value "hive.deepseek")

(defn- dependency-hooks [config]
  (keep (fn [[_ dep]]
          (when (addon/addon? dep)
            (try (addon/hooks dep) (catch Throwable _ nil))))
        (:mount/dependencies config)))

(defn- hook-named [hooks hook-name]
  (some (fn [[k f]] (when (and (= hook-name (name k)) (fn? f)) f)) hooks))

(defn- opted-in-hooks
  "Hooks of the dependencies `:deepseek/present-on` (a set of addon ids,
   default empty) names. Only these are offered this vessel's delivery: a
   brick such as hive-carto-flow-deepseek registers under :deepseek itself,
   and a second registrar swept in by hook name would replace it on mount.
   A dependency whose id or hooks cannot be read is not a seat."
  [config]
  (let [present-on (set (:deepseek/present-on config))]
    (keep (fn [[_ dep]]
            (when (and (addon/addon? dep)
                       (contains? present-on (try (addon/addon-id dep)
                                                  (catch Throwable _unreadable-id nil))))
              (try (addon/hooks dep) (catch Throwable _unreadable-hooks nil))))
          (:mount/dependencies config))))

(defn- register-presenters!
  "Register DELIVER! on the presenter seat of every opted-in dependency.
   Returns the unregister fns for the seats that accepted."
  [config presenter-id deliver!]
  (vec (keep (fn [hooks]
               (when-let [register (hook-named hooks "register-presenter!")]
                 (when (register presenter-id deliver!)
                   (hook-named hooks "unregister-presenter!"))))
             (opted-in-hooks config))))

(defn- start! [state seed runtime-config]
  (locking state
    (if (= :active (:lifecycle @state))
      {:success? true :already-initialized? true}
      (let [config (merge seed runtime-config)
            presenter-id (or (:deepseek/presenter-id config) vessel/vessel-id)]
        (try
          (let [registry (atom (v/registry-from-hooks (dependency-hooks config)))
                b (bridge/start! {:port (or (:deepseek/port config) vessel/default-port)
                                  :host (:deepseek/host config)
                                  :token (:deepseek/token config)})
                target (vessel/target b)
                deliver! (vessel/delivery registry target)
                unregisters (register-presenters! config presenter-id deliver!)]
            (reset! state {:lifecycle :active
                           :bridge b
                           :registry registry
                           :target target
                           :presenter-id presenter-id
                           :unregisters unregisters})
            {:success? true
             :metadata {:port (:port b)
                        :presenters (count unregisters)
                        :translators (count (:registry/translators @registry))}})
          (catch Throwable t
            (reset! state {:lifecycle :failed :last-error (ex-message t)})
            {:success? false :errors [(ex-message t)]}))))))

(defn- stop! [state]
  (locking state
    (let [{:keys [lifecycle bridge presenter-id unregisters]} @state]
      (when (= :active lifecycle)
        (doseq [unregister unregisters]
          (try (unregister presenter-id) (catch Throwable _ nil)))
        (bridge/stop! bridge))
      (reset! state {:lifecycle :stopped})
      nil)))

(defrecord DeepseekAddon [state seed]
  render-port/IRenderer
  (renderer-id [_] addon-id-value)
  (render! [_ ops] (renderer/deliver! (:target @state) ops))
  addon/IAddon
  (addon-id [_] addon-id-value)
  (addon-type [_] :native)
  (capabilities [_] #{:vessel :health-reporting})
  (initialize! [this runtime-config]
    (let [result (start! state seed runtime-config)]
      (when (:success? result) (renderer/register! this))
      result))
  (shutdown! [this]
    (renderer/unregister! this)
    (stop! state))
  (tools [_] [])
  (schema-extensions [_] [])
  (health [_]
    (let [{:keys [lifecycle bridge registry presenter-id unregisters last-error]} @state]
      {:status (case lifecycle
                 :active :ok
                 :failed :down
                 :degraded)
       :details (cond-> {:lifecycle lifecycle}
                  bridge (assoc :port (:port bridge)
                                :clients (bridge/clients bridge)
                                :panels (bridge/retained-panels bridge)
                                :presenter-id presenter-id
                                :presenters (count unregisters)
                                :translators (count (:registry/translators @registry)))
                  last-error (assoc :last-error last-error))}))
  (excluded-tools [_] #{})
  (hooks [_]
    (let [{:keys [lifecycle bridge registry target]} @state]
      (if (= :active lifecycle)
        {:deepseek/vessel (fn [] (vessel/descriptor registry target))
         :vessel/target (fn [] target)
         :vessel/instance (fn [] (vessel/->vessel registry target))
         :vessel/dispatch! (fn [op-or-ops] (v/dispatch! registry target op-or-ops))
         :vessel/register-translators! (fn [translators] (swap! registry v/register-all translators) nil)
         :deepseek/bridge (fn [] {:port (:port bridge)
                                  :clients (bridge/clients bridge)
                                  :panels (bridge/retained-panels bridge)
                                  :inbox (bridge/inbox bridge)})}
        {}))))

(defn addon-ctor
  "Pure constructor resolved from the mount manifest."
  [config]
  (->DeepseekAddon (atom {:lifecycle :created}) (or config {})))
