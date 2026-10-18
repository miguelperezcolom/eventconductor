{{/*
Expand the name of the chart.
*/}}
{{- define "eventconductor.name" -}}
{{- .Chart.Name | trunc 63 | trimSuffix "-" }}
{{- end }}

{{/*
Full name: release-chart, truncated to 63 chars.
*/}}
{{- define "eventconductor.fullname" -}}
{{- printf "%s-%s" .Release.Name .Chart.Name | trunc 63 | trimSuffix "-" }}
{{- end }}

{{/*
Common labels.
*/}}
{{- define "eventconductor.labels" -}}
helm.sh/chart: {{ .Chart.Name }}-{{ .Chart.Version }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end }}

{{/*
The Secret (key SECURITY_PASSWORD) an app's admin password comes from: its own
security.passwordSecret, else the one this chart generates (admin-secret.yaml).
Called with (dict "security" .Values.<app>.security "root" $).
*/}}
{{- define "eventconductor.adminPasswordSecret" -}}
{{- if .security.passwordSecret -}}
{{- .security.passwordSecret -}}
{{- else -}}
{{- printf "%s-admin" (include "eventconductor.fullname" .root) | trunc 63 | trimSuffix "-" -}}
{{- end -}}
{{- end }}

{{/*
Whether any app with security on has no passwordSecret, so the chart must generate one.
*/}}
{{- define "eventconductor.generateAdminSecret" -}}
{{- range $app := list .Values.orchestrator .Values.forms .Values.rules -}}
{{- if and $app.security.enabled (not $app.security.passwordSecret) }}true{{ end -}}
{{- end -}}
{{- end }}
