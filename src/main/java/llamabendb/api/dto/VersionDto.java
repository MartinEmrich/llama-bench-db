package llamabendb.api.dto;

import java.time.Instant;
import java.util.Map;

public record VersionDto(Long id, Instant createdAt, String description, Map<String, String> devices) {
}
