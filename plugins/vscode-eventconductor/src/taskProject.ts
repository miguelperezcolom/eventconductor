// Project templates for the "Create task module / service" commands.
//
// The canonical source of these templates is the JVM module `modules/worker-codegen`
// (io.mateu.workflow.codegen.ProjectGenerator), which the Maven goal and the IntelliJ plugin use
// directly. VS Code cannot call the JVM, so this file mirrors that output for the same inputs; keep
// the two in step when the template changes. Only the project skeleton is produced here — the Java
// is generated at build time by the Maven goal, never by the IDE.

export type Variant = "module" | "service";

export interface TaskAttribute {
  type?: string;
  items?: TaskAttribute;
}

export interface TaskContract {
  id: string;
  version: number;
  group: string;
  topic?: string | null;
  description?: string | null;
  input?: Record<string, TaskAttribute> | null;
  output?: Record<string, TaskAttribute> | null;
  errors?: { code: string; description?: string | null }[] | null;
}

export function artifactIdFor(group: string, variant: Variant): string {
  return group + (variant === "service" ? "-service" : "-tasks");
}

/** The generated project's pom — parent, coordinates and the ec.* properties, nothing else. */
export function pomXml(variant: Variant, groupId: string, artifactId: string, group: string, basePackage: string): string {
  const parent = variant === "service" ? "task-service-parent" : "task-module-parent";
  return `<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>io.mateu.workflow</groupId>
        <artifactId>${parent}</artifactId>
        <version>1.0-SNAPSHOT</version>
    </parent>

    <groupId>${groupId}</groupId>
    <artifactId>${artifactId}</artifactId>
    <version>0.1.0-SNAPSHOT</version>

    <properties>
        <ec.group>${escapeXml(group)}</ec.group>
        <ec.basePackage>${escapeXml(basePackage)}</ec.basePackage>
    </properties>
</project>
`;
}

/** The application.yaml for a standalone service (only its own settings; the rest has env defaults). */
export function applicationYaml(artifactId: string): string {
  return `# Only the service's own settings live here; everything else has a sensible default and is
# overridable by an environment variable.
spring:
  application:
    name: ${artifactId}
  cloud:
    stream:
      kafka:
        binder:
          # KAFKA_BROKERS overrides the broker list; the consumer group defaults to the
          # application name (see the worker-kafka binding defaults).
          brokers: \${KAFKA_BROKERS:localhost:9092}
`;
}

export const GITIGNORE = `target/
*.class
*.log
.idea/
*.iml
.vscode/
.DS_Store
`;

/** README written from the contract: the interface to implement, its inputs/outputs and errors. */
export function readme(variant: Variant, artifactId: string, contract: TaskContract): string {
  const base = pascal(contract.id) + "V" + contract.version;
  const intro = variant === "service"
    ? "A standalone EventConductor task service. Run it with `./mvnw spring-boot:run` (or build an image with `./mvnw spring-boot:build-image`)."
    : "An EventConductor task module. Add it as a dependency of any Spring Boot service and implement the interfaces below.";
  return `# ${artifactId}

${intro}

The task interfaces and their input/output types are generated at build time under
\`target/generated-sources\` — you never edit them. Implement each interface as a Spring bean; the
engine dispatches to it.

## Tasks to implement

### \`${base}Task\` — task \`${contract.id}@${contract.version}\`
${contract.description ? contract.description + "\n" : ""}
- **Input** (\`${contract.input && Object.keys(contract.input).length ? base + "Input" : "void"}\`): ${attributes(contract.input)}
- **Output** (\`${contract.output && Object.keys(contract.output).length ? base + "Output" : "void"}\`): ${attributes(contract.output)}
- **Errors**: ${errors(contract.errors)}
`;
}

function attributes(attrs?: Record<string, TaskAttribute> | null): string {
  if (!attrs || Object.keys(attrs).length === 0) {
    return "none";
  }
  return Object.entries(attrs).map(([name, a]) => `\`${name}\`: ${readableType(a)}`).join(", ");
}

function errors(list?: { code: string; description?: string | null }[] | null): string {
  if (!list || list.length === 0) {
    return "none";
  }
  const parts = list.map((e) => `\`${pascal(e.code)}\` (${e.code})` + (e.description ? ` — ${e.description}` : ""));
  return "throw one to fail the step — " + parts.join("; ");
}

function readableType(a: TaskAttribute): string {
  switch (a.type) {
    case "string": return "String";
    case "integer": return "Long";
    case "number": return "BigDecimal";
    case "boolean": return "Boolean";
    case "date": return "LocalDate";
    case "datetime": return "LocalDateTime";
    case "object": return "JsonNode";
    case "array": return `List<${a.items ? readableType(a.items) : "Object"}>`;
    default: return a.type ?? "Object";
  }
}

/** `confirm-booking` -> `ConfirmBooking`; `SOLD_OUT` -> `SoldOut`. Mirrors worker-codegen's Names. */
function pascal(raw: string): string {
  return raw
    .split(/[^A-Za-z0-9]+/)
    .filter((p) => p.length > 0)
    .map((p) => {
      const allUpper = p === p.toUpperCase();
      return p.charAt(0).toUpperCase() + (allUpper ? p.slice(1).toLowerCase() : p.slice(1));
    })
    .join("");
}

function escapeXml(s: string): string {
  return s.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;");
}
