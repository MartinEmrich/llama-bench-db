package llamabendb.repo;

import llamabendb.domain.ComputerVersion;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ComputerVersionRepository extends JpaRepository<ComputerVersion, Long> {

    List<ComputerVersion> findByComputerIdOrderByCreatedAtDesc(Long computerId);

    boolean existsByComputerId(Long computerId);
}
