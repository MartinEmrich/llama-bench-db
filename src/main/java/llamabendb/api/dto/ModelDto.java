package llamabendb.api.dto;

public record ModelDto(Long id, String name, String modelId, String quantization, Double sizeGiB) {
}
