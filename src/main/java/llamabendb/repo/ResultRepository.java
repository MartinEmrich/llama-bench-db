package llamabendb.repo;

import llamabendb.api.dto.ResultDto;
import llamabendb.domain.Result;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ResultRepository extends JpaRepository<Result, Long>, JpaSpecificationExecutor<Result> {

    boolean existsByComputerVersionId(Long computerVersionId);

    long countByModelId(Long modelId);

    @Query("""
            select new llamabendb.api.dto.ResultDto(
                r.id, r.importedAt,
                comp.name, cv.createdAt,
                m.name, m.modelId, m.quantization,
                r.modelString, r.sizeGiBObserved, r.backend, r.devices,
                r.ngl, r.typeK, r.typeV, r.fa, r.threads, r.ts, r.loadMode,
                r.ppTokens, r.tgTokens, r.ppTps, r.tgTps, r.ppDeviation, r.tgDeviation,
                r.params
            ) from Result r
            join r.computerVersion cv
            join cv.computer comp
            join r.model m
            where (:computerId is null or comp.id = :computerId)
              and (:versionId is null or cv.id = :versionId)
              and (:modelId is null or m.id = :modelId)
              and (:quant is null or m.quantization = :quant)
              and (:ppMin is null or r.ppTokens >= :ppMin)
              and (:ppMax is null or r.ppTokens <= :ppMax)
              and (:tgMin is null or r.tgTokens >= :tgMin)
              and (:tgMax is null or r.tgTokens <= :tgMax)
              and (:ppTpsMin is null or r.ppTps >= :ppTpsMin)
              and (:ppTpsMax is null or r.ppTps <= :ppTpsMax)
              and (:tgTpsMin is null or r.tgTps >= :tgTpsMin)
              and (:tgTpsMax is null or r.tgTps <= :tgTpsMax)
            """)
    Page<ResultDto> search(
            @Param("computerId") Long computerId,
            @Param("versionId") Long versionId,
            @Param("modelId") Long modelId,
            @Param("quant") String quant,
            @Param("ppMin") Integer ppMin,
            @Param("ppMax") Integer ppMax,
            @Param("tgMin") Integer tgMin,
            @Param("tgMax") Integer tgMax,
            @Param("ppTpsMin") Double ppTpsMin,
            @Param("ppTpsMax") Double ppTpsMax,
            @Param("tgTpsMin") Double tgTpsMin,
            @Param("tgTpsMax") Double tgTpsMax,
            Pageable pageable);
}
