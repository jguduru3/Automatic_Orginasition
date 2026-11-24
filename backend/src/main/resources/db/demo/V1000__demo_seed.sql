-- Demo tenant: fictional Pune electrical distributor. Loaded only when FLYWAY_LOCATIONS includes classpath:db/demo.
-- All users share the password Demo@1234 (change before any real use).

INSERT INTO tenants (name, gstin, wa_phone_number_id) VALUES ('Shree Datta Electricals', '27ABCDE1234F1Z5', 'demo-phone-id');

INSERT INTO godowns (tenant_id, code, name, address, is_default)
SELECT t.id, g.code, g.name, g.addr, g.def FROM tenants t,
  (VALUES ('MAIN','Hadapsar godown','Plot 14, Hadapsar Industrial Estate, Pune',TRUE),
          ('CHK','Chakan branch','Gat 220, Chakan MIDC',FALSE)) g(code,name,addr,def)
WHERE t.name = 'Shree Datta Electricals';

INSERT INTO users (tenant_id, email, password_hash, name, role, monthly_target, beat)
SELECT t.id, u.email, '$2a$10$AvMnoxKfPR2jDjbJI9jDp..Wsy3x6jDRlstmEM0a9ILGvL1zKaiMO', u.name, u.role, u.target, u.beat
FROM tenants t, (VALUES
  ('owner@demo.in','Datta Kulkarni','OWNER',0,NULL),
  ('accounts@demo.in','Priya Joshi','ACCOUNTANT',0,NULL),
  ('amit@demo.in','Amit Jadhav','SALES',2400000,'Hadapsar – Kharadi – Wagholi'),
  ('neha@demo.in','Neha Kulkarni','SALES',2000000,'Pimpri – Chinchwad – Chakan'),
  ('rohan@demo.in','Rohan Deshmukh','SALES',3200000,'Projects & contractors'),
  ('raju@demo.in','Raju Pawar','DELIVERY',0,NULL)
) AS u(email,name,role,target,beat)
WHERE t.name = 'Shree Datta Electricals';

INSERT INTO price_tiers (tenant_id, code, name, discount_pct)
SELECT t.id, x.code, x.name, x.d FROM tenants t, (VALUES ('A','Tier A',8),('B','Tier B',5),('C','Tier C',0)) x(code,name,d);

INSERT INTO products (tenant_id, sku, name, unit, category, list_rate, reorder_level, match_patterns)
SELECT t.id, p.sku, p.name, p.unit, p.cat, p.rate, p.reorder, p.pats FROM tenants t, (VALUES
 ('W15','FR House Wire 1.5 sq mm','coil (90 m)','Wires',1450,80, ARRAY['1\.5\s*(sq\s*mm|sqmm|mm)?\s*wire','wire\s*1\.5(\s*(sq\s*mm|mm))?']),
 ('W25','FR House Wire 2.5 sq mm','coil (90 m)','Wires',2350,60, ARRAY['2\.5\s*(sq\s*mm|sqmm|mm)?\s*wire','wire\s*2\.5(\s*(sq\s*mm|mm))?']),
 ('W40','FR House Wire 4 sq mm','coil (90 m)','Wires',3650,25, ARRAY['(?<![.\d])4\s*(sq\s*mm|sqmm|mm)(\s*wire)?']),
 ('M16','MCB 16A SP C-curve','pc','Switchgear',165,200, ARRAY['16\s*a\s*(sp\s*)?mcb','mcb\s*16\s*a']),
 ('M32','MCB 32A SP C-curve','pc','Switchgear',180,200, ARRAY['32\s*a\s*(sp\s*)?mcb','mcb\s*32\s*a']),
 ('M63','MCB 63A DP','pc','Switchgear',720,30, ARRAY['63\s*a\s*(dp\s*)?mcb','mcb\s*63\s*a(\s*dp)?']),
 ('RC40','RCCB 40A 4P 30mA','pc','Switchgear',2650,30, ARRAY['rccb(\s*40\s*a)?(\s*4\s*p(ole)?)?']),
 ('DB8','Distribution Board 8-way DP','pc','Switchgear',1850,15, ARRAY['db\s*8\s*way','8\s*way\s*db','distribution\s*board']),
 ('L9','LED Bulb 9W (pack of 10)','pkt','Lighting',650,100, ARRAY['led\s*(bulb\s*)?9\s*w','9\s*w\s*(led|bulb)']),
 ('LP15','LED Panel 15W Round','pc','Lighting',310,80, ARRAY['led\s*panel(\s*15\s*w)?','panel\s*(light\s*)?15\s*w']),
 ('F12','Ceiling Fan 1200 mm','pc','Fans',1890,30, ARRAY['ceiling\s*fan','fan\s*1200\s*(mm)?','1200\s*(mm)?\s*fan']),
 ('FEX','Exhaust Fan 200 mm','pc','Fans',980,20, ARRAY['exhaust(\s*fan)?(\s*200\s*mm)?']),
 ('SW6','Modular Switch 6A (box of 20)','box','Wiring devices',560,40, ARRAY['6\s*a\s*switch(es)?','switch(es)?\s*6\s*a','modular\s*switch']),
 ('SK16','Modular Socket 6/16A (box of 10)','box','Wiring devices',890,25, ARRAY['(6\s*/\s*)?16\s*a\s*socket','socket(\s*16\s*a)?']),
 ('CD20','PVC Conduit 20 mm (bundle of 10)','bundle','Accessories',420,60, ARRAY['conduit(\s*pipe)?(\s*20\s*mm)?','pvc\s*pipe\s*20'])
) AS p(sku,name,unit,cat,rate,reorder,pats);

