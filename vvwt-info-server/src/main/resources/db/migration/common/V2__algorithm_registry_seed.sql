-- V2__algorithm_registry_seed.sql (common — both H2 and PostgreSQL)
-- Story: E38S03 — DEC-43 D4, AC6
-- Seed the algorithm_registry with exactly one row: Ed25519 (V1 algorithm set).
-- ML-DSA, SLH-DSA, Falcon are explicitly Phase-2+ per DEC-43 D4.
INSERT INTO algorithm_registry (algorithm_id, display_name, deprecation_date, active, parameters)
VALUES ('Ed25519', 'Ed25519', NULL, TRUE, NULL);
