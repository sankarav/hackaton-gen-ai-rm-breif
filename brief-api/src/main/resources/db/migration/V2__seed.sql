-- Demo clients
-- client_001: Clean / no anomalies
-- client_002: HERO — large outflow + CD maturing in 3 weeks (the demo centrepiece)
-- client_003: Dormant — low activity, 60-day lapse since last meeting

INSERT INTO client (client_id, full_name, segment, relationship_start, rm_name)
VALUES
  ('client_001', 'Margaret Chen',    'Private',      '2019-03-15', 'Sarah Williams'),
  ('client_002', 'Robert Harrington','Mass Affluent', '2021-09-01', 'Sarah Williams'),
  ('client_003', 'Elena Vasquez',    'Mass Affluent', '2022-04-10', 'James Park');

-- Interactions (last_meeting_date drives the delta window)
-- client_001: 45 days ago
INSERT INTO interaction (client_id, meeting_date, notes, promises) VALUES
  ('client_001', '2026-05-03',
   'Reviewed equity portfolio performance. Client pleased with YTD returns. Interested in adding international exposure.',
   '["Send Vanguard international fund comparison", "Schedule Q3 tax-loss harvesting review"]');

-- client_002 (hero): 30 days ago — large payment flagged, CD renewal upcoming
INSERT INTO interaction (client_id, meeting_date, notes, promises) VALUES
  ('client_002', '2026-05-18',
   'Client discussed Q2 cash-flow planning. Mentioned a large payment to a business partner expected end of May. Asked about CD renewal options ahead of July maturity.',
   '["Review business line of credit options", "Send updated CD renewal rate sheet ahead of July 8 maturity"]');

-- client_003 (dormant): 60 days ago — client travelling, minimal activity expected
INSERT INTO interaction (client_id, meeting_date, notes, promises) VALUES
  ('client_003', '2026-04-18',
   'Brief check-in before client departed for extended travel in Europe. Account activity expected to be minimal through June.',
   '["Reach out when client returns — estimated late June", "Review savings rate once back"]');

-- Synthetic products (instruments Plaid Sandbox does not model)
-- Hero client: CD maturing in 3 weeks — the key opportunity talking point
INSERT INTO synthetic_product (client_id, product_type, balance, maturity_date, rate) VALUES
  ('client_002', 'CD', 150000.00, '2026-07-08', 0.0475);

-- Clean client: a term deposit renewing later in the year (low urgency)
INSERT INTO synthetic_product (client_id, product_type, balance, maturity_date, rate) VALUES
  ('client_001', 'TERM_DEPOSIT', 75000.00, '2026-11-15', 0.0420);