INSERT INTO stock (product_id, tenant_id, godown_id, on_hand)
SELECT p.id, p.tenant_id, (SELECT id FROM godowns g WHERE g.tenant_id = p.tenant_id AND g.is_default), s.qty FROM products p JOIN (VALUES
 ('W15',320),('W25',210),('W40',60),('M16',900),('M32',640),('M63',85),('RC40',22),('DB8',40),
 ('L9',480),('LP15',260),('F12',95),('FEX',14),('SW6',150),('SK16',70),('CD20',300)) s(sku,qty) ON s.sku = p.sku;

-- branch stock
INSERT INTO stock (product_id, tenant_id, godown_id, on_hand)
SELECT p.id, p.tenant_id, (SELECT id FROM godowns g WHERE g.tenant_id = p.tenant_id AND g.code = 'CHK'), s.qty FROM products p JOIN (VALUES
 ('W15',60),('M16',150),('RC40',10),('FEX',12),('L9',80)) s(sku,qty) ON s.sku = p.sku;

INSERT INTO suppliers (tenant_id, name, phone, email, gstin, lead_days)
SELECT t.id, s.name, s.phone, s.email, s.gstin, s.lead FROM tenants t, (VALUES
 ('Volt Cables Pvt Ltd','919820011100','orders@voltcables.example','27AAACV1111A1Z1',4),
 ('Sure Switchgear Distributors','919822022200','sales@sureswitch.example','27AAACS2222B1Z2',2),
 ('Brightline Lighting','919823033300','po@brightline.example','27AAACB3333C1Z3',5)) s(name,phone,email,gstin,lead)
WHERE t.name = 'Shree Datta Electricals';

INSERT INTO customers (tenant_id, name, town, phone, tier_id, credit_limit, salesperson_id)
SELECT t.id, c.name, c.town, c.phone, pt.id, c.lim, u.id
FROM tenants t
JOIN (VALUES
 ('Mahalaxmi Electricals','Hadapsar','919822041001','A',500000,'amit@demo.in'),
 ('Om Sai Electric & Hardware','Kharadi','919890112002','B',200000,'amit@demo.in'),
 ('Patil Electricals','Wagholi','919766030003','B',150000,'amit@demo.in'),
 ('New India Lighting','Camp','919823077004','A',400000,'neha@demo.in'),
 ('Shiv Shakti Traders','Chakan','919011054005','C',100000,'neha@demo.in'),
 ('Kumar Contractors (Baner project)','Baner','919881066006','A',800000,'rohan@demo.in'),
 ('Royal Switchgear Centre','Pimpri','919860521007','B',250000,'neha@demo.in'),
 ('Ganesh Hardware','Talegaon','919527088008','C',80000,'neha@demo.in')
) AS c(name,town,phone,tier,lim,sp) ON TRUE
JOIN price_tiers pt ON pt.tenant_id = t.id AND pt.code = c.tier
JOIN users u ON lower(u.email) = c.sp;

