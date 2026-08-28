(ns otel.viewer-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [otel.viewer :as viewer]))

(def trace-id "0123456789abcdef0123456789abcdef")

(def index-data
  {:summary {:traceCount 1 :spanCount 2 :logCount 1 :errorCount 0}
   :traces [{:traceId trace-id :rootSpan "GET /work" :service "app"
             :startedAt "now" :spanCount 2 :status "ok"}]
   :logs [{:timestamp "now" :severity "INFO" :body "complete"}]})

(def trace-data
  {:traceId trace-id
   :spanTree [{:spanId "root" :parentSpanId "" :name "GET /work"
               :timestampUnixNano 1000 :durationNs 2000000
               :attributes {"http.request.method" "GET"}
               :children [{:spanId "child" :parentSpanId "root"
                           :name "fetch" :timestampUnixNano 2000
                           :durationNs 500000 :status "error"}]}]
   :logs [{:timestamp "now" :severity "ERROR" :body "failed"}]})

(def trace-filters
  {:selected {:service "api" :operation "GET /users" :status "error"
              :min-duration-ms "12.5" :window "1h"}
   :service-options [{:value "api" :label "API"}
                     {:value "worker" :label "Worker"}]
   :status-options [{:value "ok" :label "OK"}
                    {:value "error" :label "Error"}]
   :window-options [{:value "15m" :label "Last 15 minutes"}
                    {:value "1h" :label "Last hour"}]})

(deftest mount-prefixes
  (is (= "" (viewer/normalize-base-path nil)))
  (is (= "/ops/otel" (viewer/normalize-base-path "ops/otel///")))
  (is (= "/" (viewer/mounted-path nil "")))
  (is (= "/ops/otel/traces/id"
         (viewer/mounted-path "/ops/otel/" "/traces/id")))
  (let [html (viewer/render-fragment
               (merge index-data
                      {:base-path "ops/otel/"
                       :work-path "work"
                       :enhancement-path "assets/viewer.js"}))]
    (is (str/includes? html "href=\"/ops/otel\""))
    (is (str/includes? html "action=\"/ops/otel/work\""))
    (is (str/includes? html
                       (str "href=\"/ops/otel/traces/" trace-id "\"")))))

