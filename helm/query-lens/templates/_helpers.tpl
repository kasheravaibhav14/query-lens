{{/*
  _helpers.tpl — Named template functions shared across all chart resources.

  WHY THIS FILE EXISTS:
    Without helpers, every resource would repeat the same label block, name
    logic, etc. Named templates let you define once and {{ include }} anywhere.

  HOW TO CALL A NAMED TEMPLATE:
    {{ include "query-lens.labels" . | nindent 4 }}
     └─ template name ─┘  └─ context ─┘  └─ indent by 4 spaces ┘

  The dot (.) passes the current Helm context (Values, Release, Chart, etc.)
  into the template so it can access the same data as the calling file.
*/}}

{{/*
  Chart name — simply the name declared in Chart.yaml.
*/}}
{{- define "query-lens.name" -}}
{{- .Chart.Name }}
{{- end }}

{{/*
  Standard labels applied to EVERY Kubernetes resource in this chart.

  These follow the official Kubernetes recommended label set:
    https://kubernetes.io/docs/concepts/overview/working-with-objects/common-labels/

  They enable:
    - kubectl get all -l app.kubernetes.io/name=query-lens
    - Helm tracking (managed-by)
    - Tooling integration (Lens, ArgoCD, Datadog)
*/}}
{{- define "query-lens.labels" -}}
app.kubernetes.io/name: {{ include "query-lens.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
{{- end }}

{{/*
  Selector labels — a STABLE subset of the full label set.

  IMPORTANT: selector labels on Deployments/StatefulSets are IMMUTABLE after
  first deploy. Never include chart version here — upgrading would break selects.
  Pods are matched to Services/Deployments via these labels.
*/}}
{{- define "query-lens.selectorLabels" -}}
app.kubernetes.io/name: {{ include "query-lens.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end }}
