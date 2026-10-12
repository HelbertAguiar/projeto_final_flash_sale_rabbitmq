CREATE TABLE IF NOT EXISTS checkout_outbox (
    pedido_id UUID PRIMARY KEY,
    payload TEXT NOT NULL,
    state VARCHAR(16) NOT NULL CHECK (state IN ('PREPARED','READY','SENT','REJECTED')),
    compensated BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS checkout_outbox_pending ON checkout_outbox (created_at) WHERE state IN ('PREPARED','READY');
