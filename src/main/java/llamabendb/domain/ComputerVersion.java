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
@Table(name = "computer_version")
public class ComputerVersion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "computer_id", nullable = false)
    private Computer computer;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(length = 4000)
    private String description;

    /**
     * Known hardware of this version: special keys "CPU" and "MEM" plus one key
     * per device name (e.g. "Vulkan0", "OPENVINO0_NPU"); values are free text.
     */
    // Nullable: rows created before V4 have no hardware data; null means empty.
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "devices")
    private Map<String, String> devices = new LinkedHashMap<>();

    public Long getId() {
        return id;
    }

    public Computer getComputer() {
        return computer;
    }

    public void setComputer(Computer computer) {
        this.computer = computer;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public Map<String, String> getDevices() {
        return devices;
    }

    public void setDevices(Map<String, String> devices) {
        this.devices = devices;
    }
}
