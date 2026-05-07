ALTER TABLE landlord_profiles
    ADD COLUMN IF NOT EXISTS force_majeure_notice_hours INTEGER NOT NULL DEFAULT 24
        CHECK (force_majeure_notice_hours BETWEEN 1 AND 168);
