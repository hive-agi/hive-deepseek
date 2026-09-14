(ns hive-deepseek.providers
  "hive's LLM providers as DeepSeek Harness model routes.

   hive-mcp's config already names every OpenAI-compatible provider the swarm
   uses (`:llm-providers`, each with `:api-url`, `:secret-key`,
   `:default-model`, `:available-models`) and where its key lives
   (`:secrets`, e.g. \"pass:Venice/key\"). dsh's `llm-pi-ai` plugin accepts
   hand-declared routes of the same shape. This namespace is the projection
   from one to the other, so a dsh session runs on the same providers and
   models as the swarm without restating them.

   Pure. It never reads a key: a route names the environment variable dsh
   resolves per request, and `credential-refs` says where a launcher finds
   each value. A secret that is not a `pass:` reference is never copied
   anywhere; its route is reported as unresolved instead.

   SPDX-License-Identifier: MIT"
  (:require [clojure.string :as str]))

(def chat-suffix
  "The path hive's provider URLs end in; dsh wants the API root."
  "/chat/completions")

(def pass-prefix "pass:")

(defn base-url
  "API root of API-URL: the URL without a trailing /chat/completions."
  [api-url]
  (when (and (string? api-url) (not (str/blank? api-url)))
    (let [u (str/replace api-url #"/+$" "")]
      (if (str/ends-with? u chat-suffix)
        (subs u 0 (- (count u) (count chat-suffix)))
        u))))

(defn env-var
  "Environment variable dsh reads the key of PROVIDER from, e.g. VENICE_API_KEY."
  [provider]
  (-> (name provider) (str/replace #"[^A-Za-z0-9]+" "_") str/upper-case (str "_API_KEY")))

(defn display-name
  "Human label of PROVIDER, e.g. :ollama-compat -> \"Ollama Compat\"."
  [provider]
  (->> (str/split (name provider) #"[-_]+")
       (remove str/blank?)
       (map str/capitalize)
       (str/join " ")))

(defn route
  "The dsh route for hive provider PROVIDER with SPEC, or nil when SPEC has no
   OpenAI-compatible URL (an OAuth dispatch such as :anthropic) or no model."
  [provider {:keys [api-url secret-key default-model available-models]}]
  (let [url (base-url api-url)
        models (->> (cons default-model available-models)
                    (filter #(and (string? %) (not (str/blank? %))))
                    distinct
                    (mapv (fn [m] {:id m})))]
    (when (and url (seq models))
      (cond-> {:provider (name provider)
               :displayName (display-name provider)
               :api "openai-completions"
               :baseURL url
               :models models}
        secret-key (assoc :apiKeyEnv (env-var provider)
                          :secret-key secret-key)))))

(defn routes
  "Routes for every usable provider in LLM-PROVIDERS, ordered by name. ONLY,
   when non-empty, keeps just those provider names."
  ([llm-providers] (routes llm-providers nil))
  ([llm-providers only]
   (let [only (into #{} (map name) only)]
     (->> llm-providers
          (keep (fn [[k spec]] (route k spec)))
          (filter #(or (empty? only) (contains? only (:provider %))))
          (sort-by :provider)
          vec))))

(defn credential-refs
  "Where each keyed route's value comes from. Returns
     {:refs       {\"VENICE_API_KEY\" \"Venice/key\"}   pass entries to read
      :unresolved [{:provider \"groq\" :reason :no-secret}]}
   A secret that is absent, or present but not a pass reference, is
   :unresolved (:no-secret / :not-a-pass-ref); its value is never returned."
  [routes secrets]
  (reduce
   (fn [acc {:keys [provider apiKeyEnv secret-key]}]
     (if-not apiKeyEnv
       acc
       (let [v (get secrets secret-key)]
         (cond
           (and (string? v) (str/starts-with? v pass-prefix)
                (not (str/blank? (subs v (count pass-prefix)))))
           (assoc-in acc [:refs apiKeyEnv] (subs v (count pass-prefix)))

           (nil? v)
           (update acc :unresolved conj {:provider provider :reason :no-secret})

           :else
           (update acc :unresolved conj {:provider provider :reason :not-a-pass-ref})))))
   {:refs {} :unresolved []}
   routes))

(defn usable
  "ROUTES a launcher can serve once it holds the values for ENV-NAMES (a set of
   environment variable names): keyless routes, and keyed ones whose variable
   is in ENV-NAMES."
  [routes env-names]
  (filterv #(or (nil? (:apiKeyEnv %)) (contains? env-names (:apiKeyEnv %))) routes))

(defn- dsh-route [r]
  (-> (select-keys r [:displayName :api :baseURL :apiKeyEnv :models])
      (update :models (fn [ms] (mapv #(assoc % :name (str (:id %) " (" (:displayName r) ")")) ms)))))

(defn default-model
  "[provider model] a DEFAULT string \"provider/model\" names among ROUTES, or
   nil. The model part may itself contain slashes (openrouter ids do)."
  [routes default]
  (when (and (string? default) (str/includes? default "/"))
    (let [[p m] (str/split default #"/" 2)]
      (when (some (fn [r] (and (= p (:provider r)) (some #(= m (:id %)) (:models r)))) routes)
        [p m]))))

(defn patch
  "dsh patch entries for ROUTES. Options:
     :default     \"provider/model\" to make the agent default, when it is a route
     :bridge-url  hive.deepseek bridge URL for the installed hive-vessel plugin"
  ([routes] (patch routes nil))
  ([routes {:keys [default bridge-url]}]
   (cond-> [{:id "llm-pi-ai"
             :config {:providers (into (sorted-map)
                                       (map (juxt :provider dsh-route))
                                       routes)}}]
     (default-model routes default)
     (conj (let [[p m] (default-model routes default)]
             {:id "agent-default-model" :config {:provider p :model m}}))

     bridge-url
     (conj {:id "hive-vessel" :config {:url bridge-url}}))))

(defn- esc [s]
  (str "\""
       (-> s
           (str/replace "\\" "\\\\")
           (str/replace "\"" "\\\"")
           (str/replace "\n" "\\n")
           (str/replace "\r" "\\r")
           (str/replace "\t" "\\t"))
       "\""))

(defn ->yaml
  "DATA as YAML text. JSON-style flow collections, which every YAML 1.2
   reader accepts, one top-level entry per line for a readable patch file."
  [data]
  (letfn [(emit [v]
            (cond
              (nil? v) "null"
              (boolean? v) (str v)
              (number? v) (str v)
              (keyword? v) (esc (name v))
              (string? v) (esc v)
              (symbol? v) (esc (str v))
              (map? v) (str "{" (str/join ", " (map (fn [[k x]] (str (emit k) ": " (emit x))) v)) "}")
              (sequential? v) (str "[" (str/join ", " (map emit v)) "]")
              :else (esc (str v))))]
    (if (sequential? data)
      (str/join "\n" (map #(str "- " (emit %)) data))
      (emit data))))
