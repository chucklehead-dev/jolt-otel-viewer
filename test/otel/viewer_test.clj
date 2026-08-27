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
