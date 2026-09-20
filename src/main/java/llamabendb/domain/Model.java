package llamabendb.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

@Entity
@Table(name = "model", uniqueConstraints = @UniqueConstraint(name = "uq_model_id_quant", columnNames = {"model_id", "quantization"}))
public class Model {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 255)
    private String name;

    @Column(name = "model_id", nullable = false, length = 255)
    private String modelId;

    @Column(nullable = false, length = 64)
    private String quantization;

    @Column(name = "size_gib")
    private Double sizeGiB;

    @Column(name = "quant_sort_key", nullable = false, length = 128)
    private String quantSortKey;

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getModelId() {
        return modelId;
    }

    public void setModelId(String modelId) {
        this.modelId = modelId;
    }

    public String getQuantization() {
        return quantization;
    }

    public void setQuantization(String quantization) {
        this.quantization = quantization;
    }

    public Double getSizeGiB() {
        return sizeGiB;
    }

    public void setSizeGiB(Double sizeGiB) {
        this.sizeGiB = sizeGiB;
    }

    public String getQuantSortKey() {
        return quantSortKey;
    }

    public void setQuantSortKey(String quantSortKey) {
        this.quantSortKey = quantSortKey;
    }
}
