import * as yaml from "js-yaml";

type RecordValue = Record<string, any>;
type Path = Array<string | number>;
const object = (value: unknown): value is RecordValue => !!value && typeof value === "object" && !Array.isArray(value);
const names = (value: unknown): string[] => Array.isArray(value) ? value.filter(item => typeof item === "string") : [];
const RESERVED = new Set(["true", "false", "null", "empty", "and", "or", "not", "eq", "ne", "lt", "le", "gt", "ge", "in",
  "new", "return", "instanceof", "businessKey", "process", "step", "now", "iteration", "completedIterations", "totalIterations", "activeIterations"]);
const UNSAFE = new Set(["__proto__", "prototype", "constructor"]);

export function parseRunWorkflow(text: string): RecordValue {
  const parsed: unknown = /^\s*[{[]/.test(text) ? JSON.parse(text) : yaml.load(text);
  if (!object(parsed) || !Array.isArray(parsed.steps))
    throw new Error("The workflow must be a YAML/JSON object with a steps array.");
  return parsed;
}

/** Static reference discovery only: never evaluates an expression or copies a literal value. */
function references(expression: string): Path[] {
  const result: Path[] = [];
  let i = 0;
  const skipQuoted = (from: number) => {
    const quote = expression[from++];
    while (from < expression.length) { if (expression[from] === "\\") from += 2; else if (expression[from++] === quote) break; }
    return from;
  };
  while (i < expression.length) {
    const c = expression[i];
    if (c === "'" || c === '"' || c === "`") { i = skipQuoted(i); continue; }
    const token = /^[A-Za-z_$][\w$]*/.exec(expression.slice(i));
    if (!token || i > 0 && /[\w$.]/.test(expression[i - 1])) { i++; continue; }
    const prefix = expression.slice(0, i).trimEnd();
    const path: Path = [token[0]]; i += token[0].length;
    while (i < expression.length) {
      const member = /^\s*\.\s*([A-Za-z_$][\w$]*)/.exec(expression.slice(i));
      const index = /^\s*\[\s*(?:(\d+)|'([^'\\]*)'|"([^"\\]*)")\s*\]/.exec(expression.slice(i));
      if (member) { path.push(member[1]); i += member[0].length; }
      else if (index) { path.push(index[1] === undefined ? index[2] ?? index[3] : 0); i += index[0].length; }
      else break;
    }
    const rest = expression.slice(i).trimStart();
    if (rest.startsWith("(") && path.length > 1) path.pop(); // order.lines.size() reads order.lines.
    else if (rest.startsWith("(") || /^:\s*\w+\s*\(/.test(rest)
      || rest.startsWith(":") && /[{,]$/.test(prefix)) continue;
    if (!RESERVED.has(String(path[0]))) result.push(path);
  }
  return result;
}

function templateExpressions(text: string): string[] {
  const result: string[] = [];
  for (let i = 0; i < text.length; i++) {
    if (text.startsWith("$${", i)) { i += 2; continue; }
    if (!text.startsWith("${", i)) continue;
    const start = i + 2;
    let depth = 1, quote = "";
    for (i = start; i < text.length; i++) {
      const char = text[i];
      if (quote) { if (char === "\\") i++; else if (char === quote) quote = ""; }
      else if (char === "'" || char === '"' || char === "`") quote = char;
      else if (char === "{") depth++;
      else if (char === "}" && --depth === 0) { result.push(text.slice(start, i)); break; }
    }
  }
  return result;
}

interface Shape { array?: boolean; children: Map<string | number, Shape>; }

/** Best-effort input shape from this document; worker contracts may require extra fields. */
export function workflowInputTemplate(definition: RecordValue): Record<string, unknown> {
  const steps = (definition.steps as unknown[]).filter(object);
  const byId = new Map(steps.map(step => [step.id, step]));
  const producers = new Map<string, Set<string>>();
  const root: Shape = { children: new Map() };
  const fields = new Set<string>();
  const add = (path: Path, array = false) => {
    if (!path.length || path.length > 20 || path.some(part => UNSAFE.has(String(part))) || fields.size >= 250 && !fields.has(JSON.stringify(path))) return;
    let node = root;
    for (const part of path) {
      if (typeof part === "number") node.array = true;
      if (!node.children.has(part)) node.children.set(part, { children: new Map() });
      node = node.children.get(part)!;
    }
    node.array ||= array; fields.add(JSON.stringify(path));
  };
  for (const step of steps) {
    const outputs = [...names(step.outputVariables), step.agent?.outputVariable, step.llm?.outputVariable, step.forEach?.output,
      ...Object.keys(step.http?.output ?? {}), ...Object.keys(step.llm?.output ?? {}), ...Object.keys(step.onEvent?.variables ?? {}),
      ...Object.keys(step.awaitReply?.onEvent?.variables ?? {})];
    for (const output of outputs) if (typeof output === "string" && output) {
      if (!producers.has(output)) producers.set(output, new Set());
      producers.get(output)!.add(step.id);
    }
  }
  const ancestors = (step: RecordValue): Set<string> => {
    const found = new Set<string>();
    const visit = (id: string) => {
      if (found.has(id) || found.size >= 2000) return;
      found.add(id); const previous = byId.get(id);
      if (previous) predecessors(previous).forEach(visit);
    };
    predecessors(step).forEach(visit);
    return found;
  };
  function predecessors(step: RecordValue): string[] {
    return [step.preconditionStepId, ...names(step.preconditionStepIds), ...(step.preconditions ?? []).map((p: any) => p?.stepId)]
      .filter((id): id is string => typeof id === "string");
  }
  for (const schedule of [...(definition.schedules ?? []), ...(definition.triggers ?? [])])
    if (object(schedule?.variables)) Object.keys(schedule.variables).forEach(name => add([name]));

  for (const step of steps) {
    const prior = ancestors(step);
    const item = typeof step.forEach?.as === "string" ? step.forEach.as : "item";
    const index = typeof step.forEach?.indexAs === "string" ? step.forEach.indexAs : "index";
    const items = typeof step.forEach?.items === "string" && /^[\w$.]+$/.test(step.forEach.items.trim())
      ? references(step.forEach.items)[0] : undefined;
    const read = (expression: unknown, after = false, array = false, iterationScope = true) => {
      if (typeof expression !== "string") return;
      for (const path of references(expression)) {
        const name = String(path[0]);
        if (step.forEach && iterationScope && name === index) continue;
        if (step.forEach && iterationScope && name === item) {
          if (items && path.length > 1 && ![...(producers.get(String(items[0])) ?? [])].some(id => prior.has(id)))
            add([...items, 0, ...path.slice(1)]);
          continue;
        }
        if ([...(producers.get(name) ?? [])].some(id => prior.has(id) || after && id === step.id)) continue;
        add(path, array);
      }
    };
    const template = (value: unknown, seen = new WeakSet<object>(), depth = 0) => {
      if (depth > 20) return;
      if (typeof value === "string") templateExpressions(value).forEach(expression => read(expression));
      else if (value && typeof value === "object" && !seen.has(value)) {
        seen.add(value); Object.values(value).forEach(child => template(child, seen, depth + 1));
      }
    };
    [step.agent?.taskPrompt, step.agent?.input, step.llm?.system, step.llm?.prompt, step.http?.url, step.http?.path,
      step.http?.query, step.http?.headers, step.http?.body, step.http?.bodyTemplate, step.event, step.replyTemplate, step.until, step.deadline,
      step.assignment].forEach(value => template(value));
    [step.preconditionExpression, ...(step.preconditions ?? []).map((p: any) => p?.expression), step.correlationExpression,
      step.lockKey, step.replyExpression].forEach(expression => read(expression));
    read(step.forEach?.items, false, !!items, false);
    read(step.untilVariable);
    read(step.awaitReply?.correlationExpression, true);
    read(step.forEach?.completionCondition, true);
    read(step.repeat?.until, true); read(step.loopBack?.while, true);
    [...names(step.replyVariables), ...names(step.messageVariables), ...names(step.continueAsNew?.variables)]
      .forEach(name => read(name));
    if (object(step.continueAsNew?.variables)) template(step.continueAsNew.variables);
  }
  const lock = definition.processLock;
  if (typeof lock?.key === "string") references(lock.key).forEach(path => add(path));
  const materialize = (node: Shape): unknown => {
    if (node.array) return node.children.has(0) ? [materialize(node.children.get(0)!)] : [];
    if (!node.children.size) return "";
    return Object.fromEntries([...node.children].sort(([a], [b]) => String(a).localeCompare(String(b)))
      .map(([name, value]) => [name, materialize(value)]));
  };
  return root.children.size ? materialize(root) as Record<string, unknown> : {};
}
