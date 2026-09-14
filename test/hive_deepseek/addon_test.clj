(ns hive-deepseek.addon-test
  "The IAddon boundary with stub dependencies: a translator-contributing
   addon and a presenter-seat addon, both records implementing the IAddon
   port -- no concrete host."
  (:require [clojure.test :refer [deftest is testing]]
            [hive-addon.protocol :as addon]
            [hive-addon.vessel :as iv]
            [hive-deepseek.addon :as sut]
            [hive-vessel.core :as v]
            [hive-spi.vessel :as render-port]
            [hive-vessel.renderer :as renderer]))

;; SPDX-License-Identifier: MIT

(defrecord StubAddon [id hook-map]
  addon/IAddon
  (addon-id [_] id)
  (addon-type [_] :native)
  (capabilities [_] #{})
  (initialize! [_ _] {:success? true})
  (shutdown! [_] nil)
  (tools [_] [])
  (schema-extensions [_] [])
  (health [_] {:status :ok})
  (excluded-tools [_] #{})
  (hooks [_] hook-map))

(def frame-translator
  {:translator/id :stub/frame
   :translator/op :stub/frame
   :translator/translate (fn [{:keys [payload]} _]
                           {:op :ui/show-panel :panel/id "stub"
                            :doc {:doc/title (str "frame " (:n payload)) :doc/blocks []}})})

(defn- presenter-seat []
  (let [presenters (atom {})]
    [presenters
     (->StubAddon "stub.presenter"
                  {:stub/register-presenter! (fn [id deliver!] (swap! presenters assoc id deliver!) deliver!)
                   :stub/unregister-presenter! (fn [id] (swap! presenters dissoc id) id)})]))

(defn- started [deps]
  (let [a (sut/addon-ctor {:deepseek/port 0})
        r (addon/initialize! a {:mount/dependencies deps})]
    [a r]))

(deftest lifecycle-is-idempotent-and-reported
  (let [[a r] (started {})]
    (try
      (is (:success? r))
        (is (satisfies? render-port/IRenderer a))
        (is (identical? a (get @renderer/renderers "hive.deepseek")))
        (is (:error (render-port/render! a [{:op :ui/send-to-terminal :text "forbidden"}])))
      (is (pos? (get-in r [:metadata :port])))
      (is (:already-initialized? (addon/initialize! a {})))
      (is (= :ok (:status (addon/health a))))
      (is (= 0 (get-in (addon/health a) [:details :clients])))
      (finally (addon/shutdown! a)))
    (is (= :stopped (get-in (addon/health a) [:details :lifecycle])))
    (is (= {} (addon/hooks a)))
        (is (not (contains? @renderer/renderers "hive.deepseek")))
    (is (nil? (addon/shutdown! a)))))

(deftest a-port-in-use-fails-loudly
  (let [[a r] (started {})
        port (get-in r [:metadata :port])
        b (sut/addon-ctor {:deepseek/port port})]
    (try
      (let [r2 (addon/initialize! b {})]
        (is (false? (:success? r2)))
        (is (= :down (:status (addon/health b)))))
      (finally (addon/shutdown! a)))))

(deftest dependency-translators-join-the-registry
  (let [[a _] (started {"stub.translators" (->StubAddon "stub.translators" {v/hook-key [frame-translator]})})]
    (try
      (let [dispatch! (:vessel/dispatch! (addon/hooks a))
            r (dispatch! {:op :stub/frame :payload {:n 7}})]
        (is (:ok r))
        (is (= "frame 7" (get-in r [:ok :plan/ops 0 :native/payload "doc" "doc/title"])))
        (is (= #{"stub"} (get-in (addon/health a) [:details :panels]))))
      (finally (addon/shutdown! a)))))

(deftest runtime-translators-can-be-added
  (let [[a _] (started {})]
    (try
      (let [{:vessel/keys [dispatch! register-translators!]} (addon/hooks a)]
        (is (= :unsupported (get-in (dispatch! {:op :stub/frame}) [:error :failure/reason])))
        (register-translators! [frame-translator])
        (is (:ok (dispatch! {:op :stub/frame :payload {:n 1}}))))
      (finally (addon/shutdown! a)))))

(deftest presenter-seats-receive-this-vessels-delivery
  (let [[presenters seat] (presenter-seat)
        [a r] (started {"stub.presenter" seat
                        "stub.translators" (->StubAddon "stub.translators" {v/hook-key [frame-translator]})})]
    (try
      (is (= 1 (get-in r [:metadata :presenters])))
      (testing "the registered delivery takes presenter envelopes"
        (let [deliver! (get @presenters :deepseek)
              result (deliver! {:type :stub/frame :event-name "stub-frame" :payload {:n 3}})]
          (is (:ok result))
          (is (= #{"stub"} (get-in (addon/health a) [:details :panels])))))
      (finally (addon/shutdown! a)))
    (is (empty? @presenters) "shutdown unregisters the presenter")))

(deftest the-vessel-faces-agree
  (let [[a _] (started {})]
    (try
      (let [hooks (addon/hooks a)
            target ((:vessel/target hooks))
            instance ((:vessel/instance hooks))
            descriptor ((:deepseek/vessel hooks))]
        (is (= {:vessel/id :deepseek :vessel/dialect :json}
               (select-keys target [:vessel/id :vessel/dialect])))
        (is (iv/vessel? instance))
        (is (= :deepseek (iv/vessel-id instance)))
        (is (nil? (iv/addon instance :terminal)))
        (is (:ok ((:delivery/deliver! (iv/addon instance :delivery)) {:op :ui/notify :message "x"})))
        (is (:ok ((:delivery/deliver! ((:vessel/addon descriptor) :delivery)) {:op :ui/notify :message "y"}))))
      (finally (addon/shutdown! a)))))
