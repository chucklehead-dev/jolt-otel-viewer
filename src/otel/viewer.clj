(ns otel.viewer
  "OpenTelemetry HTML renderer with a zero-JavaScript baseline.

  Hosts supply already-bounded data and may render either a complete document
  or an embeddable fragment. The renderer owns no database, server, SDK, or
  route lifecycle."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            ;; Selmer eagerly touches java.time and MessageDigest. These
            ;; side-effect requires install Jolt's host shims before it loads.
            [jolt.crypto]
            [jolt.time]
            [selmer.parser :as selmer]
            [selmer.util :as selmer-util]))

(def ^:private max-render-depth 64)
(def ^:private max-filter-options 50)
(def ^:private max-filter-value-length 100)
(def ^:private max-filter-label-length 200)
(def ^:private max-operation-length 200)
(def ^:private max-duration-length 32)
(def ^:private max-post-actions 8)
(def ^:private max-action-label-length 80)
(def ^:private max-sanitized-response-length 2000)
(def ^:private trace-id-pattern #"[0-9a-f]{32}")
(def ^:private duration-pattern #"[0-9]+(?:\.[0-9]+)?")
(def ^:private fragment-template
  (delay (slurp (io/resource "otel/viewer/fragment.html"))))
(def ^:private page-template
  (delay (slurp (io/resource "otel/viewer/page.html"))))
(def ^:private live-template
  (delay (slurp (io/resource "otel/viewer/live.html"))))
(def ^:private stylesheet
  (delay (slurp (io/resource "otel/viewer/viewer.css"))))
(def ^:private enhancement
  (delay (slurp (io/resource "otel/viewer/viewer.js"))))
(defn styles
  "Scoped viewer CSS for a host that embeds `render-fragment`."
  []
  @stylesheet)

(defn enhancement-script
  "Optional progressive enhancement for opening trace links in a native dialog.
  Hosts may serve this as a static JavaScript response; fragments work without it."
  []
  @enhancement)

(defn- text [value]
  (if (nil? value) "" (str value)))

(defn- status-class [status]
  (if (= "error" (str/lower-case (text status))) "error" "ok"))

(defn normalize-base-path
  "Normalize a mount prefix to either the empty root prefix or `/path`."
  [path]
  (let [path (str/replace (text path) #"/+$" "")]
    (cond
      (str/blank? path) ""
      (str/starts-with? path "/") path
      :else (str "/" path))))

(defn mounted-path
  "Resolve a host-owned route below `base-path`. Both arguments may include or
  omit slashes; the root prefix remains `/`."
  [base-path suffix]
  (let [base (normalize-base-path base-path)
        suffix (str/replace (text suffix) #"^/+" "")]
    (if (str/blank? suffix)
      (if (str/blank? base) "/" base)
      (str base "/" suffix))))

(defn- bounded-string [value limit]
  (when (string? value)
    (let [value (str/trim value)]
      (when-not (str/blank? value)
        (subs value 0 (min limit (count value)))))))

(defn- bounded-display-string [value limit]
  (when-let [value (bounded-string value (inc limit))]
    (if (> (count value) limit)
      (str (subs value 0 (max 0 (dec limit))) "…")
      value)))

(defn- bounded-display-value [value limit]
  (when-not (nil? value)
    (bounded-display-string (text value) limit)))

(defn- build-post-actions [base-path actions work-path work-label]
  (let [actions (if (sequential? actions) actions [])
        actions (if work-path
                  (cons {:path work-path
                         :label (or work-label "Generate work")}
                        actions)
                  actions)]
    (into []
          (comp
           (take max-post-actions)
           (keep (fn [action]
                   (when (map? action)
                     (when-let [path (bounded-string (:path action)
                                                    max-filter-label-length)]
                       {:path (mounted-path base-path path)
                        :label (or (bounded-string (:label action)
                                                   max-action-label-length)
                                   "Run")})))))
          actions)))

(defn- filter-options [options]
  (loop [remaining (seq (take max-filter-options
                              (if (sequential? options) options [])))
         seen #{}
         result []]
    (if-let [option (first remaining)]
      (let [value (bounded-string (:value option) max-filter-value-length)
            label (bounded-string (:label option) max-filter-label-length)]
        (if (or (nil? value) (contains? seen value))
          (recur (next remaining) seen result)
          (recur (next remaining)
                 (conj seen value)
                 (conj result {:value value :label (or label value)}))))
      result)))

(defn- selected-option [selected options]
  (let [selected (bounded-string selected max-filter-value-length)]
    (if (some #(= selected (:value %)) options) selected "")))

(defn- selected-options [options selected]
  (mapv #(assoc % :selected (= selected (:value %))) options))

(defn trace-filter-model
  "Build the optional trace-workbench presentation contract.

  `filters` is host-shaped data with `:selected` keys `:service`, `:operation`,
  `:status`, `:min-duration-ms`, and `:window`, plus vectors named
  `:service-options`, `:status-options`, and `:window-options`. Each option is
  `{:value string :label string}`. Query interpretation and option discovery
  remain host-owned. The renderer caps each option vector at 50 entries,
  bounds displayed strings, and clears selected enumerations absent from the
  visible options. Returns nil when the host omits `filters`."
  [base-path filters]
  (when (map? filters)
    (let [selected (:selected filters)
          services (filter-options (:service-options filters))
          statuses (filter-options (:status-options filters))
          windows (filter-options (:window-options filters))
          service (selected-option (:service selected) services)
          status (selected-option (:status selected) statuses)
          window (selected-option (:window selected) windows)
          operation (or (bounded-string (:operation selected) max-operation-length) "")
          duration (or (bounded-string (:min-duration-ms selected)
                                       max-duration-length) "")
          duration (if (re-matches duration-pattern duration) duration "")]
      {:action (mounted-path base-path "")
       :service service
       :operation operation
       :status status
       :minDurationMs duration
       :window window
       :serviceOptions (selected-options services service)
       :statusOptions (selected-options statuses status)
       :windowOptions (selected-options windows window)})))

(defn- duration-label [nanoseconds]
  (let [n (double (or nanoseconds 0))]
    (cond
      (< n 1000.0) (format "%.0f ns" n)
      (< n 1000000.0) (format "%.1f µs" (/ n 1000.0))
      (< n 1000000000.0) (format "%.2f ms" (/ n 1000000.0))
      :else (format "%.2f s" (/ n 1000000000.0)))))

(defn- attribute-name [attribute]
  (-> (text attribute)
      (str/replace #"^:" "")
      str/lower-case))

(def ^:private sensitive-attribute-fragments
  ["prompt" "system_instructions" "system.instructions" "reasoning"
   "input.messages" "input_messages" "output.messages" "output_messages"
   "request.messages" "request_messages" "message.content" "message_content"
   "tool.call.arguments" "tool_call.arguments" "tool.calls.arguments"
   "tool_calls.arguments" "tool.arguments" "tool_arguments" "tool.args"
   "tool_args"])

(defn- sensitive-attribute? [attribute]
  (let [attribute (attribute-name attribute)]
    (or (= attribute "samizdat.response.sanitized")
        (= attribute "samizdat.response.content_state")
        (= attribute "gen_ai.completion")
        (= attribute "llm.completions")
        (some #(str/includes? attribute %) sensitive-attribute-fragments))))

(defn- attribute-rows [attributes]
  (cond
    (map? attributes)
    (mapv (fn [[k v]] {:name (text k) :value (text v)})
          (sort-by (comp str key)
                   (remove (comp sensitive-attribute? key) attributes)))

    (str/blank? (text attributes)) []
    :else [{:name "Attributes" :value (text attributes)}]))

(defn- attribute-value [attributes name]
  (when (map? attributes)
    (some (fn [[k value]]
            (when (= name (attribute-name k)) value))
          attributes)))

(defn- present-attribute [attributes name]
  (bounded-display-value (attribute-value attributes name)
                         max-filter-label-length))

(defn- span-role [attributes]
  (let [operation (some-> (attribute-value attributes "gen_ai.operation.name")
                          text str/lower-case)]
    (cond
      (or (= operation "execute_tool")
          (attribute-value attributes "samizdat.tool.name")) "Tool"
      (#{"chat" "text_completion" "generate_content"} operation) "Generation"
      (attribute-value attributes "samizdat.turn.number") "Turn"
      (attribute-value attributes "samizdat.branch.id") "Branch"
      (or (= operation "invoke_agent")
          (attribute-value attributes "samizdat.run.id")) "Agent"
      :else nil)))

(defn- generation-view [attributes role]
  (when (= "Generation" role)
    (let [content-state (some-> (attribute-value attributes
                                                "samizdat.response.content_state")
                                text str/lower-case)
          response (when (= "captured" content-state)
                     (bounded-display-string
                      (attribute-value attributes "samizdat.response.sanitized")
                      max-sanitized-response-length))]
      {:provider (present-attribute attributes "gen_ai.provider.name")
       :model (or (present-attribute attributes "gen_ai.response.model")
                  (present-attribute attributes "gen_ai.request.model"))
       :inputTokens (present-attribute attributes "gen_ai.usage.input_tokens")
       :outputTokens (present-attribute attributes "gen_ai.usage.output_tokens")
       :cacheTokens (or (present-attribute attributes
                                          "gen_ai.usage.cache_read.input_tokens")
                        (present-attribute attributes
                                          "gen_ai.usage.cached_input_tokens"))
       :finishReason (present-attribute attributes "gen_ai.response.finish_reasons")
       :responseRecorded (boolean response)
       :response response})))

(defn- flatten-tree
  ([forest] (flatten-tree forest 0))
  ([forest depth]
   (if (> depth max-render-depth)
     []
     (into []
           (mapcat (fn [span]
                     (cons (assoc span :depth depth)
                           (flatten-tree (:children span) (inc depth)))))
           forest))))

(defn- with-timeline [spans]
  (let [start-of (fn [span]
                   (let [n (:timestampUnixNano span)]
                     (if (number? n) n 0)))
        starts (map start-of spans)
        origin (if (seq starts) (apply min starts) 0)
        finish (reduce (fn [end span]
                         (max end (+ (start-of span)
                                     (max 0 (or (:durationNs span) 0)))))
                       (inc origin) spans)
        extent (double (max 1 (- finish origin)))]
    (mapv (fn [span]
            (let [start (double (- (start-of span) origin))
                  duration (double (max 0 (or (:durationNs span) 0)))]
              (assoc span
                     :startPercent (format "%.4f" (* 100.0 (/ start extent)))
                     :widthPercent (format "%.4f" (* 100.0 (/ duration extent))))))
          spans)))

(defn- span-view [span]
  (let [attributes (:attributes span)
        role (span-role attributes)]
    {:id (text (:spanId span))
     :parentId (text (:parentSpanId span))
     :name (text (or (:name span) "(unnamed span)"))
     :kind (text (:kind span))
     :role role
     :generation (generation-view attributes role)
     :depth (min max-render-depth (max 0 (or (:depth span) 0)))
     :duration (duration-label (:durationNs span))
     :startPercent (:startPercent span)
     :widthPercent (:widthPercent span)
     :error (= "error" (str/lower-case (text (:status span))))
     :statusMessage (text (:statusMessage span))
     :open (zero? (or (:depth span) 0))
     :attributes (attribute-rows attributes)}))

(defn- log-view [log]
  {:timestamp (text (:timestamp log))
   :severity (text (:severity log))
   :body (text (:body log))})

(defn index-model
  "Build the bounded presentation model for a trace/log index. Hosts retain
  ownership of querying, authorization, limits, and ordering."
  [{:keys [title eyebrow base-path work-path work-label post-actions enhancement-path
           live-attributes summary traces logs trace-filters]}]
  (let [base (normalize-base-path base-path)
        actions (build-post-actions base post-actions work-path work-label)
        filters (trace-filter-model base trace-filters)
        trace-views
        (into []
              (comp
               (filter #(re-matches trace-id-pattern (text (:traceId %))))
               (map (fn [trace]
                      {:href (mounted-path base (str "traces/" (text (:traceId trace))))
                       :rootName (text (or (:rootSpan trace) "(root span)"))
                       :service (text (:service trace))
                       :startedAt (text (:startedAt trace))
                       :spanCount (or (:spanCount trace) 0)
                       :status (text (:status trace))
                       :statusClass (status-class (:status trace))})))
              traces)
        log-views (mapv log-view logs)]
    {:title (text (or title "OpenTelemetry"))
     :eyebrow (text (or eyebrow "OpenTelemetry"))
     :homePath (mounted-path base "")
     :postActions actions
     :hasPostActions (boolean (seq actions))
     :enhanced (boolean enhancement-path)
     :enhancementPath (when enhancement-path
                        (mounted-path base enhancement-path))
     :streaming (boolean live-attributes)
     :liveMarker (text (get live-attributes "data-otel-live"))
     :hasTraceFilters (boolean filters)
     :traceFilters filters
     :stats [{:label "Traces" :value (or (:traceCount summary) 0)}
             {:label "Spans" :value (or (:spanCount summary) 0)}
             {:label "Logs" :value (or (:logCount summary) 0)}
             {:label "Errors" :value (or (:errorCount summary) 0)}]
     :hasTraces (boolean (seq trace-views))
     :traces trace-views
     :hasLogs (boolean (seq log-views))
     :logs log-views}))

(defn trace-model
  "Build a bounded trace-detail presentation model from a host-shaped span
  tree. The renderer caps nesting depth independently of host input."
  [{:keys [title eyebrow base-path work-path work-label post-actions trace]}]
  (let [flat (-> (:spanTree trace) flatten-tree with-timeline)
        root (first flat)
        status (if (some #(= "error" (str/lower-case (text (:status %)))) flat)
                 "error" "ok")
        base (normalize-base-path base-path)
        actions (build-post-actions base post-actions work-path work-label)]
    {:title (text (or title "Trace detail"))
     :eyebrow (text (or eyebrow "OpenTelemetry"))
     :traceView true
     :homePath (mounted-path base "")
     :postActions actions
     :hasPostActions (boolean (seq actions))
     :trace {:id (text (:traceId trace))
             :rootName (text (or (:name root) "Trace detail"))
             :status status
             :statusClass (status-class status)
             :hasSpans (boolean (seq flat))
             :spans (mapv span-view flat)
             :hasLogs (boolean (seq (:logs trace)))
             :logs (mapv log-view (:logs trace))}}))

(defn render-fragment
  "Render embeddable viewer markup. Supply either index data or `:trace` detail.
  `:base-path` defaults to the site root; `:work-path` is optional."
  [model]
  (selmer-util/with-escaping
    (if (:trace model)
      (selmer/render @fragment-template (trace-model model))
      (let [page-model (index-model model)
            live-content (selmer/render @live-template page-model)]
        (selmer/render @fragment-template
                       (assoc page-model :liveContent [:safe live-content]))))))

(defn render-live-content
  "Render the bounded stats, traces, and logs inside `#otel-live`. This is the
  server-rendered payload used for Datastar patches and contains no scripts."
  [model]
  (selmer-util/with-escaping
    (selmer/render @live-template (index-model model))))

(defn render-page
  "Render a complete standalone HTML document from the same model accepted by
  `render-fragment`."
  [model]
  (let [title (text (or (:title model) "OpenTelemetry"))
        fragment (render-fragment model)
        base (normalize-base-path (:base-path model))]
    (selmer-util/with-escaping
      (selmer/render @page-template
                     {:title title
                      :styles [:safe @stylesheet]
                      :content [:safe fragment]
                      :enhancementPath (when-let [path (:enhancement-path model)]
                                         (mounted-path base path))}))))
