package llamabendb.api.dto;

import java.time.Instant;
import java.util.Map;

public record ResultDto(
        Long id,
        Instant importedAt,
        String computerName,
        Instant versionDate,
        String modelName,
        String modelId,
        String quantization,
        String modelString,
        Double sizeGiB,
        String backend,
        String devices,
        Integer ngl,
        String typeK,
        String typeV,
        Boolean fa,
        Integer threads,
        String ts,
        String loadMode,
        Integer ppTokens,
        Integer tgTokens,
        Double ppTps,
        Double tgTps,
        Double ppDeviation,
        Double tgDeviation,
        Map<String, Object> params
) {
}
