package llamabendb.service;

import llamabendb.api.BadRequestException;
import llamabendb.api.NotFoundException;
import llamabendb.api.dto.ImportResponse;
import llamabendb.domain.Computer;
import llamabendb.domain.ComputerVersion;
import llamabendb.domain.HfModelId;
import llamabendb.domain.Model;
import llamabendb.domain.QuantSortKey;
import llamabendb.domain.Result;
import llamabendb.importer.ImportException;
import llamabendb.importer.ImportParser;
import llamabendb.repo.ComputerRepository;
import llamabendb.repo.ComputerVersionRepository;
import llamabendb.repo.ModelRepository;
import llamabendb.repo.ResultRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class ImportService {

    private static final Set<String> KNOWN_COLUMNS = Set.of(
            "model", "size", "params", "backend", "ngl", "type_k", "type_v",
            "fa", "ts", "lm", "mmap", "threads", "dev");
    private static final double SIZE_TOLERANCE = 0.10;

    private final ImportParser parser = new ImportParser();
    private final ComputerRepository computerRepo;
    private final ComputerVersionRepository versionRepo;
    private final ModelRepository modelRepo;
    private final ResultRepository resultRepo;

    public ImportService(ComputerRepository computerRepo, ComputerVersionRepository versionRepo,
                         ModelRepository modelRepo, ResultRepository resultRepo) {
        this.computerRepo = computerRepo;
        this.versionRepo = versionRepo;
        this.modelRepo = modelRepo;
        this.resultRepo = resultRepo;
    }

    /**
     * Imports a pasted console transcript. computerId and modelId may be null:
     * each run is then resolved from the command line preceding its table
     * (hostname → computer, -hf → model). Unknown models are created silently.
     */
    @Transactional
    public ImportResponse importRun(Long computerId, Long computerVersionId, Long modelId, String text,
                                    String build, boolean acknowledgeWarnings) {
        ImportParser.ParseResult parsed = parser.parse(text);

        Model explicitModel = null;
        if (modelId != null) {
            explicitModel = modelRepo.findById(modelId)
                    .orElseThrow(() -> new NotFoundException("model " + modelId + " not found"));
            if (parsed.modelStrings().size() > 1) {
                throw new ImportException("paste contains results for multiple models ("
                        + String.join("; ", parsed.modelStrings())
                        + ") - select a single model or use autodetect");
            }
        }
        ComputerVersion explicitVersion = computerId != null ? resolveVersion(computerId, computerVersionId) : null;

        Map<String, Model> modelCache = new HashMap<>();
        Map<String, ComputerVersion> versionCache = new HashMap<>();
        List<Resolved> resolved = new ArrayList<>();
        for (ImportParser.Dataset d : parsed.datasets()) {
            resolved.add(new Resolved(
                    explicitVersion != null ? explicitVersion : resolveVersionByHostname(d.hostname(), versionCache),
                    resolveModel(d.hfModelId(), explicitModel, modelCache)));
        }

        List<ImportResponse.Warning> warnings = new ArrayList<>();
        boolean sizeMismatch = false;
        for (int i = 0; i < parsed.datasets().size(); i++) {
            ImportParser.Dataset d = parsed.datasets().get(i);
            Model model = resolved.get(i).model();
            if (d.sizeGiB() == null) {
                continue;
            }
            if (model.getSizeGiB() == null) {
                model.setSizeGiB(d.sizeGiB());
            } else if (Math.abs(d.sizeGiB() - model.getSizeGiB()) / model.getSizeGiB() > SIZE_TOLERANCE) {
                sizeMismatch = true;
                warnings.add(new ImportResponse.Warning("SIZE_MISMATCH", String.format(
                        "observed size %.2f GiB differs from the stored model size %.2f GiB by more than 10%% - check that the right model/quantization is selected",
                        d.sizeGiB(), model.getSizeGiB())));
            }
        }
        if (sizeMismatch && !acknowledgeWarnings) {
            return new ImportResponse(List.of(), warnings, true);
        }

        String fallbackBuild = build == null || build.isBlank() ? null : build.strip();
        List<Result> results = new ArrayList<>();
        for (int i = 0; i < parsed.datasets().size(); i++) {
            ImportParser.Dataset d = parsed.datasets().get(i);
            Result r = toEntity(d, resolved.get(i).version(), resolved.get(i).model(), fallbackBuild);
            addDeviceWarning(r, warnings);
            results.add(r);
        }
        resultRepo.saveAll(results);

        List<ImportResponse.ImportedResult> created = new ArrayList<>();
        for (int i = 0; i < results.size(); i++) {
            Result r = results.get(i);
            created.add(new ImportResponse.ImportedResult(
                    r.getId(), r.getModelString(), r.getPpTokens(), r.getTgTokens(), r.getPpTps(), r.getTgTps(),
                    r.getComputerVersion().getComputer().getName(), r.getModel().getModelId()));
        }
        return new ImportResponse(created, warnings, false);
    }

    /** Preview of what autodetect would resolve per run; parseError set when the text does not parse. */
    public DetectResult detect(String text) {
        try {
            List<DetectRun> runs = parser.parse(text).datasets().stream()
                    .map(d -> new DetectRun(d.hostname(), d.hfModelId()))
                    .toList();
            return new DetectResult(null, runs);
        } catch (ImportException e) {
            return new DetectResult(e.getMessage(), List.of());
        }
    }

    public record DetectRun(String hostname, String hfModelId) {
    }

    public record DetectResult(String parseError, List<DetectRun> runs) {
    }

    private record Resolved(ComputerVersion version, Model model) {
    }

    private ComputerVersion resolveVersion(Long computerId, Long computerVersionId) {
        if (computerVersionId == null) {
            // No explicit version: attach to the newest one of the computer.
            List<ComputerVersion> versions = versionRepo.findByComputerIdOrderByCreatedAtDesc(computerId);
            if (versions.isEmpty()) {
                throw new BadRequestException("computer " + computerId + " has no versions");
            }
            return versions.get(0);
        }
        ComputerVersion version = versionRepo.findById(computerVersionId)
                .orElseThrow(() -> new NotFoundException("computer version " + computerVersionId + " not found"));
        if (!version.getComputer().getId().equals(computerId)) {
            throw new BadRequestException("version " + computerVersionId + " does not belong to computer " + computerId);
        }
        return version;
    }

    private ComputerVersion resolveVersionByHostname(String hostname, Map<String, ComputerVersion> cache) {
        if (hostname == null || hostname.isBlank()) {
            throw new ImportException("no computer selected and no hostname detected in the pasted command line");
        }
        return cache.computeIfAbsent(hostname.toLowerCase(), h -> {
            List<Computer> candidates = computerRepo.findByHostnameNewestVersionFirst(hostname);
            if (candidates.isEmpty()) {
                throw new ImportException("no computer with hostname '" + hostname + "' - add it or select a computer");
            }
            return resolveVersion(candidates.get(0).getId(), null);
        });
    }

    private Model resolveModel(String hfModelId, Model explicitModel, Map<String, Model> cache) {
        if (explicitModel != null) {
            return explicitModel;
        }
        if (hfModelId == null || hfModelId.isBlank()) {
            throw new ImportException("no model selected and no -hf parameter found in the pasted command line");
        }
        return cache.computeIfAbsent(hfModelId.strip().toLowerCase(), k -> {
            HfModelId.Parsed parsed;
            try {
                parsed = HfModelId.parse(hfModelId, null);
            } catch (IllegalArgumentException e) {
                throw new ImportException("cannot auto-detect model: -hf '" + hfModelId
                        + "' has no quantization - expected 'uploader/model:QUANT'");
            }
            return modelRepo.findByModelIdAndQuantizationIgnoreCase(parsed.repo(), parsed.quant())
                    .orElseGet(() -> createModel(parsed));
        });
    }

    private Model createModel(HfModelId.Parsed parsed) {
        Model m = new Model();
        m.setName(parsed.baseName());
        m.setModelId(parsed.repo());
        m.setQuantization(parsed.quant());
        m.setQuantSortKey(QuantSortKey.of(parsed.quant()));
        return modelRepo.save(m);
    }

    private void addDeviceWarning(Result r, List<ImportResponse.Warning> warnings) {
        if (r.getDevices() == null || r.getBackend() == null) {
            return;
        }
        String devices = r.getDevices().strip();
        if (devices.equalsIgnoreCase("none")) {
            return;
        }
        String base = devices.replaceAll("\\d+$", "");
        List<String> backends = Arrays.stream(r.getBackend().split(",")).map(String::strip).toList();
        if (!backends.contains(base)) {
            warnings.add(new ImportResponse.Warning("DEVICE_NOT_IN_BACKEND",
                    "device '" + devices + "' is not listed among the available backends '" + r.getBackend() + "'"));
        }
    }

    private Result toEntity(ImportParser.Dataset d, ComputerVersion version, Model model, String fallbackBuild) {
        Map<String, String> f = d.fields();
        Result r = new Result();
        r.setComputerVersion(version);
        r.setModel(model);
        r.setImportedAt(Instant.now());
        r.setModelString(d.modelString());
        r.setSizeGiBObserved(d.sizeGiB());
        r.setBackend(f.get("backend"));
        r.setDevices(f.get("dev"));
        r.setNgl(parseInt(f.get("ngl"), "ngl", -1));
        r.setTypeK(blankToDefault(f.get("type_k"), "f16"));
        r.setTypeV(blankToDefault(f.get("type_v"), "f16"));
        r.setFa("1".equals(f.get("fa")));
        r.setThreads(parseIntOrNull(f.get("threads"), "threads"));
        r.setTs(f.get("ts"));
        String lm = f.get("lm");
        if (lm == null || lm.isBlank()) {
            if ("1".equals(f.get("mmap"))) {
                lm = "mmap";
            }
        }
        r.setLoadMode(lm != null && !lm.isBlank() ? lm : "auto");
        r.setPpTokens(d.ppTokens());
        r.setTgTokens(d.tgTokens());
        r.setPpTps(d.ppTps());
        r.setTgTps(d.tgTps());
        r.setPpDeviation(d.ppDeviation());
        r.setTgDeviation(d.tgDeviation());
        // A build line in the paste wins over the form-supplied value.
        r.setBuild(d.build() != null ? d.build() : fallbackBuild);
        Map<String, Object> params = new LinkedHashMap<>();
        f.forEach((k, v) -> {
            if (!KNOWN_COLUMNS.contains(k)) {
                params.put(k, v);
            }
        });
        r.setParams(params);
        return r;
    }

    private static String blankToDefault(String v, String def) {
        return v == null || v.isBlank() ? def : v;
    }

    private static Integer parseInt(String v, String field, int fallback) {
        if (v == null || v.isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(v.strip());
        } catch (NumberFormatException e) {
            throw new ImportException("unparseable " + field + " value '" + v + "'");
        }
    }

    private static Integer parseIntOrNull(String v, String field) {
        if (v == null || v.isBlank()) {
            return null;
        }
        try {
            return Integer.parseInt(v.strip());
        } catch (NumberFormatException e) {
            throw new ImportException("unparseable " + field + " value '" + v + "'");
        }
    }
}
