"""Rebuilds the SQL statements that Java assembles from several pieces (godown filters, shared fragments) and
PREPAREs each variant against Postgres. Usage: python3 check_dynamic_sql.py <java_src_dir> <dsn>"""
import re, subprocess, sys, pathlib

src, dsn = pathlib.Path(sys.argv[1]), sys.argv[2]
def tb(text, after):  # first text block after a marker
    i = text.index(after); s = text.index('"""', i) + 4; e = text.index('"""', s); return text[s:e]

stock = (src / "com/distributoros/catalog/StockService.java").read_text()
FREE = tb(stock, "FREE_SQL =").replace("%s", "{g}")
free_in = lambda g: FREE.replace("{g}", g)

prod = (src / "com/distributoros/catalog/ProductController.java").read_text()
inv = prod[prod.index('public List<Map<String, Object>> inventory'):]
parts = re.findall(r'"""\n(.*?)"""', inv[:inv.index('.param("t", u.tenantId());')], re.S)
# parts: [allGodownsFree, base1, base2, base3, base4(after freeExpr), base5(after expired filter)]
all_free = parts[0]
def inventory(godown):
    g = " AND s.godown_id = :g" if godown else ""
    fe = free_in(":g") if godown else all_free
    return parts[1] + g + parts[2] + g + parts[3] + fe + parts[4] + (" AND b.godown_id = :g" if godown else "") + parts[5]

pricing = (src / "com/distributoros/customers/PricingService.java").read_text()
pp = re.findall(r'"""\n(.*?)"""', pricing[pricing.index("List<Map<String, Object>> rows"):], re.S)
pricing_sql = pp[0] + free_in(":g") + pp[1]

orders = (src / "com/distributoros/orders/OrderController.java").read_text()
ob = tb(orders, "var stmt = db.sql(")
order_variants = [ob + f + " ORDER BY o.number DESC LIMIT :lim" for f in
                  ["", " AND o.status IN ('CONFIRMED','PACKED','DISPATCHED')", " AND o.status = :st",
                   " AND o.status = :st AND o.status IN ('PACKED','DISPATCHED','DELIVERED')"]]
inbox = (src / "com/distributoros/inbox/InboxService.java").read_text()
ib = re.findall(r'"""\n(.*?)"""', inbox[inbox.index('String spFilter'):], re.S)
inbox_variants = [ib[0] + f + ib[1] for f in ["", " AND (c.salesperson_id = :uid OR c.id IS NULL)"]]
cust = (src / "com/distributoros/customers/CustomerController.java").read_text()
cl = tb(cust, "LIST_SQL =")
cust_variants = [cl + q + " ORDER BY c.name LIMIT 500" for q in ["", " AND (c.name ILIKE :q OR c.phone LIKE :q OR c.town ILIKE :q)"]]
field = (src / "com/distributoros/field/FieldController.java").read_text()
fv = tb(field, 'String mine =')
field_variants = [fv + m + " ORDER BY v.checked_in_at DESC LIMIT 200" for m in ["", " AND v.user_id = :u"]]
pur = (src / "com/distributoros/purchases/PurchaseController.java").read_text()
pv = tb(pur, "var q = db.sql(")
pur_variants = [pv + f + " ORDER BY po.number DESC LIMIT 200" for f in ["", " AND po.status IN ('DRAFT','SENT','PARTIAL')", " AND po.status = :st"]]

checks = [("inventory all godowns", inventory(False)), ("inventory one godown", inventory(True)), ("pricing quote", pricing_sql)]
checks += [(f"orders list {i}", s) for i, s in enumerate(order_variants)] + [(f"inbox list {i}", s) for i, s in enumerate(inbox_variants)]
checks += [(f"customers list {i}", s) for i, s in enumerate(cust_variants)] + [(f"visits {i}", s) for i, s in enumerate(field_variants)]
checks += [(f"purchases list {i}", s) for i, s in enumerate(pur_variants)]

TYPES = {"t": "bigint", "g": "bigint", "c": "bigint", "u": "bigint", "uid": "bigint", "lim": "int", "st": "text", "q": "text", "ids": "bigint"}
bad = 0
for name, sql in checks:
    sql = sql.replace("\\\\", "\\")
    names = []
    def rep(m):
        n = m.group(1)
        if n not in names: names.append(n)
        return f"${names.index(n) + 1}"
    parts_ = re.split(r"('(?:[^']|'')*')", sql)
    sql2 = "".join(p if p.startswith("'") else re.sub(r'(?<!:):([A-Za-z_]\w*)', rep, p) for p in parts_)
    types = ",".join(TYPES.get(n, "text") for n in names)
    r = subprocess.run(["psql", dsn, "-v", "ON_ERROR_STOP=1", "-q", "-c", f"BEGIN; PREPARE x ({types}) AS {sql2}; ROLLBACK;"], capture_output=True, text=True)
    if r.returncode: bad += 1; print(f"FAIL {name}: {r.stderr.strip()}")
print(f"{len(checks) - bad}/{len(checks)} assembled statements OK")
sys.exit(1 if bad else 0)
