-- V2: multiple godowns, batch/expiry (pharma), drug licences, purchases + goods receipt, stock transfers,
-- live location, driver phones, Tally master mapping, media text (transcripts / OCR).

-- ---------- godowns ----------
CREATE TABLE godowns (
  id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  tenant_id   BIGINT NOT NULL REFERENCES tenants(id),
  code        TEXT NOT NULL,
  name        TEXT NOT NULL,
  address     TEXT,
  is_default  BOOLEAN NOT NULL DEFAULT FALSE,
  active      BOOLEAN NOT NULL DEFAULT TRUE,
  UNIQUE (tenant_id, code)
);
CREATE UNIQUE INDEX ux_godowns_default ON godowns (tenant_id) WHERE is_default;

INSERT INTO godowns (tenant_id, code, name, is_default) SELECT id, 'MAIN', 'Main godown', TRUE FROM tenants;

ALTER TABLE stock ADD COLUMN godown_id BIGINT REFERENCES godowns(id);
UPDATE stock s SET godown_id = g.id FROM godowns g WHERE g.tenant_id = s.tenant_id AND g.is_default;
ALTER TABLE stock ALTER COLUMN godown_id SET NOT NULL;
ALTER TABLE stock DROP CONSTRAINT stock_pkey;
ALTER TABLE stock ADD PRIMARY KEY (product_id, godown_id);
CREATE INDEX ix_stock_godown ON stock (godown_id);

ALTER TABLE sales_orders ADD COLUMN godown_id BIGINT REFERENCES godowns(id);
UPDATE sales_orders o SET godown_id = g.id FROM godowns g WHERE g.tenant_id = o.tenant_id AND g.is_default;
ALTER TABLE sales_orders ALTER COLUMN godown_id SET NOT NULL;

-- ---------- products: batches, licences, Tally mapping ----------
ALTER TABLE products
  ADD COLUMN batch_tracked    BOOLEAN NOT NULL DEFAULT FALSE,
  ADD COLUMN requires_licence BOOLEAN NOT NULL DEFAULT FALSE,
  ADD COLUMN hsn              TEXT,
  ADD COLUMN tally_name       TEXT;
CREATE UNIQUE INDEX ux_products_tally ON products (tenant_id, tally_name) WHERE tally_name IS NOT NULL;

CREATE TABLE stock_batches (
  id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  tenant_id   BIGINT NOT NULL REFERENCES tenants(id),
  product_id  BIGINT NOT NULL REFERENCES products(id),
  godown_id   BIGINT NOT NULL REFERENCES godowns(id),
  batch_no    TEXT NOT NULL,
  expiry_date DATE NOT NULL,
  mfg_date    DATE,
  mrp         NUMERIC(12,2),
  on_hand     INT NOT NULL DEFAULT 0 CHECK (on_hand >= 0),
  reserved    INT NOT NULL DEFAULT 0 CHECK (reserved >= 0),
  CONSTRAINT ck_batch_reserved CHECK (reserved <= on_hand),
  UNIQUE (product_id, godown_id, batch_no)
);
CREATE INDEX ix_batches_fefo ON stock_batches (product_id, godown_id, expiry_date) WHERE on_hand > 0;
CREATE INDEX ix_batches_expiry ON stock_batches (tenant_id, expiry_date) WHERE on_hand > 0;

-- ---------- customers: drug licence, Tally ledger ----------
ALTER TABLE customers
  ADD COLUMN drug_licence_no     TEXT,
  ADD COLUMN drug_licence_expiry DATE,
  ADD COLUMN tally_ledger        TEXT;
CREATE UNIQUE INDEX ux_customers_tally ON customers (tenant_id, tally_ledger) WHERE tally_ledger IS NOT NULL;

-- ---------- orders: which batches were picked ----------
CREATE TABLE order_line_batches (
  order_line_id BIGINT NOT NULL REFERENCES order_lines(id),
  batch_id      BIGINT NOT NULL REFERENCES stock_batches(id),
  qty           INT NOT NULL CHECK (qty > 0),
  PRIMARY KEY (order_line_id, batch_id)
);

-- ---------- vehicles: driver WhatsApp ----------
ALTER TABLE vehicles ADD COLUMN driver_phone TEXT;

-- ---------- purchases ----------
CREATE TABLE suppliers (
  id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  tenant_id   BIGINT NOT NULL REFERENCES tenants(id),
  name        TEXT NOT NULL,
  phone       TEXT,
  email       TEXT,
  gstin       TEXT,
  lead_days   INT NOT NULL DEFAULT 3 CHECK (lead_days >= 0),
  active      BOOLEAN NOT NULL DEFAULT TRUE,
  UNIQUE (tenant_id, name)
);

