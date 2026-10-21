(ns hive-deepseek.providers-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clojure.test.check.generators :as gen]
            [hive-deepseek.providers :as p]
            [hive-test.trifecta :refer [deftrifecta]]))

(def llm-providers
  {:venice {:api-url "https://api.venice.ai/api/v1/chat/completions"
            :secret-key :venice-api-key
            :default-model "deepseek-v4-flash"
            :available-models ["deepseek-v4-flash" "glm-5-3-flash"]}
   :openrouter {:api-url "https://openrouter.ai/api/v1/chat/completions"
                :secret-key :openrouter-api-key
                :default-model "moonshotai/kimi-k2.6"}
   :groq {:api-url "https://api.groq.com/openai/v1/chat/completions"
          :secret-key :groq-api-key
          :default-model "llama-3.3-70b-versatile"}
   :ollama-compat {:api-url "http://localhost:11434/v1/chat/completions/"
                   :secret-key nil
                   :default-model "devstral-small:24b"}
   :anthropic {:dispatch :anthropic-oauth :secret-key :anthropic-api-key :default-model "claude-sonnet-4-6"}
   :empty {:api-url "https://x.test/v1/chat/completions"}})

(def secrets
  {:venice-api-key "pass:Venice/key"
   :openrouter-api-key "pass:openrouter/keys/hive-mcp"
   :groq-api-key "gsk-not-a-reference"})

(deftest routes-project-every-openai-compatible-provider
  (let [rs (p/routes llm-providers)]
    (is (= ["groq" "ollama-compat" "openrouter" "venice"] (mapv :provider rs))
        "an OAuth dispatch and a provider without models are not routes")
    (is (= {:provider "venice" :displayName "Venice" :api "openai-completions"
            :baseURL "https://api.venice.ai/api/v1" :apiKeyEnv "VENICE_API_KEY"
            :secret-key :venice-api-key
            :models [{:id "deepseek-v4-flash"} {:id "glm-5-3-flash"}]}
           (last rs))
        "the default model leads and is not repeated")
    (testing "a keyless provider has no key variable; a trailing slash is dropped"
      (is (= {:provider "ollama-compat" :displayName "Ollama Compat" :api "openai-completions"
              :baseURL "http://localhost:11434/v1" :models [{:id "devstral-small:24b"}]}
             (second rs))))
    (testing "ONLY narrows by provider name"
      (is (= ["venice"] (mapv :provider (p/routes llm-providers [:venice "nope"])))))))

