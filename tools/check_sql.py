"""Extracts every SQL statement from the Java sources and PREPAREs it against a live Postgres (validates syntax,
table/column names and types without executing). Usage: python3 check_sql.py <java_src_dir> <psql dsn>"""
import re, subprocess, sys, pathlib

src, dsn = sys.argv[1], sys.argv[2]
stmts = []
for f in pathlib.Path(src).rglob("*.java"):
    code = f.read_text()
    # text blocks passed to .sql( or assigned to *_SQL constants
    for m in re.finditer(r'(?:\.sql\(|_SQL\s*=)\s*"""\n(.*?)"""', code, re.S):
        stmts.append((f.name, m.group(1)))
    for m in re.finditer(r'\.sql\("((?:[^"\\]|\\.)*)"\)', code):
        stmts.append((f.name, m.group(1).replace('\\"', '"')))
    # one(...)/many(...) helpers in DashboardController
    for m in re.finditer(r'(?:one|many)\("""\n(.*?)"""', code, re.S):
        stmts.append((f.name, m.group(1)))

def to_prepared(sql):
    sql = sql.replace("\\\\", "\\")
    names = []
    def rep(m):
        n = m.group(1)
        if n not in names: names.append(n)
        return f"${names.index(n)+1}"
    parts = re.split(r"('(?:[^']|'')*')", sql)  # leave quoted literals alone, like Spring does
    out = [pt if pt.startswith("'") else re.sub(r'(?<!:):([A-Za-z_][A-Za-z0-9_]*)', rep, pt) for pt in parts]
    return "".join(out), names

ok = bad = 0
for i, (fname, sql) in enumerate(stmts):
    p, names = to_prepared(sql)
    script = f"BEGIN; PREPARE q{i} AS {p.strip().rstrip(';')}; ROLLBACK;"
    r = subprocess.run(["psql", dsn, "-v", "ON_ERROR_STOP=1", "-q", "-c", script], capture_output=True, text=True)
    if r.returncode != 0:
        bad += 1
        print(f"--- FAIL {fname}\n{sql.strip()[:300]}\n>> {r.stderr.strip()}\n")
    else:
        ok += 1
print(f"{ok} statements OK, {bad} failed")
sys.exit(1 if bad else 0)
