package com.isums.contractservice.domains.dtos;

import jakarta.validation.constraints.PositiveOrZero;
import lombok.Builder;
import org.jspecify.annotations.Nullable;

/**
 * Chỉ số điện/nước đầu kỳ — ghi nhận làm bằng chứng cho việc quyết toán hoặc
 * xử lý tranh chấp. Bên B tự đăng ký và thanh toán trực tiếp với EVN / đơn vị
 * cấp nước; các con số ở đây KHÔNG tính vào tiền thuê.
 *
 * Gas đã bị loại: phần lớn nhà thuê nguyên căn ở TP.HCM dùng gas bình (không
 * có công tơ), nên field gây confusion hơn là hữu ích.
 */
@Builder
public record MeterReadingsDto(
        @Nullable @PositiveOrZero Long electricKwh,
        @Nullable @PositiveOrZero Long waterM3,
        @Nullable String note
) {
    public java.util.Map<String, Object> asMap() {
        java.util.Map<String, Object> m = new java.util.HashMap<>();
        if (electricKwh != null) m.put("electric", electricKwh);
        if (waterM3 != null) m.put("water", waterM3);
        if (note != null) m.put("note", note);
        return m;
    }
}
