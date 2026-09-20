package llamabendb.repo;

import llamabendb.domain.Model;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface ModelRepository extends JpaRepository<Model, Long> {

    boolean existsByModelIdAndQuantization(String modelId, String quantization);

    @Query("""
            select m from Model m
            where lower(m.name) like lower(concat('%', :q, '%'))
               or lower(m.modelId) like lower(concat('%', :q, '%'))
               or lower(m.quantization) like lower(concat('%', :q, '%'))
            order by m.name, m.quantSortKey
            """)
    List<Model> search(@Param("q") String q);

    List<Model> findAllByOrderByNameAscQuantSortKeyAsc();
}
