// @ts-check
import { defineConfig } from 'astro/config';
import starlight from '@astrojs/starlight';
import rehypeMermaid from 'rehype-mermaid';

// https://astro.build/config
export default defineConfig({
	markdown: {
		rehypePlugins: [rehypeMermaid],
	},
	integrations: [
		starlight({
			title: 'EventConductor',
			description: 'Production-grade, event-driven workflow orchestration for the Java/Spring ecosystem.',
			social: [
				{ icon: 'github', label: 'GitHub', href: 'https://github.com/miguelperezcolom/eventconductor' },
			],
			sidebar: [
				{
					label: 'Getting Started',
					items: [
						{ label: 'Introduction', slug: 'guides/introduction' },
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
