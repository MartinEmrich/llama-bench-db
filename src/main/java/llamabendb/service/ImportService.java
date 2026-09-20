package llamabendb.service;

import llamabendb.api.BadRequestException;
import llamabendb.api.NotFoundException;
import llamabendb.api.dto.ImportResponse;
import llamabendb.domain.ComputerVersion;
import llamabendb.domain.Model;
import llamabendb.domain.Result;
import llamabendb.importer.ImportException;
import llamabendb.importer.ImportParser;
import llamabendb.repo.ComputerVersionRepository;
import llamabendb.repo.ModelRepository;
import llamabendb.repo.ResultRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
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
    private final ComputerVersionRepository versionRepo;
    private final ModelRepository modelRepo;
    private final ResultRepository resultRepo;

    public ImportService(ComputerVersionRepository versionRepo, ModelRepository modelRepo, ResultRepository resultRepo) {
        this.versionRepo = versionRepo;
        this.modelRepo = modelRepo;
        this.resultRepo = resultRepo;
    }

    @Transactional
    public ImportResponse importRun(Long computerId, Long computerVersionId, Long modelId, String text,
                                    String build, boolean acknowledgeWarnings) {
        ComputerVersion version;
        if (computerVersionId == null) {
            // No explicit version: attach to the newest one of the computer.
            List<ComputerVersion> versions = versionRepo.findByComputerIdOrderByCreatedAtDesc(computerId);
            if (versions.isEmpty()) {
                throw new BadRequestException("computer " + computerId + " has no versions");
            }
            version = versions.get(0);
        } else {
            version = versionRepo.findById(computerVersionId)
                    .orElseThrow(() -> new NotFoundException("computer version " + computerVersionId + " not found"));
            if (!version.getComputer().getId().equals(computerId)) {
                throw new BadRequestException("version " + computerVersionId + " does not belong to computer " + computerId);
            }
        }
        Model model = modelRepo.findById(modelId)
                .orElseThrow(() -> new NotFoundException("model " + modelId + " not found"));

        ImportParser.ParseResult parsed = parser.parse(text);

        List<ImportResponse.Warning> warnings = new ArrayList<>();
        boolean sizeMismatch = false;
        for (ImportParser.Dataset d : parsed.datasets()) {
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
        for (ImportParser.Dataset d : parsed.datasets()) {
            Result r = toEntity(d, version, model, fallbackBuild);
            addDeviceWarning(r, warnings);
            results.add(r);
        }
        resultRepo.saveAll(results);

        List<ImportResponse.ImportedResult> created = results.stream()
                .map(r -> new ImportResponse.ImportedResult(
                        r.getId(), r.getModelString(), r.getPpTokens(), r.getTgTokens(), r.getPpTps(), r.getTgTps()))
                .toList();
        return new ImportResponse(created, warnings, false);
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
