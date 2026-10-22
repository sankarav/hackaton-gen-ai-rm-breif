-- Demo client 4: retirement-planning scenario with a CD maturing soon.
-- Idempotent so it also applies cleanly where David Okafor was already inserted by hand.

INSERT INTO client (client_id, full_name, segment, relationship_start, rm_name)
VALUES ('client_004', 'David Okafor', 'Private', '2020-06-01', 'Sarah Williams')
ON CONFLICT (client_id) DO NOTHING;

INSERT INTO interaction (client_id, meeting_date, notes, promises)
SELECT 'client_004', DATE '2026-08-15', 'Annual review. Considering early retirement in 2028.',
       '["Send retirement income projection"]'
WHERE NOT EXISTS (SELECT 1 FROM interaction WHERE client_id = 'client_004' AND meeting_date = DATE '2026-08-15');

INSERT INTO synthetic_product (client_id, product_type, balance, maturity_date, rate)
SELECT 'client_004', 'CD', 80000.00, DATE '2026-11-30', 0.0450
WHERE NOT EXISTS (SELECT 1 FROM synthetic_product WHERE client_id = 'client_004' AND product_type = 'CD');
