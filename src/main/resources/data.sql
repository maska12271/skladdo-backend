INSERT INTO category (name, description, active) VALUES
                                                     ('Electronics', 'Electronic products', true),
                                                     ('Office Supplies', 'Office and stationery products', true),
                                                     ('Tools', 'Various tools', true);

INSERT INTO manufacturer (name, country, address, email, phone, website, notes, active) VALUES
                                                                                            ('Bosch', 'Germany', 'Stuttgart', 'info@bosch.example', '+491111111', 'https://bosch.example', 'Industrial manufacturer', true),
                                                                                            ('Makita', 'Japan', 'Anjo', 'info@makita.example', '+811111111', 'https://makita.example', 'Power tools manufacturer', true),
                                                                                            ('HP', 'USA', 'Palo Alto', 'info@hp.example', '+121111111', 'https://hp.example', 'Office electronics', true);

INSERT INTO client (name, registration_code, email, phone, address, contact_person, notes, active) VALUES
                                                                                                       ('Tallinn City Office', '10000001', 'office@tallinn.example', '+3725000001', 'Tallinn', 'Mari Tamm', 'Public sector client', true),
                                                                                                       ('North Trade OÜ', '10000002', 'info@northtrade.example', '+3725000002', 'Harjumaa', 'Andres Saar', 'Private company', true);

INSERT INTO product (name, sku, manufacturer_id, category_id, size, unit, description, image_url, price, active) VALUES
                                                                                                                     ('Cordless Drill', 'DRILL-001', 2, 3, 'Medium', 'pcs', '18V cordless drill', 'https://example.com/drill.jpg', 129.99, true),
                                                                                                                     ('Laser Printer', 'PRINT-001', 3, 1, 'A4', 'pcs', 'Office laser printer', 'https://example.com/printer.jpg', 249.50, true),
                                                                                                                     ('Monitor 27', 'MON-001', 3, 1, '27 inch', 'pcs', '27 inch office monitor', 'https://example.com/monitor.jpg', 199.99, true);