package llamabendb.api.dto;

import java.time.Instant;

public record ComputerListDto(Long id, String name, String hostname, int versionCount, Instant latestVersionAt) {
}
