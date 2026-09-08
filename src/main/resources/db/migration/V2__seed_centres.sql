-- The 23 Kaduna State LGA Centres of Excellence.
-- Status is SETUP until a Central Administrator activates each one.
INSERT INTO centres (created_at, code, name, lga, state, is_active,
                     has_pharmacy_capability, has_laboratory_capability,
                     has_him_capability, default_consultation_minutes, created_by)
VALUES
 (UTC_TIMESTAMP(6), 'COE-BGW', 'Birnin Gwari Centre of Excellence',   'Birnin Gwari',   'Kaduna', b'0', b'0', b'0', b'0', 30, 'system'),
 (UTC_TIMESTAMP(6), 'COE-CHK', 'Chikun Centre of Excellence',         'Chikun',         'Kaduna', b'0', b'0', b'0', b'0', 30, 'system'),
 (UTC_TIMESTAMP(6), 'COE-GIW', 'Giwa Centre of Excellence',           'Giwa',           'Kaduna', b'0', b'0', b'0', b'0', 30, 'system'),
 (UTC_TIMESTAMP(6), 'COE-IGB', 'Igabi Centre of Excellence',          'Igabi',          'Kaduna', b'0', b'0', b'0', b'0', 30, 'system'),
 (UTC_TIMESTAMP(6), 'COE-IKR', 'Ikara Centre of Excellence',          'Ikara',          'Kaduna', b'0', b'0', b'0', b'0', 30, 'system'),
 (UTC_TIMESTAMP(6), 'COE-JAB', 'Jaba Centre of Excellence',           'Jaba',           'Kaduna', b'0', b'0', b'0', b'0', 30, 'system'),
 (UTC_TIMESTAMP(6), 'COE-JEM', 'Jema''a Centre of Excellence',        'Jema''a',        'Kaduna', b'0', b'0', b'0', b'0', 30, 'system'),
 (UTC_TIMESTAMP(6), 'COE-KCH', 'Kachia Centre of Excellence',         'Kachia',         'Kaduna', b'0', b'0', b'0', b'0', 30, 'system'),
 (UTC_TIMESTAMP(6), 'COE-KDN', 'Kaduna North Centre of Excellence',   'Kaduna North',   'Kaduna', b'0', b'0', b'0', b'0', 30, 'system'),
 (UTC_TIMESTAMP(6), 'COE-KDS', 'Kaduna South Centre of Excellence',   'Kaduna South',   'Kaduna', b'0', b'0', b'0', b'0', 30, 'system'),
 (UTC_TIMESTAMP(6), 'COE-KGK', 'Kagarko Centre of Excellence',        'Kagarko',        'Kaduna', b'0', b'0', b'0', b'0', 30, 'system'),
 (UTC_TIMESTAMP(6), 'COE-KJR', 'Kajuru Centre of Excellence',         'Kajuru',         'Kaduna', b'0', b'0', b'0', b'0', 30, 'system'),
 (UTC_TIMESTAMP(6), 'COE-KAU', 'Kaura Centre of Excellence',          'Kaura',          'Kaduna', b'0', b'0', b'0', b'0', 30, 'system'),
 (UTC_TIMESTAMP(6), 'COE-KRU', 'Kauru Centre of Excellence',          'Kauru',          'Kaduna', b'0', b'0', b'0', b'0', 30, 'system'),
 (UTC_TIMESTAMP(6), 'COE-KUB', 'Kubau Centre of Excellence',          'Kubau',          'Kaduna', b'0', b'0', b'0', b'0', 30, 'system'),
 (UTC_TIMESTAMP(6), 'COE-KUD', 'Kudan Centre of Excellence',          'Kudan',          'Kaduna', b'0', b'0', b'0', b'0', 30, 'system'),
 (UTC_TIMESTAMP(6), 'COE-LER', 'Lere Centre of Excellence',           'Lere',           'Kaduna', b'0', b'0', b'0', b'0', 30, 'system'),
 (UTC_TIMESTAMP(6), 'COE-MKF', 'Makarfi Centre of Excellence',        'Makarfi',        'Kaduna', b'0', b'0', b'0', b'0', 30, 'system'),
 (UTC_TIMESTAMP(6), 'COE-SBG', 'Sabon Gari Centre of Excellence',     'Sabon Gari',     'Kaduna', b'0', b'0', b'0', b'0', 30, 'system'),
 (UTC_TIMESTAMP(6), 'COE-SNG', 'Sanga Centre of Excellence',          'Sanga',          'Kaduna', b'0', b'0', b'0', b'0', 30, 'system'),
 (UTC_TIMESTAMP(6), 'COE-SOB', 'Soba Centre of Excellence',           'Soba',           'Kaduna', b'0', b'0', b'0', b'0', 30, 'system'),
 (UTC_TIMESTAMP(6), 'COE-ZKT', 'Zangon Kataf Centre of Excellence',   'Zangon Kataf',   'Kaduna', b'0', b'0', b'0', b'0', 30, 'system'),
 (UTC_TIMESTAMP(6), 'COE-ZAR', 'Zaria Centre of Excellence',          'Zaria',          'Kaduna', b'0', b'0', b'0', b'0', 30, 'system');

-- Every centre gets a wallet at creation. Thresholds are 20 percent warning
-- and 10 percent critical, applied once funding is credited.
INSERT INTO wallets (created_at, centre_id, balance, low_balance_warning_sent, critical_balance_warning_sent, version)
SELECT UTC_TIMESTAMP(6), id, 0.00, b'0', b'0', 0 FROM centres;
