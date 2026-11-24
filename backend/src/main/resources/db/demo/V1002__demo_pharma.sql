-- Second demo tenant: a pharma distributor with batch/expiry tracking, two godowns and drug-licence checks.
-- Logins: owner@pharma.demo.in, sales@pharma.demo.in (password Demo@1234). All names fictional.

INSERT INTO tenants (name, gstin, wa_phone_number_id) VALUES ('Sai Medico Distributors', '27PQRSX5678K1Z9', 'demo-pharma-phone-id');

INSERT INTO godowns (tenant_id, code, name, address, is_default)
SELECT t.id, g.code, g.name, g.addr, g.def FROM tenants t,
  (VALUES ('MAIN','Main store','Shop 3, Market Yard, Pune',TRUE), ('COLD','Cold room (2-8 °C)','Shop 3 rear, Market Yard, Pune',FALSE)) g(code,name,addr,def)
WHERE t.name = 'Sai Medico Distributors';

INSERT INTO users (tenant_id, email, password_hash, name, role, monthly_target, beat)
SELECT t.id, u.email, '$2a$10$AvMnoxKfPR2jDjbJI9jDp..Wsy3x6jDRlstmEM0a9ILGvL1zKaiMO', u.name, u.role, u.target, u.beat
FROM tenants t, (VALUES
  ('owner@pharma.demo.in','Sai Patwardhan','OWNER',0,NULL),
  ('sales@pharma.demo.in','Kiran More','SALES',1500000,'Kothrud – Karve Nagar – Warje')) u(email,name,role,target,beat)
WHERE t.name = 'Sai Medico Distributors';

INSERT INTO price_tiers (tenant_id, code, name, discount_pct)
SELECT t.id, x.code, x.name, x.d FROM tenants t, (VALUES ('R','Retail chemist',10),('H','Hospital',14)) x(code,name,d)
WHERE t.name = 'Sai Medico Distributors';

INSERT INTO products (tenant_id, sku, name, unit, category, list_rate, gst_rate, reorder_level, match_patterns, batch_tracked, requires_licence, hsn)
SELECT t.id, p.sku, p.name, p.unit, p.cat, p.rate, p.gst, p.ro, p.pats, TRUE, p.lic, p.hsn FROM tenants t, (VALUES
 ('PCM650','Paracetamol 650 mg (strip of 15)','strip','Analgesic',30.50,12,500, ARRAY['paracetamol\s*650','pcm\s*650','dolo\s*650'], FALSE,'3004'),
 ('AMX500','Amoxicillin 500 mg (strip of 10)','strip','Antibiotic',92.00,12,200, ARRAY['amox(icillin)?\s*500'], TRUE,'3004'),
 ('AZI500','Azithromycin 500 mg (strip of 3)','strip','Antibiotic',71.00,12,150, ARRAY['azi(thro(mycin)?)?\s*500'], TRUE,'3004'),
 ('PAN40','Pantoprazole 40 mg (strip of 15)','strip','Gastro',105.00,12,200, ARRAY['panto(prazole)?\s*40','pan\s*40'], TRUE,'3004'),
 ('ORS','ORS sachet 21 g (box of 25)','box','OTC',230.00,12,60, ARRAY['\bors\b'], FALSE,'3004'),
 ('INS30','Insulin 30/70 (10 ml vial)','vial','Diabetes',145.00,5,40, ARRAY['insulin(\s*30\s*/?\s*70)?'], TRUE,'3004')
) p(sku,name,unit,cat,rate,gst,ro,pats,lic,hsn)
WHERE t.name = 'Sai Medico Distributors';

-- Batches: one expired, some near expiry, most fresh
INSERT INTO stock_batches (tenant_id, product_id, godown_id, batch_no, expiry_date, mfg_date, mrp, on_hand)
SELECT p.tenant_id, p.id, g.id, b.batch, CURRENT_DATE + b.days, CURRENT_DATE + b.days - 730, p.list_rate * 1.25, b.qty
FROM (VALUES
  ('PCM650','MAIN','PC24A11',-20,120),('PCM650','MAIN','PC25B07',45,600),('PCM650','MAIN','PC25D19',400,1500),
  ('AMX500','MAIN','AM25C02',75,180),('AMX500','MAIN','AM26A10',540,400),
  ('AZI500','MAIN','AZ25E14',300,220),
  ('PAN40','MAIN','PN25F01',30,90),('PAN40','MAIN','PN26B22',600,500),
  ('ORS','MAIN','OR25K03',250,140),
  ('INS30','COLD','IN25H30',60,35),('INS30','COLD','IN26C05',330,80)
) b(sku,gcode,batch,days,qty)
JOIN products p ON p.sku = b.sku
JOIN godowns g ON g.tenant_id = p.tenant_id AND g.code = b.gcode
WHERE p.tenant_id = (SELECT id FROM tenants WHERE name = 'Sai Medico Distributors');

