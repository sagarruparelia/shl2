package com.chanakya.shl2.model.dto.response;

import java.util.List;

public record PaginatedAccessLog(
        List<AccessLogEntry> entries,
        String cursor
) {}
