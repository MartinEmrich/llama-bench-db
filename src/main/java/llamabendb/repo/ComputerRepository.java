package llamabendb.repo;

import llamabendb.domain.Computer;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface ComputerRepository extends JpaRepository<Computer, Long> {

    boolean existsByName(String name);

    List<Computer> findAllByOrderByNameAsc();

    /**
     * Computers sharing a hostname (case-insensitive), newest version first.
     * A hostname may be reused on a successor system; the one whose latest
     * version is most recent is the machine that ran the benchmark.
     */
    @Query("""
            select c from Computer c join c.versions v
            where lower(c.hostname) = lower(:hostname)
            group by c.id
            order by max(v.createdAt) desc, c.id desc
            """)
    List<Computer> findByHostnameNewestVersionFirst(@Param("hostname") String hostname);
}
