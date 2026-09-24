package llamabendb.api;

import llamabendb.api.dto.ComputerDetailDto;
import llamabendb.api.dto.ComputerListDto;
import llamabendb.api.dto.VersionDto;
import llamabendb.domain.Computer;
import llamabendb.domain.ComputerVersion;
import llamabendb.repo.ComputerRepository;
import llamabendb.repo.ComputerVersionRepository;
import llamabendb.repo.ResultRepository;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/computers")
public class ComputerController {

    public record CreateComputerRequest(String name, String hostname, String description) {
    }

    public record UpdateComputerRequest(String name, String hostname) {
    }

    public record CreateVersionRequest(String description, Map<String, String> devices) {
    }

    public record UpdateVersionRequest(String description, Map<String, String> devices) {
    }

    private final ComputerRepository computerRepo;
    private final ComputerVersionRepository versionRepo;
    private final ResultRepository resultRepo;

    public ComputerController(ComputerRepository computerRepo, ComputerVersionRepository versionRepo, ResultRepository resultRepo) {
        this.computerRepo = computerRepo;
        this.versionRepo = versionRepo;
        this.resultRepo = resultRepo;
    }

    @GetMapping
    public List<ComputerListDto> list() {
        Map<Long, List<ComputerVersion>> versionsByComputer = versionRepo.findAll().stream()
                .collect(Collectors.groupingBy(cv -> cv.getComputer().getId()));
        return computerRepo.findAllByOrderByNameAsc().stream()
                .map(c -> toListDto(c, versionsByComputer.getOrDefault(c.getId(), List.of())))
                .toList();
    }

    @GetMapping("/{id}")
    public ComputerDetailDto detail(@PathVariable Long id) {
        Computer c = computerRepo.findById(id).orElseThrow(() -> new NotFoundException("computer " + id + " not found"));
        List<VersionDto> versions = versionRepo.findByComputerIdOrderByCreatedAtDesc(id).stream()
                .map(this::toVersionDto)
                .toList();
        return new ComputerDetailDto(c.getId(), c.getName(), c.getHostname(), versions);
    }

    @PostMapping
    @Transactional
    @ResponseStatus(HttpStatus.CREATED)
    public ComputerListDto create(@RequestBody CreateComputerRequest req) {
        String name = requireName(req.name());
        if (computerRepo.existsByName(name)) {
            throw new ConflictException("a computer named '" + name + "' already exists");
        }
        Computer c = new Computer();
        c.setName(name);
        c.setHostname(normalizeHostname(req.hostname()));
        c = computerRepo.save(c);
        addVersion(c, req.description(), null);
        return toListDto(c, versionRepo.findByComputerIdOrderByCreatedAtDesc(c.getId()));
    }

    @PutMapping("/{id}")
    @Transactional
    public ComputerListDto update(@PathVariable Long id, @RequestBody UpdateComputerRequest req) {
        Computer c = computerRepo.findById(id).orElseThrow(() -> new NotFoundException("computer " + id + " not found"));
        String name = requireName(req.name());
        if (!name.equals(c.getName()) && computerRepo.existsByName(name)) {
            throw new ConflictException("a computer named '" + name + "' already exists");
        }
        c.setName(name);
        // null = unchanged, blank = cleared (the form always sends both fields)
        if (req.hostname() != null) {
            c.setHostname(normalizeHostname(req.hostname()));
        }
        return toListDto(c, versionRepo.findByComputerIdOrderByCreatedAtDesc(id));
    }

    @DeleteMapping("/{id}")
    @Transactional
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id) {
        Computer c = computerRepo.findById(id).orElseThrow(() -> new NotFoundException("computer " + id + " not found"));
        for (ComputerVersion v : versionRepo.findByComputerIdOrderByCreatedAtDesc(id)) {
            if (resultRepo.existsByComputerVersionId(v.getId())) {
                throw new ConflictException("computer '" + c.getName() + "' has results and cannot be deleted");
            }
        }
        computerRepo.delete(c);
    }

    @GetMapping("/{id}/versions")
    public List<VersionDto> versions(@PathVariable Long id) {
        if (!computerRepo.existsById(id)) {
            throw new NotFoundException("computer " + id + " not found");
        }
        return versionRepo.findByComputerIdOrderByCreatedAtDesc(id).stream()
                .map(this::toVersionDto)
                .toList();
    }

    @PostMapping("/{id}/versions")
    @Transactional
    @ResponseStatus(HttpStatus.CREATED)
    public VersionDto addVersion(@PathVariable Long id, @RequestBody CreateVersionRequest req) {
        Computer c = computerRepo.findById(id).orElseThrow(() -> new NotFoundException("computer " + id + " not found"));
        return toVersionDto(addVersion(c, req.description(), req.devices()));
    }

    /** Edits an existing version's description and/or hardware map. null = unchanged. */
    @PutMapping("/{id}/versions/{versionId}")
    @Transactional
    public VersionDto updateVersion(@PathVariable Long id, @PathVariable Long versionId,
                                    @RequestBody UpdateVersionRequest req) {
        if (!computerRepo.existsById(id)) {
            throw new NotFoundException("computer " + id + " not found");
        }
        ComputerVersion v = versionRepo.findById(versionId)
                .orElseThrow(() -> new NotFoundException("computer version " + versionId + " not found"));
        if (!v.getComputer().getId().equals(id)) {
            throw new BadRequestException("version " + versionId + " does not belong to computer " + id);
        }
        // null = unchanged, blank = cleared (the form always sends both fields)
        if (req.description() != null) {
            v.setDescription(req.description().isBlank() ? null : req.description().strip());
        }
        if (req.devices() != null) {
            v.setDevices(normalizeDevices(req.devices()));
        }
        return toVersionDto(v);
    }

    private ComputerVersion addVersion(Computer c, String description, Map<String, String> devices) {
        ComputerVersion v = new ComputerVersion();
        v.setComputer(c);
        v.setCreatedAt(Instant.now());
        v.setDescription(description);
        v.setDevices(normalizeDevices(devices));
        return versionRepo.save(v);
    }

    /** Trims entries and drops ones with a blank key or value. */
    private static Map<String, String> normalizeDevices(Map<String, String> in) {
        Map<String, String> out = new LinkedHashMap<>();
        if (in != null) {
            for (Map.Entry<String, String> e : in.entrySet()) {
                String k = e.getKey() == null ? "" : e.getKey().strip();
                String val = e.getValue() == null ? "" : e.getValue().strip();
                if (!k.isEmpty() && !val.isEmpty()) {
                    out.put(k, val);
                }
            }
        }
        return out;
    }

    private VersionDto toVersionDto(ComputerVersion v) {
        return new VersionDto(v.getId(), v.getCreatedAt(), v.getDescription(),
                v.getDevices() == null ? Map.of() : v.getDevices());
    }

    private ComputerListDto toListDto(Computer c, List<ComputerVersion> versions) {
        Instant latest = versions.stream()
                .map(ComputerVersion::getCreatedAt)
                .max(Instant::compareTo)
                .orElse(null);
        return new ComputerListDto(c.getId(), c.getName(), c.getHostname(), versions.size(), latest);
    }

    private String requireName(String name) {
        if (name == null || name.isBlank()) {
            throw new BadRequestException("name is required");
        }
        return name.strip();
    }

    private String normalizeHostname(String hostname) {
        return hostname == null || hostname.isBlank() ? null : hostname.strip();
    }
}
