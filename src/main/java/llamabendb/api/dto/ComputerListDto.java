package llamabendb.api.dto;

import java.time.Instant;

public record ComputerListDto(Long id, String name, int versionCount, Instant latestVersionAt) {
}
