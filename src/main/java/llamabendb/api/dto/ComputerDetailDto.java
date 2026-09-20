package llamabendb.api.dto;

import java.util.List;

public record ComputerDetailDto(Long id, String name, List<VersionDto> versions) {
}
