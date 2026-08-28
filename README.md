# jolt-otel-viewer

A small, host-agnostic OpenTelemetry HTML renderer for Jolt. Hosts provide
already-bounded trace summaries, logs, and trace trees; this package owns no
database, HTTP server, authentication, or exporter lifecycle.

## Install

Pin the exact commit you have reviewed:

```clojure
{:deps
 {io.github.chucklehead-dev/jolt-otel-viewer
  {:git/url "https://github.com/chucklehead-dev/jolt-otel-viewer.git"
   :git/sha "<full-commit-sha>"}}}
```

The placeholder must be replaced with a full 40-character commit SHA. This
repository does not publish mutable dependency coordinates.

The baseline output uses semantic HTML, `<details>`, ordinary links and forms,
and scoped CSS. `enhancement-script` optionally adds EventSource live-region
updates and native `<dialog>` trace previews. It is compatible with a strict
`script-src 'self'` policy: serve it from the host origin rather than inserting
it inline.

```clojure
(require '[otel.viewer :as viewer])

(viewer/render-fragment
  {:base-path "/ops/telemetry"
   :trace-filters
   {:selected {:service "app" :operation "GET" :status "error"
               :min-duration-ms "10" :window "1h"}
    :service-options [{:value "app" :label "Application"}]
    :status-options [{:value "ok" :label "OK"}
                     {:value "error" :label "Error"}]
    :window-options [{:value "15m" :label "Last 15 minutes"}
                     {:value "1h" :label "Last hour"}]}
   :summary {:traceCount 1 :spanCount 2 :logCount 0 :errorCount 0}
   :traces [{:traceId "0123456789abcdef0123456789abcdef"
             :rootSpan "GET /" :service "app" :spanCount 2 :status "ok"}]
   :logs []})
```

Public rendering surface:

- `render-fragment` and `render-page`
- `render-live-content` for server-sent patches
- `styles` and `enhancement-script` for host-owned asset routes
- `index-model`, `trace-model`, `trace-filter-model`, `normalize-base-path`, and
  `mounted-path`

Routes passed as `:work-path`, `:post-actions`, or `:enhancement-path` are
resolved beneath `:base-path`. `:post-actions` accepts up to eight
`{:path string :label string}` actions. The older `:work-path` and
`:work-label` pair remains supported and becomes the first action when both
forms are present. Omitting enhancement options produces a zero-JavaScript
viewer; every action remains an ordinary POST form.

The viewer does not infer application semantics from OpenTelemetry attribute
names. A library may enrich a span with a Kindly note under `:kindly`; the
viewer reads the standard `:kindly/kind` and `:kindly/options` value metadata.
This keeps role names, observation tables, captured content, and default-open
behavior beside the library's instrumentation metadata instead of hard-coding
them in the viewer.

```clojure
{:spanId "..."
 :name "chat"
 :kindly
 {:value
  (with-meta
    [(with-meta
       [(array-map :Provider "local" :Model "example-model")]
       {:kindly/kind :kind/table})]
    {:kindly/kind :kind/fragment
     :kindly/options
     {:otel.viewer/role "Generation"
      :otel.viewer/tone :accent
      :otel.viewer/label "Generation observation"
      :otel.viewer/hide-attributes ["app.prompt.sanitized"]}})}}
```

The `:kindly` value uses the same note shape as Kindly renderers:
`{:value annotated-value}`. The bounded, server-side adapter currently supports
`:kind/fragment`, `:kind/table`, `:kind/code`, `:kind/println`, `:kind/pprint`,
and `:kind/md`; unsupported kinds fall back to ordinary span attributes. It
also honors Kindly's scalar wrapping contract via
`:kindly/options {:wrapped-value true}`. Viewer-specific behavior lives only in
namespaced options: `:otel.viewer/role`, `:otel.viewer/tone`,
`:otel.viewer/label`, `:otel.viewer/open?`, and
`:otel.viewer/hide-attributes`.

Prompt, system, reasoning, message-content, and tool-argument attributes remain
excluded from generic rows even without enrichment. Libraries decide whether
to place redacted content into an explicit Kindly `:kind/code` item; every code
item is escaped and capped at 2,000 characters.

Supplying `:trace-filters` adds a semantic GET form whose action is the mounted
index path. The host owns query parsing and storage and passes the selected
values back under `:selected`; the renderer only presents them. Supported
selected keys are `:service`, `:operation`, `:status`, `:min-duration-ms`, and
`:window`. Select controls use host-provided `{:value string :label string}`
vectors under `:service-options`, `:status-options`, and `:window-options`.
Each option vector is capped at 50 visible entries, duplicate/invalid values are
discarded, selected enumeration values must occur in the visible options, free
text is bounded, and minimum duration accepts only a non-negative decimal
string. The empty selection means all services/statuses, no duration threshold,
or any time. Ordinary trace links and the GET form remain fully functional when
the enhancement script is omitted.

Index inputs use `:summary`, `:traces`, and `:logs`; trace-detail input uses
`:trace {:traceId ... :spanTree [...] :logs [...]}`. Span children are nested
under `:children`. The package rejects malformed trace IDs and caps rendered
tree depth, but the host must still impose query/result-count and field-size
limits before rendering.

## Development and releases

Run `jolt -M:test` with Jolt v0.7.27 or newer. A release is an immutable Git
tag pointing at a commit for which the test workflow passed; consumers should
continue to pin that commit SHA even when also recording the tag.

This project is licensed under the Eclipse Public License 2.0; see `LICENSE`.
