package llamabendb.api;

import llamabendb.api.dto.ExportDto;
import llamabendb.domain.Computer;
import llamabendb.domain.ComputerVersion;
import llamabendb.domain.Model;
import llamabendb.domain.QuantSortKey;
import llamabendb.domain.Result;
import llamabendb.repo.ComputerRepository;
import llamabendb.repo.ComputerVersionRepository;
import llamabendb.repo.ModelRepository;
import llamabendb.repo.ResultRepository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class ExportController {

    private final ComputerRepository computerRepo;
    private final ComputerVersionRepository versionRepo;
    private final ModelRepository modelRepo;
    private final ResultRepository resultRepo;

    public ExportController(ComputerRepository computerRepo, ComputerVersionRepository versionRepo,
                            ModelRepository modelRepo, ResultRepository resultRepo) {
        this.computerRepo = computerRepo;
        this.versionRepo = versionRepo;
        this.modelRepo = modelRepo;
        this.resultRepo = resultRepo;
    }

    @GetMapping("/export")
    public ExportDto export() {
        List<ExportDto.ComputerExport> computers = computerRepo.findAll().stream()
                .map(c -> new ExportDto.ComputerExport(
                        c.getId(), c.getName(), c.getHostname(),
                        versionRepo.findByComputerIdOrderByCreatedAtDesc(c.getId()).stream()
                                .map(v -> new ExportDto.VersionExport(v.getId(), c.getId(), v.getCreatedAt(),
                                        v.getDescription(), v.getDevices() == null ? Map.of() : v.getDevices()))
                                .toList()))
                .toList();
        List<ExportDto.ModelExport> models = modelRepo.findAll().stream()
                .map(m -> new ExportDto.ModelExport(m.getId(), m.getName(), m.getModelId(), m.getQuantization(), m.getSizeGiB()))
                .toList();
        List<ExportDto.ResultExport> results = resultRepo.findAll().stream()
                .map(r -> new ExportDto.ResultExport(
                        r.getId(), r.getComputerVersion().getId(), r.getModel().getId(), r.getImportedAt(),
                        r.getModelString(), r.getSizeGiBObserved(), r.getBackend(), r.getDevices(),
                        r.getNgl(), r.getTypeK(), r.getTypeV(), r.getFa(), r.getThreads(), r.getTs(), r.getLoadMode(),
                        r.getPpTokens(), r.getTgTokens(), r.getPpTps(), r.getTgTps(), r.getPpDeviation(), r.getTgDeviation(),
                        r.getBuild(), r.getParams()))
                .toList();
        return new ExportDto(Instant.now(), computers, models, results);
    }

    @PostMapping("/import")
    @Transactional
    public Map<String, Integer> restore(@RequestBody ExportDto in) {
        if (in == null || in.computers() == null || in.models() == null || in.results() == null) {
            throw new BadRequestException("malformed import payload");
        }
        resultRepo.deleteAllInBatch();
        modelRepo.deleteAllInBatch();
        versionRepo.deleteAllInBatch();
        computerRepo.deleteAllInBatch();

        Map<Long, Computer> computersById = new HashMap<>();
        for (ExportDto.ComputerExport c : in.computers()) {
            Computer comp = new Computer();
            comp.setName(c.name());
            comp.setHostname(c.hostname());
            comp = computerRepo.save(comp);
            computersById.put(c.id(), comp);
        }
        Map<Long, ComputerVersion> versionsById = new HashMap<>();
        for (ExportDto.ComputerExport c : in.computers()) {
            for (ExportDto.VersionExport v : c.versions()) {
                Computer computer = computersById.get(v.computerId());
                if (computer == null) {
                    throw new BadRequestException("version " + v.id() + " references unknown computer " + v.computerId());
                }
                ComputerVersion cv = new ComputerVersion();
                cv.setComputer(computer);
                cv.setCreatedAt(v.createdAt());
                cv.setDescription(v.description());
                cv.setDevices(v.devices() != null ? new HashMap<>(v.devices()) : new HashMap<>());
                cv = versionRepo.save(cv);
                versionsById.put(v.id(), cv);
            }
        }
        Map<Long, Model> modelsById = new HashMap<>();
        for (ExportDto.ModelExport m : in.models()) {
            Model model = new Model();
            model.setName(m.name());
            model.setModelId(m.modelId());
            model.setQuantization(m.quantization());
            model.setSizeGiB(m.sizeGiB());
            model.setQuantSortKey(QuantSortKey.of(m.quantization()));
            model = modelRepo.save(model);
            modelsById.put(m.id(), model);
        }
        int count = 0;
        for (ExportDto.ResultExport r : in.results()) {
            ComputerVersion version = versionsById.get(r.computerVersionId());
            Model model = modelsById.get(r.modelId());
            if (version == null || model == null) {
                throw new BadRequestException("result " + r.id() + " references an unknown computer version or model");
            }
            Result res = new Result();
            res.setComputerVersion(version);
            res.setModel(model);
            res.setImportedAt(r.importedAt());
            res.setModelString(r.modelString());
            res.setSizeGiBObserved(r.sizeGiBObserved());
            res.setBackend(r.backend());
            res.setDevices(r.devices());
            res.setNgl(r.ngl() != null ? r.ngl() : -1);
            res.setTypeK(r.typeK() != null ? r.typeK() : "f16");
            res.setTypeV(r.typeV() != null ? r.typeV() : "f16");
            res.setFa(Boolean.TRUE.equals(r.fa()));
            res.setThreads(r.threads());
            res.setTs(r.ts());
            res.setLoadMode(r.loadMode() != null ? r.loadMode() : "auto");
            res.setPpTokens(r.ppTokens());
            res.setTgTokens(r.tgTokens());
            res.setPpTps(r.ppTps());
            res.setTgTps(r.tgTps());
            res.setPpDeviation(r.ppDeviation());
            res.setTgDeviation(r.tgDeviation());
            res.setBuild(r.build());
            res.setParams(r.params() != null ? r.params() : Map.of());
            resultRepo.save(res);
            count++;
        }
        return Map.of("computers", computersById.size(), "models", modelsById.size(), "results", count);
    }
}
