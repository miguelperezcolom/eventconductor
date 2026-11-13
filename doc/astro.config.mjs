// @ts-check
import { defineConfig } from 'astro/config';
import starlight from '@astrojs/starlight';

function rehypePreMermaid() {
	return (tree) => {
		function visit(node) {
			if (!node.children) return;
			for (let i = 0; i < node.children.length; i++) {
				const child = node.children[i];
				if (
					child.tagName === 'pre' &&
					child.children?.[0]?.tagName === 'code' &&
					child.children[0].properties?.className?.includes('language-mermaid')
				) {
					const code = child.children[0].children[0]?.value || '';
					node.children[i] = {
						type: 'element',
						tagName: 'pre',
						properties: { className: ['mermaid'] },
						children: [{ type: 'text', value: code }],
					};
				} else {
					visit(child);
				}
			}
		}
		visit(tree);
	};
}

// https://astro.build/config
export default defineConfig({
	markdown: {
		rehypePlugins: [rehypePreMermaid],
	},
	integrations: [
		starlight({
			title: 'EventConductor',
			head: [
				{
					tag: 'script',
					attrs: { type: 'module' },
					content: `import mermaid from 'https://cdn.jsdelivr.net/npm/mermaid@11/dist/mermaid.esm.min.mjs'; mermaid.initialize({ startOnLoad: true });`,
				},
			],
			description: 'Production-grade, event-driven workflow orchestration for the Java/Spring ecosystem.',
			social: [
				{ icon: 'github', label: 'GitHub', href: 'https://github.com/miguelperezcolom/eventconductor' },
				{ icon: 'discord', label: 'Discord', href: 'https://discord.gg/4vudTCheNc' },
			],
			sidebar: [
				{
					label: 'Getting Started',
					items: [
						{ label: 'Introduction', slug: 'guides/introduction' },
						{ label: 'Why a Process Engine?', slug: 'guides/why-a-process-engine' },
						{ label: 'Comparison: Camunda & Temporal', slug: 'guides/comparison' },
						{ label: 'Quick Start', slug: 'guides/quickstart' },
						{ label: 'Deployment Modes', slug: 'guides/deployment-modes' },
						{ label: 'Architecture', slug: 'guides/architecture' },
						{ label: 'Performance', slug: 'guides/performance' },
						{ label: 'Reliability', slug: 'guides/reliability' },
						{ label: 'Demo Applications', slug: 'guides/demos' },
						{ label: 'UI Manual', slug: 'guides/ui-manual' },
					],
				},
				{
					label: 'Workshop',
					items: [
						{ label: 'Overview', slug: 'workshop' },
						{ label: '0 · Setup', slug: 'workshop/00-setup' },
						{ label: '1 · A first flow', slug: 'workshop/01-first-flow' },
						{ label: '2 · One booking per room', slug: 'workshop/02-one-booking-per-room' },
						{ label: '3 · When things fail', slug: 'workshop/03-when-things-fail' },
						{ label: '4 · Calling the hotel', slug: 'workshop/04-calling-the-hotel' },
						{ label: '5 · Calling an AI', slug: 'workshop/05-calling-an-ai' },
						{ label: '6 · A person in the loop', slug: 'workshop/06-a-person-in-the-loop' },
						{ label: '7 · Operating', slug: 'workshop/07-operating' },
						{ label: '8 · Debugging a workflow', slug: 'workshop/08-debugging' },
						{
							label: '9 · Testing, automated',
							items: [
								{ label: 'Overview', slug: 'workshop/testing' },
								{ label: 'a · The happy path', slug: 'workshop/testing/happy-path' },
								{ label: 'b · When things fail', slug: 'workshop/testing/failure-paths' },
								{ label: 'c · Time', slug: 'workshop/testing/time' },
								{ label: 'd · Simulated integrations', slug: 'workshop/testing/integrations' },
								{ label: 'e · The real handlers', slug: 'workshop/testing/real-handlers' },
								{ label: 'f · Automate it', slug: 'workshop/testing/automate' },
								{ label: 'g · Debugging a failing test', slug: 'workshop/testing/debugging-a-failing-test' },
							],
						},
						{ label: '10 · Changing without redeploying', slug: 'workshop/10-changing-without-redeploying' },
					],
				},
				{
					label: 'Workflow Engine',
					items: [
						{ label: 'Workflow Definitions', slug: 'guides/workflow-definitions' },
						{ label: 'IDE Plugins (VS Code & IntelliJ)', slug: 'guides/ide-plugins' },
						{ label: 'Starting a Process', slug: 'guides/starting-a-process' },
						{ label: 'Synchronous Invocation', slug: 'guides/synchronous-invocation' },
						{ label: 'Asynchronous Request/Response', slug: 'guides/request-response' },
						{ label: 'HTTP Calls', slug: 'guides/http-calls' },
						{ label: 'LLM Calls', slug: 'guides/llm-calls' },
						{ label: 'Agents in Workflows', slug: 'guides/workflow-agents' },
						{ label: 'Publishing Domain Events', slug: 'guides/publishing-events' },
						{ label: 'Starting on and Waiting for Domain Events', slug: 'guides/domain-event-triggers' },
						{ label: 'Starting a Workflow from a Webhook', slug: 'guides/webhooks' },
						{ label: 'Payload Templates', slug: 'guides/payload-templates' },
						{ label: 'Scheduling and Deadlines', slug: 'guides/scheduling-and-deadlines' },
						{ label: 'Implementing Workers', slug: 'guides/workers' },
						{ label: 'Workers over HTTP (any language)', slug: 'guides/http-workers' },
						{ label: 'Testing Workflows', slug: 'guides/testing-workflows' },
						{ label: 'The Test Worker', slug: 'guides/test-worker' },
						{ label: 'Loops and Multi-Instance', slug: 'guides/loops-and-multi-instance' },
						{ label: 'Long-running Processes', slug: 'guides/long-running-processes' },
						{ label: 'Flow Control', slug: 'guides/flow-control' },
						{ label: 'Dynamic Workflows', slug: 'guides/dynamic-workflows' },
						{ label: 'Retries, Timeouts & Compensation', slug: 'guides/retries-timeouts-compensation' },
						{ label: 'Updating running processes', slug: 'guides/updating-running-processes' },
						{ label: 'Operating running processes', slug: 'guides/operating-running-processes' },
						{ label: 'Migrating running processes', slug: 'guides/migrating-running-processes' },
						{ label: 'Process Analytics', slug: 'guides/analytics' },
						{ label: 'Searching and Bulk Operations', slug: 'guides/searching-and-bulk-operations' },
						{ label: 'Sensitive Data and Encryption', slug: 'guides/sensitive-data-and-encryption' },
						{ label: 'Process Index (CQRS Read Model)', slug: 'guides/process-index' },
						{ label: 'Sharding', slug: 'guides/sharding' },
						{ label: 'Event Storming', slug: 'guides/event-storming' },
					],
				},
				{
					label: 'Forms Engine',
					items: [
						{ label: 'Form Definitions', slug: 'guides/form-definitions' },
						{ label: 'User Tasks', slug: 'guides/user-tasks' },
						{ label: 'Human Tasks: Assignment and Escalation', slug: 'guides/human-tasks-assignment-and-escalation' },
					],
				},
				{
					label: 'Rule Engine',
					items: [
						{ label: 'Rule Definitions', slug: 'guides/rule-definitions' },
						{ label: 'Rule Evaluation', slug: 'guides/rule-evaluation' },
					],
				},
				{
					label: 'AI Integration (MCP)',
					items: [
						{ label: 'Overview', slug: 'guides/mcp-overview' },
						{ label: 'Connect Claude Desktop', slug: 'guides/mcp-claude-desktop' },
						{ label: 'Custom MCP Tools', slug: 'guides/mcp-custom-tools' },
						{ label: 'ia-agent-service', slug: 'guides/ia-agent-service' },
					],
				},
				{
					label: 'Reference',
					items: [
						{ label: 'Step Types', slug: 'reference/step-types' },
						{ label: 'Process & Step Statuses', slug: 'reference/statuses' },
						{ label: 'Configuration', slug: 'reference/configuration' },
						{ label: 'Processes REST API', slug: 'reference/processes-rest-api' },
						{ label: 'Observability', slug: 'reference/observability' },
						{ label: 'Task Contracts & Worker Protocol', slug: 'reference/task-contracts' },
						{ label: 'Maven Plugin (validate & generate)', slug: 'reference/maven-plugin' },
						{ label: 'Kafka Topics', slug: 'reference/kafka-topics' },
						{ label: 'HTTP Worker API', slug: 'reference/http-worker-api' },
						{ label: 'Java API', slug: 'reference/java-api' },
						{ label: 'Versioning & Compatibility', slug: 'reference/versioning' },
						{ label: 'Benchmarks (local)', slug: 'reference/benchmarks' },
					],
				},
				{
					label: 'For AI assistants',
					items: [
						{ label: 'llms.txt', link: '/llms.txt', attrs: { target: '_blank' } },
						{ label: 'AI reference (compact)', link: '/eventconductor-ai-compact.md', attrs: { target: '_blank' } },
						{ label: 'AI reference (full)', link: '/eventconductor-ai-full.md', attrs: { target: '_blank' } },
					],
				},
			],
		}),
	],
});