CREATE TABLE purchase_orders (
  id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  tenant_id     BIGINT NOT NULL REFERENCES tenants(id),
  number        BIGINT NOT NULL,
  supplier_id   BIGINT NOT NULL REFERENCES suppliers(id),
  godown_id     BIGINT NOT NULL REFERENCES godowns(id),
  status        TEXT NOT NULL CHECK (status IN ('DRAFT','SENT','PARTIAL','RECEIVED','CANCELLED')),
  subtotal      NUMERIC(14,2) NOT NULL DEFAULT 0,
  tax           NUMERIC(14,2) NOT NULL DEFAULT 0,
  total         NUMERIC(14,2) NOT NULL DEFAULT 0,
  expected_date DATE,
  note          TEXT,
  created_by    BIGINT REFERENCES users(id),
  created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
  sent_at       TIMESTAMPTZ,
  UNIQUE (tenant_id, number)
);
CREATE INDEX ix_po_tenant_status ON purchase_orders (tenant_id, status);

CREATE TABLE po_lines (
  id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  po_id        BIGINT NOT NULL REFERENCES purchase_orders(id),
  product_id   BIGINT NOT NULL REFERENCES products(id),
  qty          INT NOT NULL CHECK (qty > 0),
  received_qty INT NOT NULL DEFAULT 0 CHECK (received_qty >= 0),
  rate         NUMERIC(12,2) NOT NULL CHECK (rate >= 0),
  gst_rate     NUMERIC(5,2) NOT NULL,
  CONSTRAINT ck_po_received CHECK (received_qty <= qty)
);
CREATE INDEX ix_po_lines_po ON po_lines (po_id);

CREATE TABLE goods_receipts (
  id                  BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  tenant_id           BIGINT NOT NULL REFERENCES tenants(id),
  number              BIGINT NOT NULL,
  po_id               BIGINT NOT NULL REFERENCES purchase_orders(id),
  godown_id           BIGINT NOT NULL REFERENCES godowns(id),
  supplier_invoice_no TEXT,
  amount              NUMERIC(14,2) NOT NULL,
  received_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
  created_by          BIGINT REFERENCES users(id),
  UNIQUE (tenant_id, number)
);

CREATE TABLE grn_lines (
  id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  grn_id      BIGINT NOT NULL REFERENCES goods_receipts(id),
  po_line_id  BIGINT NOT NULL REFERENCES po_lines(id),
  product_id  BIGINT NOT NULL REFERENCES products(id),
  qty         INT NOT NULL CHECK (qty > 0),
  batch_id    BIGINT REFERENCES stock_batches(id)
);

-- ---------- stock transfers between godowns ----------
CREATE TABLE stock_transfers (
  id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  tenant_id     BIGINT NOT NULL REFERENCES tenants(id),
  number        BIGINT NOT NULL,
  from_godown   BIGINT NOT NULL REFERENCES godowns(id),
  to_godown     BIGINT NOT NULL REFERENCES godowns(id),
  note          TEXT,
  created_by    BIGINT REFERENCES users(id),
  created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
  CHECK (from_godown <> to_godown),
  UNIQUE (tenant_id, number)
);
CREATE TABLE transfer_lines (
  transfer_id BIGINT NOT NULL REFERENCES stock_transfers(id),
  product_id  BIGINT NOT NULL REFERENCES products(id),
  qty         INT NOT NULL CHECK (qty > 0),
  batch_no    TEXT
);

-- ---------- live location ----------
CREATE TABLE location_pings (
  id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  tenant_id   BIGINT NOT NULL REFERENCES tenants(id),
  user_id     BIGINT NOT NULL REFERENCES users(id),
  lat         DOUBLE PRECISION NOT NULL CHECK (lat BETWEEN -90 AND 90),
  lng         DOUBLE PRECISION NOT NULL CHECK (lng BETWEEN -180 AND 180),
  accuracy_m  DOUBLE PRECISION,
  recorded_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_pings_user_time ON location_pings (tenant_id, user_id, recorded_at DESC);

-- ---------- users: phone for WhatsApp alerts ----------
ALTER TABLE users ADD COLUMN phone TEXT;

-- ---------- tenants: Tally pull bookkeeping ----------
ALTER TABLE tenants ADD COLUMN tally_last_pull_at TIMESTAMPTZ, ADD COLUMN tally_last_pull_summary TEXT;

-- ---------- messages: machine-read text from voice notes and photos ----------
ALTER TABLE messages ADD COLUMN media_text TEXT, ADD COLUMN media_status TEXT
  CHECK (media_status IN ('PENDING','DONE','FAILED','SKIPPED'));

-- ---------- Tally: purchase vouchers ----------
ALTER TABLE tally_outbox DROP CONSTRAINT tally_outbox_voucher_type_check;
ALTER TABLE tally_outbox ADD CONSTRAINT tally_outbox_voucher_type_check CHECK (voucher_type IN ('SALES','RECEIPT','PURCHASE'));
