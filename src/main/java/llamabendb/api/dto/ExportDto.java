package llamabendb.api.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public record ExportDto(
        Instant exportedAt,
        List<ComputerExport> computers,
        List<ModelExport> models,
        List<ResultExport> results
) {

    public record ComputerExport(Long id, String name, String hostname, List<VersionExport> versions) {
    }

    public record VersionExport(Long id, Long computerId, Instant createdAt, String description) {
    }

    public record ModelExport(Long id, String name, String modelId, String quantization, Double sizeGiB) {
    }

    public record ResultExport(
            Long id,
            Long computerVersionId,
            Long modelId,
            Instant importedAt,
            String modelString,
            Double sizeGiBObserved,
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
            String build,
            Map<String, Object> params
    ) {
    }
}