(deftest credentials-are-references-never-values
  (let [{:keys [refs unresolved]} (p/credential-refs (p/routes llm-providers) secrets)]
    (is (= {"VENICE_API_KEY" "Venice/key" "OPENROUTER_API_KEY" "openrouter/keys/hive-mcp"} refs))
    (is (= [{:provider "groq" :reason :not-a-pass-ref}] unresolved))
    (is (not (str/includes? (pr-str [refs unresolved]) "gsk-not-a-reference")))
    (testing "an absent secret is unresolved, not silently dropped"
      (is (= [{:provider "venice" :reason :no-secret}]
             (:unresolved (p/credential-refs (p/routes llm-providers [:venice]) {})))))
    (testing "only routes whose key the launcher holds are usable, keyless ones always"
      (is (= ["ollama-compat" "venice"]
             (mapv :provider (p/usable (p/routes llm-providers) #{"VENICE_API_KEY"})))))))

(deftest patch-is-the-llm-pi-ai-row-plus-the-chosen-default
  (let [rs (p/routes llm-providers [:venice :openrouter])
        [llm default & more] (p/patch rs {:default "openrouter/moonshotai/kimi-k2.6"
                                          :bridge-url "http://127.0.0.1:7925"})]
    (is (= "llm-pi-ai" (:id llm)))
    (is (= ["openrouter" "venice"] (keys (get-in llm [:config :providers]))))
    (is (= {:displayName "Venice" :api "openai-completions" :baseURL "https://api.venice.ai/api/v1"
            :apiKeyEnv "VENICE_API_KEY"
            :models [{:id "deepseek-v4-flash" :name "deepseek-v4-flash (Venice)"}
                     {:id "glm-5-3-flash" :name "glm-5-3-flash (Venice)"}]}
           (get-in llm [:config :providers "venice"]))
        "no hive-internal key reaches dsh")
    (is (= {:id "agent-default-model" :config {:provider "openrouter" :model "moonshotai/kimi-k2.6"}} default)
        "a model id with slashes survives")
    (is (= [{:id "hive-vessel" :config {:url "http://127.0.0.1:7925"}}] more))
    (testing "a default that names no route is left out"
      (is (= ["llm-pi-ai"] (mapv :id (p/patch rs {:default "venice/nope"}))))
      (is (= ["llm-pi-ai"] (mapv :id (p/patch rs {:default "venice"})))))))

(deftest yaml-is-flow-style-and-escaped
  (is (= "- {\"id\": \"a\", \"n\": 1, \"ok\": true, \"xs\": [\"q\\\"uote\", null]}\n- {\"id\": \"b\"}"
         (p/->yaml [{:id "a" :n 1 :ok true :xs ["q\"uote" nil]} {:id :b}])))
  (is (= "{\"k\": \"line\\nbreak\"}" (p/->yaml {:k "line\nbreak"}))))

(deftest route-verdict-reads-a-probe-status
  (testing "a rejected key and an empty balance are told apart"
    (is (= :skip-key (p/route-verdict 401)))
    (is (= :skip-key (p/route-verdict 403)))
    (is (= :skip-balance (p/route-verdict 402))))
  (testing "anything else keeps the route: a 200, a 5xx, a network error"
    (is (= :keep (p/route-verdict 200)))
    (is (= :keep (p/route-verdict 429)))
    (is (= :keep (p/route-verdict 503)))
    (is (= :keep (p/route-verdict "error connection refused")))
    (is (= :keep (p/route-verdict nil)))))

(deftrifecta route-verdict
  hive-deepseek.providers/route-verdict
  {:golden-path   "test/golden/providers/route-verdict.edn"
   :cases         {:ok 200 :unauthorized 401 :forbidden 403 :no-credit 402
                   :rate-limited 429 :down 503 :network "error timeout"}
   :gen           (gen/one-of [(gen/choose 100 599) gen/string-alphanumeric])
   :pred          #{:keep :skip-key :skip-balance}
   :property-type :pred
   :num-tests     200
   :mutations     [["402-kept" (fn [s] (if (contains? #{401 403} s) :skip-key :keep))]
                   ["all-skipped" (fn [_] :skip-key)]]})

(deftest balance-probe-is-a-one-token-completion-on-the-first-model
  (let [venice (last (p/routes llm-providers))]
    (is (= {:url "https://api.venice.ai/api/v1/chat/completions"
            :body {:model "deepseek-v4-flash"
                   :messages [{:role "user" :content "."}]
                   :max_tokens 1}}
           (p/balance-probe-request venice)))))

(deftest screen-drops-what-the-probes-refuse
  (let [rs (p/routes llm-providers [:venice :openrouter :groq])
        statuses {"venice" 402 "openrouter" 200 "groq" 401}
        {:keys [served skipped]} (p/screen rs #(get statuses (:provider %)))]
    (is (= ["openrouter"] (mapv :provider served)))
    (is (= [{:provider "groq" :verdict :skip-key :status 401}
            {:provider "venice" :verdict :skip-balance :status 402}]
           skipped))
    (testing "a keyless route is never probed and always served"
      (is (= ["ollama-compat"]
             (mapv :provider (:served (p/screen (p/routes llm-providers [:ollama-compat])
                                                (fn [_] (throw (ex-info "probed" {})))))))))))
