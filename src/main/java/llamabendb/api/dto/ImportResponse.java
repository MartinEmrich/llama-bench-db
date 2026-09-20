package llamabendb.api.dto;

import java.util.List;

public record ImportResponse(List<ImportedResult> results, List<Warning> warnings, boolean blocked) {

    public record ImportedResult(Long id, String modelString, int ppTokens, int tgTokens, double ppTps, double tgTps) {
    }

    public record Warning(String code, String message) {
    }
}
