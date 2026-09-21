package llamabendb.domain;

/**
 * Parses a Hugging Face model id of the form 'uploader/name:QUANT' into its parts.
 * The base name is the repo name without uploader and without a trailing "-GGUF".
 */
public final class HfModelId {

    public record Parsed(String repo, String quant, String baseName) {
    }

    private HfModelId() {
    }

    public static Parsed parse(String modelId, String explicitQuant) {
        if (modelId == null || modelId.isBlank()) {
            throw new IllegalArgumentException("modelId is required");
        }
        String id = modelId.strip();
        String repo;
        String quant = explicitQuant;
        int colon = id.lastIndexOf(':');
        if (colon > 0 && colon < id.length() - 1) {
            repo = id.substring(0, colon);
            quant = id.substring(colon + 1);
        } else {
            repo = id;
        }
        if (quant == null || quant.isBlank()) {
            throw new IllegalArgumentException("quantization is required - expected format 'uploader/name:QUANT'");
        }
        String baseName = repo.contains("/") ? repo.substring(repo.indexOf('/') + 1) : repo;
        if (baseName.length() > 5 && baseName.regionMatches(true, baseName.length() - 5, "-GGUF", 0, 5)) {
            baseName = baseName.substring(0, baseName.length() - 5);
        }
        return new Parsed(repo, quant.strip(), baseName);
    }
}
