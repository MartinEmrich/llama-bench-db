package llamabendb.api.dto;

import java.time.Instant;

public record VersionDto(Long id, Instant createdAt, String description) {
}
