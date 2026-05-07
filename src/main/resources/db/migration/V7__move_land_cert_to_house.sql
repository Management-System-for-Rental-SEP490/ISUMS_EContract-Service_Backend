-- V7: Drop land_cert_* columns from econtracts — they moved to houses.
--
-- GCN (Land Use Right Certificate) is a fact about the house, not about each
-- lease contract. Every contract for the same house references the same GCN,
-- so duplicating the info in `econtracts` made no sense. Moved to
-- houses.land_cert_number/issue_date/issuer; ContractHtmlBuilder now pulls
-- from House gRPC at render time.
--
-- Also drop ownership_docs — duplicated the GCN info as free text. Removed.

ALTER TABLE econtracts
    DROP COLUMN IF EXISTS land_cert_number,
    DROP COLUMN IF EXISTS land_cert_issue_date,
    DROP COLUMN IF EXISTS land_cert_issuer;
