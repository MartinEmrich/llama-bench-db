package llamabendb.api;

import llamabendb.api.dto.PageResultDto;
import llamabendb.api.dto.ResultDto;
import llamabendb.domain.Computer;
import llamabendb.domain.ComputerVersion;
import llamabendb.domain.Model;
import llamabendb.domain.Result;
import llamabendb.repo.ComputerRepository;
import llamabendb.repo.ComputerVersionRepository;
import llamabendb.repo.ModelRepository;
import llamabendb.repo.ResultRepository;
import llamabendb.service.ImportService;
import llamabendb.api.dto.ImportResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

@RestController
@RequestMapping("/api/results")
public class ResultController {

    public record ImportRequest(Long computerId, Long versionId, Long modelId, String text,
                                String build, Boolean acknowledgeWarnings) {
    }

    public record DetectRequest(String text) {
    }

    /**
     * Manual correction of a stored result. null = unchanged, blank string =
     * cleared (nullable fields only). Numeric fields are strings so that
     * "blank clears" works uniformly; JSON numbers are coerced as well.
     */
    public record UpdateResultRequest(
            Long computerId,
            Long versionId,
            Long modelId,
            Instant importedAt,
            String modelString,
            String sizeGiBObserved,
            String backend,
            String devices,
            String ngl,
            String typeK,
            String typeV,
            Boolean fa,
            String threads,
            String ts,
            String loadMode,
            String build,
            Map<String, String> params
    ) {
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
    private final ComputerRepository computerRepo;
    private final ComputerVersionRepository versionRepo;
    private final ModelRepository modelRepo;
    private final ImportService importService;

    public ResultController(ResultRepository resultRepo, ComputerRepository computerRepo,
                            ComputerVersionRepository versionRepo, ModelRepository modelRepo,
                            ImportService importService) {
        this.resultRepo = resultRepo;
        this.computerRepo = computerRepo;
        this.versionRepo = versionRepo;
        this.modelRepo = modelRepo;
        this.importService = importService;
    }

    @GetMapping
    public PageResultDto<ResultDto> list(
            @RequestParam(required = false) Long computerId,
            @RequestParam(required = false) Long versionId,
            @RequestParam(required = false) Long modelId,
            @RequestParam(required = false) String model,
            @RequestParam(required = false) String quant,
            @RequestParam(required = false) String devices,
            @RequestParam(required = false) Boolean devicesEmpty,
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
                computerId, versionId, modelId, model, quant, devices, devicesEmpty,
                ppMin, ppMax, tgMin, tgMax,
                ppTpsMin, ppTpsMax, tgTpsMin, tgTpsMax,
                pageable);
        return new PageResultDto<>(result.getContent(), result.getNumber(), result.getSize(),
                result.getTotalElements(), result.getTotalPages());
    }

    @PostMapping("/import")
    public ResponseEntity<ImportResponse> importRun(@RequestBody ImportRequest req) {
        if (req.text() == null) {
            throw new BadRequestException("text is required");
        }
        // computerId and modelId may be null: each run is then resolved from the
        // command line preceding its table (hostname → computer, -hf → model).
        ImportResponse response = importService.importRun(
                req.computerId(), req.versionId(), req.modelId(), req.text(), req.build(),
                Boolean.TRUE.equals(req.acknowledgeWarnings()));
        if (response.blocked()) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(response);
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /** Distinct device values (used device lists), optionally scoped to a computer. */
    @GetMapping("/device-values")
    public List<String> deviceValues(@RequestParam(required = false) Long computerId) {
        return resultRepo.findDeviceValues(computerId);
    }

    /** What autodetect would resolve per run, so the import form can preview it. */
    @PostMapping("/detect")
    public ImportService.DetectResult detect(@RequestBody DetectRequest req) {
        if (req.text() == null) {
            throw new BadRequestException("text is required");
        }
        return importService.detect(req.text());
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteResult(@PathVariable Long id) {
        if (!resultRepo.existsById(id)) {
            throw new NotFoundException("result " + id + " not found");
        }
        resultRepo.deleteById(id);
    }

    /** Manually corrects a stored result (devices, backend, attribution, …). */
    @PutMapping("/{id}")
    @Transactional
    public ResultDto updateResult(@PathVariable Long id, @RequestBody UpdateResultRequest req) {
        Result r = resultRepo.findById(id)
                .orElseThrow(() -> new NotFoundException("result " + id + " not found"));
        reLink(r, req);

        if (req.importedAt() != null) {
            r.setImportedAt(req.importedAt());
        }
        applyNullable(req.modelString(), r::setModelString);
        applyDouble(req.sizeGiBObserved(), "sizeGiB", r::setSizeGiBObserved);
        applyNullable(req.backend(), r::setBackend);
        applyNullable(req.devices(), r::setDevices);
        applyNgl(req.ngl(), r::setNgl);
        applyRequired(req.typeK(), "typeK", r::setTypeK);
        applyRequired(req.typeV(), "typeV", r::setTypeV);
        if (req.fa() != null) {
            r.setFa(req.fa());
        }
        applyInt(req.threads(), "threads", r::setThreads);
        applyNullable(req.ts(), r::setTs);
        applyRequired(req.loadMode(), "loadMode", r::setLoadMode);
        applyNullable(req.build(), r::setBuild);
        if (req.params() != null) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<String, String> e : req.params().entrySet()) {
                String k = e.getKey() == null ? "" : e.getKey().strip();
                if (!k.isEmpty() && e.getValue() != null) {
                    out.put(k, e.getValue());
                }
            }
            r.setParams(out);
        }
        return toDto(resultRepo.save(r));
    }

