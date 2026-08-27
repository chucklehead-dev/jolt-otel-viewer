# jolt-otel-viewer

A small, host-agnostic OpenTelemetry HTML renderer for Jolt. Hosts provide
already-bounded trace summaries, logs, and trace trees; this package owns no
database, HTTP server, authentication, or exporter lifecycle.

The baseline output uses semantic HTML, `<details>`, ordinary links and forms,
and scoped CSS. `enhancement-script` optionally adds EventSource live-region
updates and native `<dialog>` trace previews. It is compatible with a strict
`script-src 'self'` policy: serve it from the host origin rather than inserting
it inline.

```clojure
(require '[otel.viewer :as viewer])

(viewer/render-fragment
  {:base-path "/ops/telemetry"
   :summary {:traceCount 1 :spanCount 2 :logCount 0 :errorCount 0}
   :traces [{:traceId "0123456789abcdef0123456789abcdef"
             :rootSpan "GET /" :service "app" :spanCount 2 :status "ok"}]
   :logs []})
```

Public rendering surface:

- `render-fragment` and `render-page`
- `render-live-content` for server-sent patches
- `styles` and `enhancement-script` for host-owned asset routes
- `index-model`, `trace-model`, `normalize-base-path`, and `mounted-path`

Routes passed as `:work-path` or `:enhancement-path` are resolved beneath
`:base-path`. Omitting enhancement options produces a zero-JavaScript viewer.

Index inputs use `:summary`, `:traces`, and `:logs`; trace-detail input uses
`:trace {:traceId ... :spanTree [...] :logs [...]}`. Span children are nested
under `:children`. The package rejects malformed trace IDs and caps rendered
tree depth, but the host must still impose query/result-count and field-size
limits before rendering.
