{{/*
Pieces shared by the optional components (worker, httpWorker, llmWorker, webhookGateway).
Each takes a dict: root (the chart context), name (component label), v (its values).
*/}}

{{/* Pod-level spec up to and including initContainers waiting for Redpanda (and PostgreSQL). */}}
{{- define "eventconductor.component.podPreamble" -}}
{{- with .root.Values.nodeSelector }}
nodeSelector:
  {{- toYaml . | nindent 2 }}
{{- end }}
securityContext:
  runAsNonRoot: true
  runAsUser: 10001
  fsGroup: 10001
initContainers:
  {{- if .postgres }}
  - name: wait-for-postgres
    image: busybox:1.36
    command: ['sh', '-c', 'until nc -z postgres 5432; do echo waiting for postgres; sleep 2; done']
    securityContext:
      allowPrivilegeEscalation: false
      capabilities: { drop: ["ALL"] }
  {{- end }}
  - name: wait-for-redpanda
    image: busybox:1.36
    command: ['sh', '-c', 'until nc -z redpanda 19092; do echo waiting for redpanda; sleep 2; done']
    securityContext:
      allowPrivilegeEscalation: false
      capabilities: { drop: ["ALL"] }
{{- end }}

{{/* Container fields after env: envFrom, resources, security, /tmp, probes. */}}
{{- define "eventconductor.component.containerTail" -}}
{{- if .v.existingSecret }}
envFrom:
  - secretRef:
      name: {{ .v.existingSecret }}
{{- end }}
resources:
  {{- toYaml .v.resources | nindent 2 }}
securityContext:
  allowPrivilegeEscalation: false
  readOnlyRootFilesystem: true
  capabilities: { drop: ["ALL"] }
volumeMounts:
  - name: tmp
    mountPath: /tmp
readinessProbe:
  httpGet:
    path: /actuator/health/readiness
    port: {{ .v.port }}
  initialDelaySeconds: 20
  periodSeconds: 10
livenessProbe:
  httpGet:
    path: /actuator/health/liveness
    port: {{ .v.port }}
  initialDelaySeconds: 60
  periodSeconds: 20
{{- end }}

{{/* Env common to all of them: port, broker, probe groups, tmpdir. */}}
{{- define "eventconductor.component.commonEnv" -}}
- name: SERVER_PORT
  value: {{ .v.port | quote }}
- name: KAFKA_BROKERS
  value: {{ .root.Values.redpanda.brokerInternal }}
# Liveness/readiness groups, which the probes call. Spring Boot turns them on by itself when
# it detects Kubernetes; set explicitly so the probes do not depend on that detection.
- name: MANAGEMENT_ENDPOINT_HEALTH_PROBES_ENABLED
  value: "true"
- name: JAVA_TOOL_OPTIONS
  value: "-Djava.io.tmpdir=/tmp"
{{- end }}

{{/* PodDisruptionBudget, when enabled. */}}
{{- define "eventconductor.component.pdb" -}}
{{- if .v.podDisruptionBudget.enabled }}
---
apiVersion: policy/v1
kind: PodDisruptionBudget
metadata:
  name: {{ include "eventconductor.fullname" .root }}-{{ .name }}
spec:
  minAvailable: {{ .v.podDisruptionBudget.minAvailable }}
  selector:
    matchLabels:
      app.kubernetes.io/instance: {{ .root.Release.Name }}
      app.kubernetes.io/component: {{ .name }}
{{- end }}
{{- end }}
