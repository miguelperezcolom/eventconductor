-- The rendered flow-control key of a receipt, so "what happened to this reservation's changes?" is one
-- query (GET /workflow/api/events/received?flowKey=): a debounce folds events into one process, and until
-- now the key was only inside the receipt's free-text detail.
--
-- Plain portable DDL with IF NOT EXISTS, as V42: it also runs over a schema ddl-auto created from the
-- entities (InboundEventReceiptEntity, which declares the same column and index).
ALTER TABLE inbound_event_receipt ADD COLUMN IF NOT EXISTS flow_key VARCHAR(255);

CREATE INDEX IF NOT EXISTS idx_inbound_event_receipt_flow_key_received
    ON inbound_event_receipt (flow_key, received_at);
