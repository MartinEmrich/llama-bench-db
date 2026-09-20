package llamabendb.api;

import llamabendb.api.dto.PageResultDto;
import llamabendb.api.dto.ResultDto;
import llamabendb.repo.ResultRepository;
import llamabendb.service.ImportService;
import llamabendb.api.dto.ImportResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/results")
public class ResultController {

    public record ImportRequest(Long computerId, Long versionId, Long modelId, String text,
                                String build, Boolean acknowledgeWarnings) {
    }

    private static final Map<String, String> SORTABLE = Map.ofEntries(
            Map.entry("importedAt", "importedAt"),
            Map.entry("computer", "computerVersion.computer.name"),
            Map.entry("version", "computerVersion.createdAt"),
            Map.entry("model", "model.name"),
            Map.entry("modelId", "model.modelId"),
            Map.entry("quant", "model.quantSortKey"),
            Map.entry("ngl", "ngl"),
            Map.entry("ppTokens", "ppTokens"),
            Map.entry("tgTokens", "tgTokens"),
            Map.entry("ppTps", "ppTps"),
            Map.entry("tgTps", "tgTps"));

    private final ResultRepository resultRepo;
    private final ImportService importService;

    public ResultController(ResultRepository resultRepo, ImportService importService) {
        this.resultRepo = resultRepo;
        this.importService = importService;
    }

    @GetMapping
    public PageResultDto<ResultDto> list(
            @RequestParam(required = false) Long computerId,
            @RequestParam(required = false) Long versionId,
            @RequestParam(required = false) Long modelId,
            @RequestParam(required = false) String quant,
            @RequestParam(required = false) Integer ppMin,
            @RequestParam(required = false) Integer ppMax,
            @RequestParam(required = false) Integer tgMin,
            @RequestParam(required = false) Integer tgMax,
            @RequestParam(required = false) Double ppTpsMin,
            @RequestParam(required = false) Double ppTpsMax,
            @RequestParam(required = false) Double tgTpsMin,
            @RequestParam(required = false) Double tgTpsMax,
            @RequestParam(defaultValue = "importedAt,desc") String sort,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size
    ) {
        String[] parts = sort.split(",");
        String property = SORTABLE.get(parts[0]);
        if (property == null) {
            throw new BadRequestException("unsupported sort field '" + parts[0] + "'");
        }
        Sort.Direction direction = parts.length > 1 && "asc".equalsIgnoreCase(parts[1])
                ? Sort.Direction.ASC
                : Sort.Direction.DESC;
        Pageable pageable = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 200), Sort.by(direction, property));

        Page<ResultDto> result = resultRepo.search(
                computerId, versionId, modelId, quant,
                ppMin, ppMax, tgMin, tgMax,
                ppTpsMin, ppTpsMax, tgTpsMin, tgTpsMax,
                pageable);
        return new PageResultDto<>(result.getContent(), result.getNumber(), result.getSize(),
                result.getTotalElements(), result.getTotalPages());
    }

    @PostMapping("/import")
    public ResponseEntity<ImportResponse> importRun(@RequestBody ImportRequest req) {
        if (req.computerId() == null || req.modelId() == null || req.text() == null) {
            throw new BadRequestException("computerId, modelId and text are required");
        }
        ImportResponse response = importService.importRun(
                req.computerId(), req.versionId(), req.modelId(), req.text(), req.build(),
                Boolean.TRUE.equals(req.acknowledgeWarnings()));
        if (response.blocked()) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(response);
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteResult(@PathVariable Long id) {
        if (!resultRepo.existsById(id)) {
            throw new NotFoundException("result " + id + " not found");
        }
        resultRepo.deleteById(id);
    }

    /** Newest non-null build for a computer, optionally restricted to a backend. */
    @GetMapping("/latest-build")
    public ResponseEntity<Map<String, String>> latestBuild(
            @RequestParam Long computerId,
            @RequestParam(required = false) String backend) {
        List<String> builds = resultRepo.findLatestBuilds(
                computerId,
                backend == null || backend.isBlank() ? null : backend.strip(),
                PageRequest.of(0, 1));
        if (builds.isEmpty()) {
            return ResponseEntity.noContent().build();
        }
        return ResponseEntity.ok(Map.of("build", builds.get(0)));
    }
}
