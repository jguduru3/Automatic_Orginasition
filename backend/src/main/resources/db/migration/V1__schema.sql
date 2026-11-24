-- DistributorOS core schema. Every business table carries tenant_id; every query filters on it.

CREATE TABLE tenants (
  id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  name          TEXT NOT NULL,
  gstin         TEXT,
  wa_phone_number_id TEXT UNIQUE,          -- Meta WhatsApp Cloud API phone number id routed to this tenant
  created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE users (
  id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  tenant_id     BIGINT NOT NULL REFERENCES tenants(id),
  email         TEXT NOT NULL,
  password_hash TEXT NOT NULL,
  name          TEXT NOT NULL,
  role          TEXT NOT NULL CHECK (role IN ('OWNER','ACCOUNTANT','SALES','DELIVERY')),
  active        BOOLEAN NOT NULL DEFAULT TRUE,
  monthly_target NUMERIC(14,2) NOT NULL DEFAULT 0,
  beat          TEXT,
  created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX ux_users_email ON users (lower(email));

CREATE TABLE price_tiers (
  id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  tenant_id     BIGINT NOT NULL REFERENCES tenants(id),
  code          TEXT NOT NULL,
  name          TEXT NOT NULL,
  discount_pct  NUMERIC(5,2) NOT NULL DEFAULT 0 CHECK (discount_pct >= 0 AND discount_pct < 100),
  UNIQUE (tenant_id, code)
);

CREATE TABLE products (
  id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  tenant_id     BIGINT NOT NULL REFERENCES tenants(id),
  sku           TEXT NOT NULL,
  name          TEXT NOT NULL,
  unit          TEXT NOT NULL,
  category      TEXT NOT NULL,
  list_rate     NUMERIC(12,2) NOT NULL CHECK (list_rate >= 0),
  gst_rate      NUMERIC(5,2) NOT NULL DEFAULT 18,
  reorder_level INT NOT NULL DEFAULT 0 CHECK (reorder_level >= 0),
  match_patterns TEXT[] NOT NULL DEFAULT '{}',  -- case-insensitive regexes used by the WhatsApp parser
  active        BOOLEAN NOT NULL DEFAULT TRUE,
  UNIQUE (tenant_id, sku)
);

-- One row per product (single godown for v1). Reservation is an atomic conditional UPDATE.
CREATE TABLE stock (
  product_id    BIGINT PRIMARY KEY REFERENCES products(id),
  tenant_id     BIGINT NOT NULL REFERENCES tenants(id),
  on_hand       INT NOT NULL DEFAULT 0 CHECK (on_hand >= 0),
  reserved      INT NOT NULL DEFAULT 0 CHECK (reserved >= 0),
  updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT ck_reserved_le_on_hand CHECK (reserved <= on_hand)
);

CREATE TABLE customers (
  id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  tenant_id     BIGINT NOT NULL REFERENCES tenants(id),
  name          TEXT NOT NULL,
  town          TEXT,
  phone         TEXT NOT NULL,                 -- E.164 digits without '+', e.g. 919822041000
  gstin         TEXT,
  tier_id       BIGINT REFERENCES price_tiers(id),
  credit_limit  NUMERIC(14,2) NOT NULL DEFAULT 0 CHECK (credit_limit >= 0),
  salesperson_id BIGINT REFERENCES users(id),
  active        BOOLEAN NOT NULL DEFAULT TRUE,
  created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (tenant_id, phone)
);

CREATE TABLE customer_prices (
  customer_id   BIGINT NOT NULL REFERENCES customers(id),
  product_id    BIGINT NOT NULL REFERENCES products(id),
  rate          NUMERIC(12,2) NOT NULL CHECK (rate >= 0),
  PRIMARY KEY (customer_id, product_id)
);

CREATE TABLE conversations (
  id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  tenant_id     BIGINT NOT NULL REFERENCES tenants(id),
  customer_id   BIGINT REFERENCES customers(id),
  phone         TEXT NOT NULL,
  status        TEXT NOT NULL DEFAULT 'NEW' CHECK (status IN ('NEW','CONVERTED','IGNORED')),
  order_id      BIGINT,
  last_message_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (tenant_id, phone)
);
CREATE INDEX ix_conv_tenant_last ON conversations (tenant_id, last_message_at DESC);

CREATE TABLE messages (
  id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  tenant_id     BIGINT NOT NULL REFERENCES tenants(id),
  conversation_id BIGINT NOT NULL REFERENCES conversations(id),
  direction     TEXT NOT NULL CHECK (direction IN ('IN','OUT')),
  body          TEXT NOT NULL,
  kind          TEXT NOT NULL DEFAULT 'TEXT' CHECK (kind IN ('TEXT','VOICE','IMAGE')),
  media_blob    TEXT,
  wa_message_id TEXT,
  created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX ux_messages_wa_id ON messages (tenant_id, wa_message_id) WHERE wa_message_id IS NOT NULL;
CREATE INDEX ix_messages_conv ON messages (conversation_id, id);

CREATE TABLE vehicles (
  id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  tenant_id     BIGINT NOT NULL REFERENCES tenants(id),
  reg_no        TEXT NOT NULL,
  type          TEXT NOT NULL,
  driver_name   TEXT NOT NULL,
  driver_user_id BIGINT REFERENCES users(id),
  UNIQUE (tenant_id, reg_no)
);

-- Gap-free per-tenant document numbering (SO, INV, RCPT, VCH)
CREATE TABLE doc_counters (
  tenant_id     BIGINT NOT NULL REFERENCES tenants(id),
  kind          TEXT NOT NULL,
  next_value    BIGINT NOT NULL,
  PRIMARY KEY (tenant_id, kind)
);

CREATE TABLE sales_orders (
  id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  tenant_id     BIGINT NOT NULL REFERENCES tenants(id),
  number        BIGINT NOT NULL,
  customer_id   BIGINT NOT NULL REFERENCES customers(id),
  source        TEXT NOT NULL CHECK (source IN ('WHATSAPP','SALESPERSON','PHONE')),
  status        TEXT NOT NULL CHECK (status IN ('CONFIRMED','PACKED','DISPATCHED','DELIVERED','CANCELLED')),
  conversation_id BIGINT REFERENCES conversations(id),
  salesperson_id BIGINT REFERENCES users(id),
  created_by    BIGINT REFERENCES users(id),
  vehicle_id    BIGINT REFERENCES vehicles(id),
  subtotal      NUMERIC(14,2) NOT NULL,
  tax           NUMERIC(14,2) NOT NULL,
  total         NUMERIC(14,2) NOT NULL,
  credit_override_by BIGINT REFERENCES users(id),
  pod_blob      TEXT,
  created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
  packed_at     TIMESTAMPTZ,
  dispatched_at TIMESTAMPTZ,
  delivered_at  TIMESTAMPTZ,
  UNIQUE (tenant_id, number)
);
CREATE INDEX ix_orders_tenant_status ON sales_orders (tenant_id, status);
CREATE INDEX ix_orders_tenant_created ON sales_orders (tenant_id, created_at DESC);
CREATE INDEX ix_orders_customer ON sales_orders (customer_id, status);
ALTER TABLE conversations ADD CONSTRAINT fk_conv_order FOREIGN KEY (order_id) REFERENCES sales_orders(id);

CREATE TABLE order_lines (
  id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  order_id      BIGINT NOT NULL REFERENCES sales_orders(id),
  product_id    BIGINT NOT NULL REFERENCES products(id),
  qty           INT NOT NULL CHECK (qty > 0),
  backorder_qty INT NOT NULL DEFAULT 0 CHECK (backorder_qty >= 0),
  rate          NUMERIC(12,2) NOT NULL,
  gst_rate      NUMERIC(5,2) NOT NULL,
  amount        NUMERIC(14,2) NOT NULL
);
CREATE INDEX ix_lines_order ON order_lines (order_id);

CREATE TABLE invoices (
  id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  tenant_id     BIGINT NOT NULL REFERENCES tenants(id),
  number        BIGINT NOT NULL,
  customer_id   BIGINT NOT NULL REFERENCES customers(id),
  order_id      BIGINT UNIQUE REFERENCES sales_orders(id),
  invoice_date  DATE NOT NULL DEFAULT CURRENT_DATE,
  amount        NUMERIC(14,2) NOT NULL CHECK (amount >= 0),
  paid          NUMERIC(14,2) NOT NULL DEFAULT 0,
  CONSTRAINT ck_paid CHECK (paid >= 0 AND paid <= amount),
  UNIQUE (tenant_id, number)
);
CREATE INDEX ix_invoices_open ON invoices (tenant_id, customer_id) WHERE paid < amount;

CREATE TABLE payments (
  id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  tenant_id     BIGINT NOT NULL REFERENCES tenants(id),
  number        BIGINT NOT NULL,
  customer_id   BIGINT NOT NULL REFERENCES customers(id),
  amount        NUMERIC(14,2) NOT NULL CHECK (amount > 0),
  unapplied     NUMERIC(14,2) NOT NULL DEFAULT 0 CHECK (unapplied >= 0),
  mode          TEXT NOT NULL CHECK (mode IN ('UPI','NEFT','CHEQUE','CASH')),
  reference     TEXT,
  idempotency_key TEXT,
  received_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
  created_by    BIGINT REFERENCES users(id),
  UNIQUE (tenant_id, number)
);
CREATE UNIQUE INDEX ux_payments_idem ON payments (tenant_id, idempotency_key) WHERE idempotency_key IS NOT NULL;

CREATE TABLE payment_allocations (
  payment_id    BIGINT NOT NULL REFERENCES payments(id),
  invoice_id    BIGINT NOT NULL REFERENCES invoices(id),
  amount        NUMERIC(14,2) NOT NULL CHECK (amount > 0),
  PRIMARY KEY (payment_id, invoice_id)
);

CREATE TABLE visits (
  id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  tenant_id     BIGINT NOT NULL REFERENCES tenants(id),
  user_id       BIGINT NOT NULL REFERENCES users(id),
  customer_id   BIGINT REFERENCES customers(id),
  lat           DOUBLE PRECISION,
  lng           DOUBLE PRECISION,
  note          TEXT,
  checked_in_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_visits_user_day ON visits (tenant_id, user_id, checked_in_at DESC);

-- Transactional outbox: vouchers are written in the same transaction as the business change,
-- then posted to Tally by a worker using FOR UPDATE SKIP LOCKED.
CREATE TABLE tally_outbox (
  id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  tenant_id     BIGINT NOT NULL REFERENCES tenants(id),
  voucher_type  TEXT NOT NULL CHECK (voucher_type IN ('SALES','RECEIPT')),
  ref           TEXT NOT NULL,
  party         TEXT NOT NULL,
  amount        NUMERIC(14,2) NOT NULL,
  payload       TEXT NOT NULL,
  status        TEXT NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING','POSTED','FAILED')),
  attempts      INT NOT NULL DEFAULT 0,
  last_error    TEXT,
  tally_voucher_no TEXT,
  created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
  posted_at     TIMESTAMPTZ,
  UNIQUE (tenant_id, voucher_type, ref)
);
CREATE INDEX ix_outbox_pending ON tally_outbox (tenant_id, id) WHERE status = 'PENDING';

CREATE TABLE activity_log (
  id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  tenant_id     BIGINT NOT NULL REFERENCES tenants(id),
  user_id       BIGINT REFERENCES users(id),
  action        TEXT NOT NULL,
  message       TEXT NOT NULL,
  entity        TEXT,
  entity_id     BIGINT,
  at            TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_activity_tenant_at ON activity_log (tenant_id, at DESC);