INSERT INTO stock (product_id, tenant_id, godown_id, on_hand)
SELECT product_id, tenant_id, godown_id, sum(on_hand) FROM stock_batches
WHERE tenant_id = (SELECT id FROM tenants WHERE name = 'Sai Medico Distributors')
GROUP BY product_id, tenant_id, godown_id;

INSERT INTO customers (tenant_id, name, town, phone, tier_id, credit_limit, salesperson_id, drug_licence_no, drug_licence_expiry)
SELECT t.id, c.name, c.town, c.phone, pt.id, c.lim, u.id, c.dl, CURRENT_DATE + c.dl_days
FROM tenants t
JOIN (VALUES
 ('Shree Ganesh Medical','Kothrud','919890500001','R',150000,'MH-PZ3-112233',400),
 ('Lifeline Chemists','Karve Nagar','919890500002','R',100000,'MH-PZ3-445566',-10),
 ('Sahyadri Nursing Home','Warje','919890500003','H',400000,'MH-PZ2-778899',700)
) AS c(name,town,phone,tier,lim,dl,dl_days) ON TRUE
JOIN price_tiers pt ON pt.tenant_id = t.id AND pt.code = c.tier
JOIN users u ON u.email = 'sales@pharma.demo.in'
WHERE t.name = 'Sai Medico Distributors';

INSERT INTO invoices (tenant_id, number, customer_id, invoice_date, amount)
SELECT c.tenant_id, i.no, c.id, CURRENT_DATE - i.age, i.amt FROM customers c
JOIN (VALUES ('919890500001',7001,22,18400),('919890500002',7002,48,26750),('919890500003',7003,12,96200)) i(phone,no,age,amt)
ON i.phone = c.phone;

INSERT INTO vehicles (tenant_id, reg_no, type, driver_name, driver_phone)
SELECT t.id, 'MH12 XY 7788', 'Electric scooter', 'Vikas Gaikwad', '919850077001' FROM tenants t WHERE t.name = 'Sai Medico Distributors';

INSERT INTO suppliers (tenant_id, name, phone, email, gstin, lead_days)
SELECT t.id, 'Medisupply C&F Agency', '919821066600', 'cfa@medisupply.example', '27AAACM6666D1Z6', 2
FROM tenants t WHERE t.name = 'Sai Medico Distributors';

INSERT INTO doc_counters (tenant_id, kind, next_value)
SELECT t.id, k.kind, k.n FROM tenants t, (VALUES ('SO',501),('INV',7004),('RCPT',101),('VCH',901),('PO',41),('GRN',61),('TRF',5)) k(kind,n)
WHERE t.name = 'Sai Medico Distributors';

INSERT INTO conversations (tenant_id, customer_id, phone, status, last_message_at)
SELECT c.tenant_id, c.id, c.phone, 'NEW', now() - interval '6 minutes' FROM customers c
WHERE c.phone IN ('919890500001','919890500002');

INSERT INTO messages (tenant_id, conversation_id, direction, body, created_at)
SELECT cv.tenant_id, cv.id, 'IN', m.body, cv.last_message_at FROM conversations cv JOIN (VALUES
 ('919890500001', E'Namaste, please send\nDolo 650 - 40 strips\nAmox 500 x 20\nPan 40 - 15\nORS 2 box'),
 ('919890500002', E'Azithro 500 10 strips\nParacetamol 650 30')
) m(phone, body) ON m.phone = cv.phone;

INSERT INTO activity_log (tenant_id, action, message)
SELECT t.id, 'SEED', 'Demo pharma data loaded' FROM tenants t WHERE t.name = 'Sai Medico Distributors';
