---
title: Custom MCP Tools
description: Expose your own domain as MCP tools for the AI agent.
---

Any Spring service can expose its own MCP tools by implementing `McpTools`. The agent discovers them automatically and incorporates their system context into the system prompt.

## Add a new MCP tool

```java
@Component
@RequiredArgsConstructor
public class MyMcpTools implements McpTools, McpSystemContext {

    private final MyRepository myRepository;

    @Override
    public String getSystemContext() {
        return """
            My domain: manages widgets.
            - Widgets have states: PENDING, ACTIVE, ARCHIVED.
            - Use createWidget to create new widgets.
            - Use listWidgets to see all widgets.
            """;
    }

    @Tool(description = "Create a new widget")
    public String createWidget(String name, String type) {
        Widget widget = myRepository.save(new Widget(name, type));
        return "Created widget: " + widget.getId();
    }

    @Tool(description = "List all widgets with their status")
    public List<WidgetSummary> listWidgets() {
        return myRepository.findAll().stream()
            .map(w -> new WidgetSummary(w.getId(), w.getName(), w.getStatus()))
            .toList();
    }
}
```

## Register the MCP server

Add your service's MCP endpoint to `demo/ia-agent-service/application.yaml`:

```yaml
spring:
  ai:
    mcp:
      client:
        sse:
          connections:
            my-service:
              url: http://localhost:MY_PORT
```

The agent will pick up the new tools on the next request and incorporate your `getSystemContext()` text into its system prompt automatically.

## The `system-context` MCP Prompt

Implementing `McpSystemContext` makes your service expose a `system-context` MCP Prompt. The agent reads this prompt when it connects to your server, giving the LLM domain-specific knowledge:

- What entities exist
- What states/statuses they can have
- What operations are available
- Any business rules or constraints

Write this as plain English — the LLM uses it to decide which tool to call and how to interpret results.

## Best practices

**Be specific in tool descriptions**

```java
// Good
@Tool(description = "List all bookings. Filter by status: PENDING, CONFIRMED, CANCELLED. Returns booking ID, lead name, dates, and status.")
public List<BookingSummary> listBookings(String status) { ... }

// Avoid
@Tool(description = "Get bookings")
public List<BookingSummary> listBookings(String status) { ... }
```

**Return structured data, not plain strings**

The LLM can reason about structured data much more effectively:

```java
// Good — return a record/object
public record BookingSummary(String id, String leadName, String status, LocalDate checkIn) {}

// Avoid — returning a formatted string
return "Booking " + id + " for " + leadName + " (status: " + status + ")";
```

**Document error conditions**

```java
@Tool(description = "Get booking details by ID. Returns null if the booking does not exist.")
public BookingDetail getBooking(String bookingId) {
    return repository.findById(bookingId).orElse(null);
}
```

**Keep tool granularity at the operation level**

One tool per business operation, not one tool per entity. The LLM composes them naturally.