(deftest multiple-post-actions-preserve-work-path-compatibility
  (let [html (viewer/render-fragment
              (merge index-data
                     {:base-path "/ops/otel"
                      :work-path "work"
                      :work-label "Generate work"
                      :post-actions [{:path "agent/without-response"
                                      :label "Run <without> response"}
                                     {:path "/agent/with-response"
                                      :label "Run with response"}]}))]
    (is (str/includes? html
                       "action=\"/ops/otel/work\" method=\"post\""))
    (is (str/includes? html
                       "action=\"/ops/otel/agent/without-response\" method=\"post\""))
    (is (str/includes? html "Run &lt;without&gt; response"))
    (is (str/includes? html
                       "action=\"/ops/otel/agent/with-response\" method=\"post\""))
    (is (= 3 (count (re-seq #"method=\"post\"" html))))
    (is (not (str/includes? html "<script"))
        "multiple actions retain the zero-JavaScript baseline"))
  (let [actions (mapv (fn [i] {:path (str "run/" i)
                                :label (apply str (repeat 100 "x"))})
                      (range 12))
        model (viewer/index-model (assoc index-data :post-actions actions))]
    (is (= 8 (count (:postActions model))))
    (is (= 80 (count (get-in model [:postActions 0 :label]))))))

(deftest bounded-rendering-and-escaping
  (testing "invalid trace ids are omitted and host data is escaped"
    (let [html (viewer/render-fragment
                 (-> index-data
                     (update :traces conj {:traceId "../bad"})
                     (assoc :logs [{:timestamp "now" :severity "WARN"
                                    :body "</span><script>alert(1)</script>"}]))) ]
      (is (not (str/includes? html "../bad")))
      (is (str/includes? html "&lt;script&gt;alert(1)&lt;/script&gt;"))
      (is (not (str/includes? html "<script>alert")))))
  (testing "trace hierarchy, timing, attributes and status render"
    (let [html (viewer/render-fragment {:trace trace-data})]
      (is (str/includes? html "<details open>"))
      (is (str/includes? html "--depth:1"))
      (is (str/includes? html "http.request.method"))
      (is (str/includes? html "otel-status-error")))))

(deftest genai-and-samizdat-observations-are-private-bounded-and-escaped
  (let [secret "NEVER-RENDER-RAW"
        prompt (str "safe <prompt> " (apply str (repeat 2100 "p")))
        sanitized (str "safe <answer> " (apply str (repeat 2100 "z")))
        generation
        {:traceId trace-id
         :spanTree
         [{:spanId "agent" :name "samizdat run" :durationNs 100
           :attributes {"samizdat.run.id" "run-1"}
           :children
           [{:spanId "control" :name "control loop" :durationNs 95
             :attributes {"samizdat.control.driver" "beam"}
             :children
             [{:spanId "branch" :name "branch W0" :durationNs 90
               :attributes {"samizdat.branch.id" "W0"}
               :children
               [{:spanId "turn" :name "turn 1" :durationNs 80
                 :attributes {"samizdat.turn.number" 1}
                 :children
                 [{:spanId "generation" :name "chat" :durationNs 70
                   :attributes
                   {"gen_ai.operation.name" "chat"
                  "gen_ai.provider.name" "openai"
                  "gen_ai.request.model" "local-model"
                  "gen_ai.usage.input_tokens" 42
                  "gen_ai.usage.output_tokens" 9
                  "gen_ai.usage.cache_read.input_tokens" 7
                  "gen_ai.response.finish_reasons" "stop"
                  "gen_ai.input.messages" secret
                  "gen_ai.output.messages" secret
                  "samizdat.prompt" secret
                  "samizdat.system.instructions" secret
                  "samizdat.reasoning" secret
                  "samizdat.tool.args" secret
                  "samizdat.prompt.content_state" "captured"
                  "samizdat.prompt.sanitized" prompt
                  "samizdat.response.content_state" "captured"
                  "samizdat.response.sanitized" sanitized}
                   :children
                   [{:spanId "tool" :name "read_file" :durationNs 10
                     :attributes {"samizdat.tool.name" "read_file"
                                  "tool.call.arguments" secret}}]}]}]}]}]}]}
        model (viewer/trace-model {:trace generation})
        observation (get-in model [:trace :spans 4 :generation])
        html (viewer/render-fragment {:trace generation})]
    (doseq [role ["Agent" "Control" "Branch" "Turn" "Generation" "Tool"]]
      (is (str/includes? html (str ">" role "</span>"))))
    (doseq [value ["openai" "local-model" "42" "9" "7" "stop"]]
      (is (str/includes? html value)))
    (is (= {:provider "openai" :model "local-model"
            :inputTokens "42" :outputTokens "9" :cacheTokens "7"
            :finishReason "stop"}
           (select-keys observation
                        [:provider :model :inputTokens :outputTokens
                         :cacheTokens :finishReason])))
    (is (str/includes? html "Captured prompt"))
    (is (str/includes? html "safe &lt;prompt&gt;"))
    (is (= 2000 (count (:prompt observation))))
    (is (str/includes? html "Captured response"))
    (is (str/includes? html "safe &lt;answer&gt;"))
    (is (= 2000
           (count (:response observation)))
        "the response, including its ellipsis, is capped at 2,000 characters")
    (is (str/includes? html "…</pre>"))
    (is (not (str/includes? html secret)))
    (is (not (str/includes? html "gen_ai.input.messages")))
    (is (not (str/includes? html "samizdat.prompt.sanitized")))
    (is (not (str/includes? html "samizdat.response.sanitized")))))

(deftest response-requires-explicit-captured-state
  (doseq [attributes
          [{"gen_ai.operation.name" "chat"
            "samizdat.response.sanitized" "SECRET_WITHOUT_STATE"}
           {"gen_ai.operation.name" "chat"
            "samizdat.response.content_state" "omitted"
            "samizdat.response.sanitized" "SECRET_WHILE_OMITTED"}
           {"gen_ai.operation.name" "chat"
            "samizdat.response.content_state" "captured"
            "gen_ai.output.messages" "SECRET_ALTERNATE"
            "gen_ai.completion" "SECRET_COMPLETION"}]]
    (let [html (viewer/render-fragment
                {:trace {:traceId trace-id
                         :spanTree [{:spanId "g" :name "chat"
                                     :durationNs 1
                                     :attributes attributes}]}})]
      (is (str/includes? html "Content not recorded (privacy default)"))
      (is (not (str/includes? html "SECRET_")))))
  (let [html (viewer/render-fragment
              {:trace {:traceId trace-id
                       :spanTree
                       [{:spanId "g" :name "chat" :durationNs 1
                         :attributes
                         {"gen_ai.operation.name" "chat"
                          "samizdat.response.content_state" "captured"
                          "samizdat.response.sanitized" "bounded safe output"}}]}})]
    (is (str/includes? html "bounded safe output"))
    (is (not (str/includes? html "Content not recorded")))))

(deftest prompt-and-intervention-require-explicit-semantic-state
  (let [html (viewer/render-fragment
              {:trace {:traceId trace-id
                       :spanTree
                       [{:spanId "g" :name "chat" :durationNs 2
                         :attributes
                         {"gen_ai.operation.name" "chat"
                          "samizdat.prompt.sanitized" "HIDDEN_WITHOUT_STATE"}}
                        {:spanId "i" :name "controller intervention" :durationNs 1
                         :attributes
                         {"samizdat.intervention.action" "revise"
                          "samizdat.intervention.reason" "needs a concrete mechanism"}}]}})]
    (is (not (str/includes? html "HIDDEN_WITHOUT_STATE")))
    (is (str/includes? html "Content not recorded (privacy default)"))
    (is (str/includes? html ">Intervention</span>"))
    (is (str/includes? html "samizdat.intervention.reason"))
    (is (str/includes? html "needs a concrete mechanism"))))

(deftest zero-javascript-and-strict-csp-enhancement
  (let [baseline (viewer/render-page index-data)
        enhanced (viewer/render-page
                   (merge index-data
                          {:base-path "/ops/otel"
                           :enhancement-path "/assets/viewer.js"}))
        script (viewer/enhancement-script)]
    (is (not (str/includes? baseline "<script")))
    (is (not (str/includes? baseline "<form"))
        "No work form is emitted unless the host supplies its route")
    (is (str/includes? enhanced
                       "<script src=\"/ops/otel/assets/viewer.js\" defer></script>"))
    (is (str/includes? script "new EventSource"))
    (is (not (str/includes? script "eval(")))
    (is (not (str/includes? script "new Function")))
    (is (not (str/includes? script "innerHTML")))))

(deftest trace-workbench-is-an-optional-zero-javascript-get-form
  (let [html (viewer/render-page
              (assoc index-data
                     :base-path "/ops/otel/"
                     :trace-filters trace-filters))]
    (is (str/includes? html "<form class=\"otel-filter-form\" action=\"/ops/otel\" method=\"get\""))
    (is (str/includes? html "name=\"operation\" value=\"GET /users\""))
    (is (str/includes? html "name=\"min-duration-ms\""))
    (is (str/includes? html "value=\"12.5\""))
    (is (str/includes? html "value=\"api\" selected>API</option>"))
    (is (str/includes? html "value=\"error\" selected>Error</option>"))
    (is (str/includes? html "value=\"1h\" selected>Last hour</option>"))
    (is (str/includes? html "href=\"/ops/otel\">Clear</a>"))
    (is (str/includes? html (str "href=\"/ops/otel/traces/" trace-id "\"")))
    (is (not (str/includes? html "data-otel-trace"))
        "trace navigation remains an ordinary link without enhancement")
    (is (not (str/includes? html "<script"))
        "filtering and trace navigation need no JavaScript"))
  (let [html (viewer/render-fragment index-data)]
    (is (not (str/includes? html "otel-filter-form"))
        "the workbench is absent unless the host supplies its model")))

(deftest trace-workbench-contract-is-bounded-validated-and-escaped
  (let [services (mapv (fn [i] {:value (str "svc" i)
                                 :label (str "Service " i)})
                       (range 80))
        model (viewer/trace-filter-model
               "/mounted"
               {:selected {:service "svc49" :operation (apply str (repeat 250 "x"))
                           :status "missing" :min-duration-ms "-1"
                           :window "missing"}
                :service-options services
                :status-options services
                :window-options services})]
    (is (= "/mounted" (:action model)))
    (is (= 50 (count (:serviceOptions model))))
    (is (= 50 (count (:statusOptions model))))
    (is (= 50 (count (:windowOptions model))))
    (is (= "svc49" (:service model)))
    (is (true? (get-in model [:serviceOptions 49 :selected])))
    (is (= 200 (count (:operation model))))
    (is (= "" (:status model)))
    (is (= "" (:window model)))
    (is (= "" (:minDurationMs model))))
  (is (nil? (viewer/trace-filter-model "/mounted" nil)))
  (is (= [] (:serviceOptions
             (viewer/trace-filter-model nil {:service-options 42})))
      "invalid option collections default to empty")
  (let [model (viewer/trace-filter-model
               nil
               {:selected {:service "outside" :operation 42
                           :status "ok" :min-duration-ms "1.25"
                           :window "1h"}
                :service-options [{:value "inside" :label "Inside"}]
                :status-options [{:value "ok" :label "OK"}]
                :window-options [{:value "1h" :label "Last hour"}]})]
    (is (= "" (:service model)) "unknown option values default to all")
    (is (= "" (:operation model)) "non-string free text defaults to blank")
    (is (= "ok" (:status model)))
    (is (= "1.25" (:minDurationMs model)))
    (is (= "1h" (:window model))))
  (let [html (viewer/render-fragment
              (assoc index-data :trace-filters
                     {:selected {:service "svc&danger"
                                 :operation "GET \"/unsafe\" <script>"
                                 :status "" :window ""}
                      :service-options [{:value "svc&danger"
                                         :label "<script>service</script>"}]}))]
    (is (str/includes? html "value=\"svc&amp;danger\" selected"))
    (is (str/includes? html "&lt;script&gt;service&lt;/script&gt;"))
    (is (str/includes? html
                       "value=\"GET &quot;/unsafe&quot; &lt;script&gt;\""))
    (is (not (str/includes? html "<script>service")))))

(deftest public-models-accept-host-shaped-bounded-data
  (let [model (viewer/index-model
                (merge index-data
                       {:title "Project telemetry"
                        :eyebrow "Project alpha · telemetry"
                        :base-path "/projects/alpha/telemetry"}))
        detail (viewer/trace-model
                 {:base-path "/projects/alpha/telemetry"
                  :trace trace-data})]
    (is (= "Project telemetry" (:title model)))
    (is (= "Project alpha · telemetry" (:eyebrow model)))
    (is (= (str "/projects/alpha/telemetry/traces/" trace-id)
           (get-in model [:traces 0 :href])))
    (is (= "/projects/alpha/telemetry" (:homePath detail)))
    (is (= [0 1] (mapv :depth (get-in detail [:trace :spans]))))))

(defn -main [& _]
  (let [result (clojure.test/run-tests 'otel.viewer-test)]
    (System/exit (if (zero? (+ (:fail result) (:error result))) 0 1))))
