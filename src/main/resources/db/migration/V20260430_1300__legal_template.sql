-- Legal template registry. Stores versioned, multi-lingual legal text snippets
-- (e.g. "Căn cứ Bộ luật Dân sự 2015 Điều 328...") that get snapshotted into
-- domain records (relocation requests, future renewals/terminations) at the
-- moment the user agrees to them.
--
-- Versioning model (immutable history):
--   - INSERT new row when admin updates text → previous active row gets expired_at = effective_at_of_new
--   - NEVER UPDATE an existing row's `text` once it has been snapshotted into a domain record
--   - Lookup picks the most-recent row where effective_at <= now AND expired_at IS NULL
--
-- Seeded rows use the all-zeros UUID as created_by (system seed marker).
-- Admin-created versions will use the LANDLORD's internal user id.

CREATE TABLE IF NOT EXISTS legal_template (
    id              uuid        PRIMARY KEY,
    template_key    varchar(80) NOT NULL,
    lang            varchar(8)  NOT NULL,
    text            text        NOT NULL,
    effective_at    timestamptz NOT NULL DEFAULT now(),
    expired_at      timestamptz,
    note            varchar(500),
    created_by      uuid        NOT NULL,
    expired_by      uuid,
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz,

    CONSTRAINT chk_legal_template_text_min  CHECK (length(text) >= 20),
    CONSTRAINT chk_legal_template_dates     CHECK (expired_at IS NULL OR expired_at > effective_at),
    CONSTRAINT chk_legal_template_lang      CHECK (lang IN ('vi','en','ja'))
);

-- Hot-path index for resolveSnapshot(): only active rows
CREATE INDEX IF NOT EXISTS idx_legal_template_active
    ON legal_template (template_key, lang, effective_at DESC)
    WHERE expired_at IS NULL;

-- Full-history index for admin "show all versions" view
CREATE INDEX IF NOT EXISTS idx_legal_template_history
    ON legal_template (template_key, lang, effective_at DESC);

-- Seed initial templates. Vietnamese text mirrors the strings previously hardcoded
-- in ContractRelocationServiceImpl. English translations are engineering team's
-- working copy, pending legal review — admin can replace via POST /api/legal-templates.

INSERT INTO legal_template (id, template_key, lang, text, created_by, note)
VALUES
('11111111-1111-1111-1111-111111111101', 'RELOCATION_LANDLORD_FAULT_BASIS', 'vi',
 'Căn cứ Điều 328, Điều 477 Bộ luật Dân sự 2015 và Điều 172 Luật Nhà ở 2023: nhà thuê phải bảo đảm giá trị sử dụng, an toàn và mục đích thuê; nếu nhà không đủ điều kiện sử dụng không do lỗi bên thuê, bên thuê không mất cọc và được đổi nhà phù hợp hoặc hoàn trả các khoản đã thanh toán theo thỏa thuận/hợp đồng.',
 '00000000-0000-0000-0000-000000000000',
 'Initial seed (formerly ContractRelocationServiceImpl.LANDLORD_FAULT_LEGAL_BASIS)'),

('11111111-1111-1111-1111-111111111102', 'RELOCATION_LANDLORD_FAULT_BASIS', 'en',
 'Pursuant to Articles 328 and 477 of the 2015 Civil Code and Article 172 of the 2023 Law on Housing: the leased premises must guarantee usable value, safety and the agreed purpose of use; if the premises become unfit for use for reasons not attributable to the lessee, the lessee shall not forfeit the deposit and shall be entitled to a suitable replacement property or to a refund of all amounts already paid under the agreement/contract.',
 '00000000-0000-0000-0000-000000000000',
 'Initial seed (English translation by engineering — pending legal review)'),

('11111111-1111-1111-1111-111111111201', 'RELOCATION_ACTIVE_LEASE_UPGRADE_BASIS', 'vi',
 'Bên thuê đang trong thời hạn thuê được đề nghị đổi sang nhà khác theo nhu cầu thực tế. Việc đổi nhà chỉ có hiệu lực khi các bên chấp thuận báo giá/quyết toán, ký hợp đồng hoặc phụ lục thay thế, thanh toán phần chênh lệch đến hạn và hoàn tất bàn giao nhà cũ.',
 '00000000-0000-0000-0000-000000000000',
 'Initial seed (formerly ContractRelocationServiceImpl.ACTIVE_LEASE_UPGRADE_LEGAL_BASIS)'),

('11111111-1111-1111-1111-111111111202', 'RELOCATION_ACTIVE_LEASE_UPGRADE_BASIS', 'en',
 'A lessee currently within the lease term may request to switch to another property based on actual needs. Such a switch only takes effect once both parties agree on the quote/settlement, sign a replacement contract or addendum, pay any due differential, and complete the handover of the previous premises.',
 '00000000-0000-0000-0000-000000000000',
 'Initial seed (English translation by engineering — pending legal review)')
ON CONFLICT (id) DO NOTHING;