    /** Re-links the result to another computer version and/or model (mirrors import resolution). */
    private void reLink(Result r, UpdateResultRequest req) {
        if (req.versionId() != null) {
            ComputerVersion v = versionRepo.findById(req.versionId())
                    .orElseThrow(() -> new NotFoundException("computer version " + req.versionId() + " not found"));
            if (req.computerId() != null && !v.getComputer().getId().equals(req.computerId())) {
                throw new BadRequestException("version " + req.versionId() + " does not belong to computer " + req.computerId());
            }
            r.setComputerVersion(v);
        } else if (req.computerId() != null) {
            Computer c = computerRepo.findById(req.computerId())
                    .orElseThrow(() -> new NotFoundException("computer " + req.computerId() + " not found"));
            List<ComputerVersion> versions = versionRepo.findByComputerIdOrderByCreatedAtDesc(c.getId());
            if (versions.isEmpty()) {
                throw new BadRequestException("computer " + c.getId() + " has no versions");
            }
            r.setComputerVersion(versions.get(0));
        }
        if (req.modelId() != null) {
            Model m = modelRepo.findById(req.modelId())
                    .orElseThrow(() -> new NotFoundException("model " + req.modelId() + " not found"));
            r.setModel(m);
        }
    }

    /** Nullable string column: null = unchanged, blank = cleared. */
    private static void applyNullable(String v, Consumer<String> set) {
        if (v != null) {
            set.accept(v.isBlank() ? null : v.strip());
        }
    }

    /** Non-null string column: null = unchanged, blank rejected. */
    private static void applyRequired(String v, String field, Consumer<String> set) {
        if (v == null) {
            return;
        }
        if (v.isBlank()) {
            throw new BadRequestException(field + " cannot be empty");
        }
        set.accept(v.strip());
    }

    private static void applyNgl(String v, Consumer<Integer> set) {
        if (v == null) {
            return;
        }
        if (v.isBlank()) {
            throw new BadRequestException("ngl cannot be empty");
        }
        int ngl;
        try {
            ngl = Integer.parseInt(v.strip());
        } catch (NumberFormatException e) {
            throw new BadRequestException("ngl must be an integer");
        }
        if (ngl < -1) {
            throw new BadRequestException("ngl must be >= -1");
        }
        set.accept(ngl);
    }

    /** Nullable integer column: null = unchanged, blank = cleared. */
    private static void applyInt(String v, String field, Consumer<Integer> set) {
        if (v == null) {
            return;
        }
        if (v.isBlank()) {
            set.accept(null);
            return;
        }
        try {
            int n = Integer.parseInt(v.strip());
            if (n < 1) {
                throw new BadRequestException(field + " must be >= 1");
            }
            set.accept(n);
        } catch (NumberFormatException e) {
            throw new BadRequestException(field + " must be an integer");
        }
    }

    /** Nullable double column: null = unchanged, blank = cleared. */
    private static void applyDouble(String v, String field, Consumer<Double> set) {
        if (v == null) {
            return;
        }
        if (v.isBlank()) {
            set.accept(null);
            return;
        }
        try {
            double d = Double.parseDouble(v.strip());
            if (d < 0) {
                throw new BadRequestException(field + " must be >= 0");
            }
            set.accept(d);
        } catch (NumberFormatException e) {
            throw new BadRequestException(field + " must be a number");
        }
    }

    private static ResultDto toDto(Result r) {
        return new ResultDto(
                r.getId(), r.getImportedAt(),
                r.getComputerVersion().getComputer().getId(), r.getComputerVersion().getId(),
                r.getComputerVersion().getComputer().getName(), r.getComputerVersion().getCreatedAt(),
                r.getModel().getId(), r.getModel().getName(), r.getModel().getModelId(), r.getModel().getQuantization(),
                r.getModelString(), r.getSizeGiBObserved(), r.getBackend(), r.getDevices(),
                r.getNgl(), r.getTypeK(), r.getTypeV(), r.getFa(), r.getThreads(), r.getTs(), r.getLoadMode(),
                r.getPpTokens(), r.getTgTokens(), r.getPpTps(), r.getTgTps(), r.getPpDeviation(), r.getTgDeviation(),
                r.getBuild(), r.getParams());
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
