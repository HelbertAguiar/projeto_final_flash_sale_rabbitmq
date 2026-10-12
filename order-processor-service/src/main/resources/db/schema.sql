CREATE TABLE IF NOT EXISTS orders (
    pedido_id UUID PRIMARY KEY,
    payload TEXT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE IF NOT EXISTS compensation_outbox (
    pedido_id UUID PRIMARY KEY REFERENCES orders(pedido_id),
    payload TEXT NOT NULL,
    published BOOLEAN NOT NULL DEFAULT FALSE
);
