package com.spec2test.repo;

import com.spec2test.domain.Page;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PageRepository extends JpaRepository<Page, Long> {

    boolean existsByRunIdAndPageNo(Long runId, Integer pageNo);

    Optional<Page> findByRunIdAndPageNo(Long runId, Integer pageNo);

    int countByRunId(Long runId);

    long countByRunIdAndVisionCaptionIsNull(Long runId);

    @Query("select p.pageNo as pageNo, p.textLayer as textLayer, p.visionCaption as visionCaption "
            + "from Page p where p.runId = :runId order by p.pageNo")
    List<PageTextView> findTextViewsByRunId(@Param("runId") Long runId);

    interface PageTextView {
        Integer getPageNo();

        String getTextLayer();

        String getVisionCaption();
    }
}
