package llamabendb.api;

import llamabendb.api.dto.ModelDto;
import llamabendb.domain.HfModelId;
import llamabendb.domain.Model;
import llamabendb.domain.QuantSortKey;
import llamabendb.repo.ModelRepository;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/models")
public class ModelController {

    public record CreateModelRequest(String modelId, String name, String quantization, Double sizeGiB) {
    }

    public record UpdateModelRequest(String name, String modelId, String quantization, Double sizeGiB) {
    }

    private final ModelRepository modelRepo;
    private final ResultRepository resultRepo;

    public ModelController(ModelRepository modelRepo, ResultRepository resultRepo) {
        this.modelRepo = modelRepo;
        this.resultRepo = resultRepo;
    }

    @GetMapping
    public List<ModelDto> list(@RequestParam(required = false) String q) {
        List<Model> models = (q == null || q.isBlank())
                ? modelRepo.findAllByOrderByNameAscQuantSortKeyAsc()
                : modelRepo.search(q.strip());
        return models.stream().map(this::toDto).toList();
    }

    @PostMapping
    @Transactional
    @ResponseStatus(HttpStatus.CREATED)
    public ModelDto create(@RequestBody CreateModelRequest req) {
        HfModelId.Parsed parsed = HfModelId.parse(req.modelId(), req.quantization());
        if (modelRepo.existsByModelIdAndQuantization(parsed.repo(), parsed.quant())) {
            throw new ConflictException("model '" + parsed.repo() + ":" + parsed.quant() + "' already exists");
        }
        Model m = new Model();
        m.setName(req.name() != null && !req.name().isBlank() ? req.name().strip() : parsed.baseName());
        m.setModelId(parsed.repo());
        m.setQuantization(parsed.quant());
        m.setSizeGiB(req.sizeGiB());
        m.setQuantSortKey(QuantSortKey.of(parsed.quant()));
        return toDto(modelRepo.save(m));
    }

    @PutMapping("/{id}")
    @Transactional
    public ModelDto update(@PathVariable Long id, @RequestBody UpdateModelRequest req) {
        Model m = modelRepo.findById(id).orElseThrow(() -> new NotFoundException("model " + id + " not found"));
        if (req.name() != null && !req.name().isBlank()) {
            m.setName(req.name().strip());
        }
        if (req.modelId() != null && !req.modelId().isBlank()) {
            HfModelId.Parsed parsed = HfModelId.parse(req.modelId(), req.quantization());
            if (!parsed.repo().equals(m.getModelId()) || !parsed.quant().equals(m.getQuantization())) {
                if (modelRepo.existsByModelIdAndQuantization(parsed.repo(), parsed.quant())) {
                    throw new ConflictException("model '" + parsed.repo() + ":" + parsed.quant() + "' already exists");
                }
            }
            m.setModelId(parsed.repo());
            m.setQuantization(parsed.quant());
        } else if (req.quantization() != null && !req.quantization().isBlank()) {
            String quant = req.quantization().strip();
            if (!quant.equals(m.getQuantization())
                    && modelRepo.existsByModelIdAndQuantization(m.getModelId(), quant)) {
                throw new ConflictException("model '" + m.getModelId() + ":" + quant + "' already exists");
            }
            m.setQuantization(quant);
        }
        if (req.sizeGiB() != null) {
            m.setSizeGiB(req.sizeGiB());
        }
        m.setQuantSortKey(QuantSortKey.of(m.getQuantization()));
        return toDto(modelRepo.save(m));
    }

    @DeleteMapping("/{id}")
    @Transactional
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id) {
        Model m = modelRepo.findById(id).orElseThrow(() -> new NotFoundException("model " + id + " not found"));
        if (resultRepo.countByModelId(id) > 0) {
            throw new ConflictException("model '" + m.getName() + "' has results and cannot be deleted");
        }
        modelRepo.delete(m);
    }

    private ModelDto toDto(Model m) {
        return new ModelDto(m.getId(), m.getName(), m.getModelId(), m.getQuantization(), m.getSizeGiB());
    }
}
