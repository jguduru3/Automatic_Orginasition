-- Same statement as DocNumbers.next inside a transaction that also writes the number.
BEGIN;
INSERT INTO doc_counters (tenant_id, kind, next_value) VALUES (1, 'STRESS', 2)
ON CONFLICT (tenant_id, kind) DO UPDATE SET next_value = doc_counters.next_value + 1
RETURNING next_value - 1 AS n \gset
INSERT INTO _stress_numbers (n) VALUES (:n);
END;
