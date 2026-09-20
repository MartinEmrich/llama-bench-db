package llamabendb.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@Entity
@Table(name = "result")
public class Result {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "computer_version_id", nullable = false)
    private ComputerVersion computerVersion;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "model_id", nullable = false)
    private Model model;

    @Column(name = "imported_at", nullable = false)
    private Instant importedAt;

    @Column(name = "model_string", length = 255)
    private String modelString;

    @Column(name = "size_gib_observed")
    private Double sizeGiBObserved;

    @Column(length = 255)
    private String backend;

    @Column(length = 255)
    private String devices;

    @Column(nullable = false)
    private Integer ngl = -1;

    @Column(name = "type_k", nullable = false, length = 32)
    private String typeK = "f16";

    @Column(name = "type_v", nullable = false, length = 32)
    private String typeV = "f16";

    @Column(nullable = false)
    private Boolean fa = false;

    private Integer threads;

    @Column(length = 255)
    private String ts;

    @Column(name = "load_mode", nullable = false, length = 32)
    private String loadMode = "auto";

    @Column(name = "pp_tokens", nullable = false)
    private Integer ppTokens;

    @Column(name = "tg_tokens", nullable = false)
    private Integer tgTokens;

    @Column(name = "pp_tps")
    private Double ppTps;

    @Column(name = "tg_tps")
    private Double tgTps;

    @Column(name = "pp_deviation")
    private Double ppDeviation;

    @Column(name = "tg_deviation")
    private Double tgDeviation;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "params", nullable = false)
    private Map<String, Object> params = new LinkedHashMap<>();

    public Long getId() {
        return id;
    }

    public ComputerVersion getComputerVersion() {
        return computerVersion;
    }

    public void setComputerVersion(ComputerVersion computerVersion) {
        this.computerVersion = computerVersion;
    }

    public Model getModel() {
        return model;
    }

    public void setModel(Model model) {
        this.model = model;
    }

    public Instant getImportedAt() {
        return importedAt;
    }

    public void setImportedAt(Instant importedAt) {
        this.importedAt = importedAt;
    }

    public String getModelString() {
        return modelString;
    }

    public void setModelString(String modelString) {
        this.modelString = modelString;
    }

    public Double getSizeGiBObserved() {
        return sizeGiBObserved;
    }

    public void setSizeGiBObserved(Double sizeGiBObserved) {
        this.sizeGiBObserved = sizeGiBObserved;
    }

    public String getBackend() {
        return backend;
    }

    public void setBackend(String backend) {
        this.backend = backend;
    }

    public String getDevices() {
        return devices;
    }

    public void setDevices(String devices) {
        this.devices = devices;
    }

    public Integer getNgl() {
        return ngl;
    }

    public void setNgl(Integer ngl) {
        this.ngl = ngl;
    }

    public String getTypeK() {
        return typeK;
    }

    public void setTypeK(String typeK) {
        this.typeK = typeK;
    }

    public String getTypeV() {
        return typeV;
    }

    public void setTypeV(String typeV) {
        this.typeV = typeV;
    }

    public Boolean getFa() {
        return fa;
    }

    public void setFa(Boolean fa) {
        this.fa = fa;
    }

    public Integer getThreads() {
        return threads;
    }

    public void setThreads(Integer threads) {
        this.threads = threads;
    }

    public String getTs() {
        return ts;
    }

    public void setTs(String ts) {
        this.ts = ts;
    }

    public String getLoadMode() {
        return loadMode;
    }

    public void setLoadMode(String loadMode) {
        this.loadMode = loadMode;
    }

    public Integer getPpTokens() {
        return ppTokens;
    }

    public void setPpTokens(Integer ppTokens) {
        this.ppTokens = ppTokens;
    }

    public Integer getTgTokens() {
        return tgTokens;
    }

    public void setTgTokens(Integer tgTokens) {
        this.tgTokens = tgTokens;
    }

    public Double getPpTps() {
        return ppTps;
    }

    public void setPpTps(Double ppTps) {
        this.ppTps = ppTps;
    }

    public Double getTgTps() {
        return tgTps;
    }

    public void setTgTps(Double tgTps) {
        this.tgTps = tgTps;
    }

    public Double getPpDeviation() {
        return ppDeviation;
    }

    public void setPpDeviation(Double ppDeviation) {
        this.ppDeviation = ppDeviation;
    }

    public Double getTgDeviation() {
        return tgDeviation;
    }

    public void setTgDeviation(Double tgDeviation) {
        this.tgDeviation = tgDeviation;
    }

    public Map<String, Object> getParams() {
        return params;
    }

    public void setParams(Map<String, Object> params) {
        this.params = params;
    }
}