INSERT INTO customer_prices (customer_id, product_id, rate)
SELECT c.id, p.id, x.rate FROM customers c JOIN products p ON p.tenant_id = c.tenant_id
JOIN (VALUES ('RC40',2450),('M63',660)) x(sku,rate) ON x.sku = p.sku
WHERE c.phone = '919881066006';

INSERT INTO vehicles (tenant_id, reg_no, type, driver_name, driver_user_id, driver_phone)
SELECT t.id, v.reg, v.type, v.driver, (SELECT id FROM users WHERE email = v.email), v.phone
FROM tenants t, (VALUES ('MH12 AB 4521','Tata Ace','Raju Pawar','raju@demo.in','919850044401'),
                        ('MH12 CD 8890','Bolero Pickup','Sameer Shaikh',NULL,'919850044402'),
                        ('MH14 EF 1102','Piaggio Ape','Imran Khan',NULL,'919850044403')) v(reg,type,driver,email,phone)
WHERE t.name = 'Shree Datta Electricals';

-- Opening invoices (historic, already synced to Tally)
INSERT INTO invoices (tenant_id, number, customer_id, invoice_date, amount)
SELECT c.tenant_id, i.no, c.id, CURRENT_DATE - i.age, i.amt
FROM customers c JOIN (VALUES
 ('919822041001',2238,41,86400),('919822041001',2281,18,124500),('919822041001',2304,6,58200),
 ('919890112002',2266,27,64300),('919890112002',2299,9,38900),
 ('919766030003',2252,34,42100),
 ('919823077004',2219,52,71800),('919823077004',2290,14,96300),
 ('919011054005',2171,96,24600),('919011054005',2195,74,23400),('919011054005',2273,22,44000),
 ('919881066006',2208,63,212000),('919881066006',2287,16,184500),
 ('919860521007',2244,38,58800),('919860521007',2301,7,47600),
 ('919527088008',2262,29,31200)
) i(phone,no,age,amt) ON i.phone = c.phone;

INSERT INTO doc_counters (tenant_id, kind, next_value)
SELECT t.id, k.kind, k.n FROM tenants t, (VALUES ('SO',1037),('INV',2305),('RCPT',801),('VCH',5121),('PO',301),('GRN',501),('TRF',11)) k(kind,n)
WHERE t.name = 'Shree Datta Electricals';

-- Open orders that hold stock
WITH o AS (
  INSERT INTO sales_orders (tenant_id, number, customer_id, source, status, salesperson_id, subtotal, tax, total, created_at, godown_id)
  SELECT c.tenant_id, x.no, c.id, x.src, x.st, c.salesperson_id, 0, 0, 0, now() - interval '2 hours',
         (SELECT id FROM godowns g WHERE g.tenant_id = c.tenant_id AND g.is_default)
  FROM customers c JOIN (VALUES
    (1034,'919766030003','WHATSAPP','PACKED'),
    (1035,'919527088008','SALESPERSON','CONFIRMED'),
    (1036,'919860521007','WHATSAPP','CONFIRMED')) x(no,phone,src,st) ON x.phone = c.phone
  RETURNING id, number, customer_id
)
INSERT INTO order_lines (order_id, product_id, qty, rate, gst_rate, amount)
SELECT o.id, p.id, l.qty, r.rate, p.gst_rate, r.rate * l.qty
FROM o JOIN (VALUES
  (1034,'W15',8),(1034,'W25',4),(1034,'M32',30),
  (1035,'SW6',6),(1035,'SK16',4),(1035,'CD20',10),
  (1036,'RC40',6),(1036,'DB8',4),(1036,'M16',40)) l(no,sku,qty) ON l.no = o.number
JOIN customers c ON c.id = o.customer_id
JOIN products p ON p.tenant_id = c.tenant_id AND p.sku = l.sku
CROSS JOIN LATERAL (
  SELECT COALESCE(cp.rate, round(p.list_rate * (1 - COALESCE(pt.discount_pct,0)/100))) AS rate
  FROM (SELECT 1) one
  LEFT JOIN customer_prices cp ON cp.customer_id = c.id AND cp.product_id = p.id
  LEFT JOIN price_tiers pt ON pt.id = c.tier_id
) r;

