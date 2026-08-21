package com.spec2test.repo;

import com.spec2test.domain.Section;
import com.spec2test.domain.SectionStatus;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SectionRepository extends JpaRepository<Section, Long> {

    List<Section> findByRunIdOrderBySectionNo(Long runId);

    Optional<Section> findFirstByRunIdAndStatusOrderBySectionNo(Long runId, SectionStatus status);

    long countByRunId(Long runId);

    long countByRunIdAndStatusIn(Long runId, List<SectionStatus> statuses);
}
