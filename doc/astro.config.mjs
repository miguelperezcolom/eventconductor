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
			],
			sidebar: [
				{
					label: 'Getting Started',
					items: [
						{ label: 'Introduction', slug: 'guides/introduction' },
						{ label: 'Comparison: Camunda & Temporal', slug: 'guides/comparison' },
						{ label: 'Quick Start', slug: 'guides/quickstart' },
						{ label: 'Deployment Modes', slug: 'guides/deployment-modes' },
						{ label: 'Demo Applications', slug: 'guides/demos' },
						{ label: 'UI Manual', slug: 'guides/ui-manual' },
					],
				},
				{
					label: 'Workflow Engine',
					items: [
						{ label: 'Workflow Definitions', slug: 'guides/workflow-definitions' },
						{ label: 'Starting a Process', slug: 'guides/starting-a-process' },
						{ label: 'Implementing Workers', slug: 'guides/workers' },
						{ label: 'Retries, Timeouts & Compensation', slug: 'guides/retries-timeouts-compensation' },
						{ label: 'Process Analytics', slug: 'guides/analytics' },
						{ label: 'Event Storming', slug: 'guides/event-storming' },
					],
				},
				{
					label: 'Forms Engine',
					items: [
						{ label: 'Form Definitions', slug: 'guides/form-definitions' },
						{ label: 'User Tasks', slug: 'guides/user-tasks' },
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
						{ label: 'Kafka Topics', slug: 'reference/kafka-topics' },
						{ label: 'Java API', slug: 'reference/java-api' },
					],
				},
			],
		}),
	],
});
