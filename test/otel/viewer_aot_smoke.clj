(ns otel.viewer-aot-smoke
  (:require [clojure.string :as str]
            [otel.viewer :as viewer]))

(defn -main [& _]
  (let [html (viewer/render-page {:summary {} :traces [] :logs []})
        css (viewer/styles)
        js (viewer/enhancement-script)]
    (when-not (and (str/includes? html "<!doctype html>")
                   (str/includes? css ".otel-viewer")
                   (str/includes? js "EventSource"))
      (System/exit 1))
    (println "PASS: viewer assets available from self-contained image")))