UPDATE sales_orders so SET subtotal = t.sub, tax = t.tax, total = t.sub + t.tax
FROM (SELECT order_id, sum(amount) sub, round(sum(amount * gst_rate / 100), 2) tax FROM order_lines GROUP BY order_id) t
WHERE t.order_id = so.id;

UPDATE stock s SET reserved = r.q
FROM (SELECT l.product_id, o.godown_id, sum(l.qty - l.backorder_qty) q FROM order_lines l JOIN sales_orders o ON o.id = l.order_id
      WHERE o.status IN ('CONFIRMED','PACKED') GROUP BY l.product_id, o.godown_id) r
WHERE r.product_id = s.product_id AND r.godown_id = s.godown_id;

-- a purchase order already sent to the switchgear supplier for the short RCCBs
WITH po AS (
  INSERT INTO purchase_orders (tenant_id, number, supplier_id, godown_id, status, expected_date, sent_at, created_by)
  SELECT t.id, 300, s.id, g.id, 'SENT', CURRENT_DATE + 2, now() - interval '1 day', u.id
  FROM tenants t JOIN suppliers s ON s.tenant_id = t.id AND s.name = 'Sure Switchgear Distributors'
  JOIN godowns g ON g.tenant_id = t.id AND g.is_default JOIN users u ON u.email = 'owner@demo.in'
  RETURNING id
)
INSERT INTO po_lines (po_id, product_id, qty, rate, gst_rate)
SELECT po.id, p.id, x.qty, x.rate, 18 FROM po CROSS JOIN (VALUES ('RC40',40,2100),('FEX',30,760)) x(sku,qty,rate)
JOIN products p ON p.sku = x.sku;
UPDATE purchase_orders po SET subtotal = t.s, tax = round(t.s * 0.18, 2), total = t.s + round(t.s * 0.18, 2)
FROM (SELECT po_id, sum(qty * rate) s FROM po_lines GROUP BY po_id) t WHERE t.po_id = po.id;

-- Inbox: four new WhatsApp orders and one converted one
INSERT INTO conversations (tenant_id, customer_id, phone, status, last_message_at)
SELECT c.tenant_id, c.id, c.phone, x.st, now() - (x.mins || ' minutes')::interval
FROM customers c JOIN (VALUES ('919822041001','NEW',25),('919011054005','NEW',16),('919881066006','NEW',9),('919890112002','NEW',5),('919766030003','CONVERTED',140)) x(phone,st,mins)
ON x.phone = c.phone;

UPDATE conversations cv SET order_id = so.id FROM sales_orders so WHERE so.number = 1034 AND cv.phone = '919766030003' AND so.tenant_id = cv.tenant_id;

INSERT INTO messages (tenant_id, conversation_id, direction, body, kind, created_at)
SELECT cv.tenant_id, cv.id, m.dir, m.body, m.kind, cv.last_message_at
FROM conversations cv JOIN (VALUES
 ('919822041001','IN','TEXT', E'Good morning bhaiya, today''s order:\n1.5 wire - 20 coil\n2.5 wire - 10 coil\n32A MCB - 100 pcs\nRCCB 40A - 4\nLED 9W - 15 pkt\nPlease send by evening'),
 ('919011054005','IN','TEXT', E'Sir urgent\n2 fan 1200mm\n1.5 wire 5 coil\nexhaust fan 6 pcs\nPayment next week pakka'),
 ('919881066006','IN','TEXT', E'Site order Baner project:\nRCCB 40A 4 pole x 30\nDB 8 way - 12\n63A DP MCB 40 nos\n4 sq mm wire 15 coil\nconduit 20mm 25 bundle'),
 ('919890112002','IN','VOICE', 'Need 6A switch 10 box, 16A socket 5 box, LED panel 15W 40 pcs and changeover switch 63A x 2'),
 ('919766030003','IN','TEXT', E'1.5 wire 8 coil\n2.5 wire 4 coil\n32A MCB 30')
) m(phone,dir,kind,body) ON m.phone = cv.phone;

INSERT INTO tally_outbox (tenant_id, voucher_type, ref, party, amount, payload)
SELECT c.tenant_id, 'SALES', 'INV-2304', c.name, 58200, '<ENVELOPE/>' FROM customers c WHERE c.phone = '919822041001';

INSERT INTO activity_log (tenant_id, action, message, at)
SELECT t.id, 'SEED', 'Demo data loaded', now() FROM tenants t;
