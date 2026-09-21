package llamabendb.domain;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "computer", uniqueConstraints = @UniqueConstraint(name = "uq_computer_name", columnNames = "name"))
public class Computer {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 128)
    private String name;

    // Not unique: a hostname may be reused on a successor system. When several
    // computers share one, the newest version timestamp wins on import.
    @Column(length = 128)
    private String hostname;

    @OneToMany(mappedBy = "computer", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<ComputerVersion> versions = new ArrayList<>();

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getHostname() {
        return hostname;
    }

    public void setHostname(String hostname) {
        this.hostname = hostname;
    }

    public List<ComputerVersion> getVersions() {
        return versions;
    }
}
