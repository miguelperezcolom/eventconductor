-- The "received events" listing of one workflow (EventTriggerService.received(limit, workflowId): the
-- latest receipts of a workflow, newest first) filters inbound_event_receipt by workflow_id and orders
-- by received_at. V39 only indexed received_at, so on a busy table that query read every recent receipt
-- of every workflow; this index answers it as one range scan.
--
-- Plain portable DDL with IF NOT EXISTS, as V29-V41: it also runs over a schema ddl-auto created from
-- the entities (InboundEventReceiptEntity, which declares the same index).
CREATE INDEX IF NOT EXISTS idx_inbound_event_receipt_workflow_received
    ON inbound_event_receipt (workflow_id, received_at);
