package llamabendb.repo;

import llamabendb.domain.Computer;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ComputerRepository extends JpaRepository<Computer, Long> {

    boolean existsByName(String name);

    List<Computer> findAllByOrderByNameAsc();
}
