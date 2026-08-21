package com.spec2test.repo;

import com.spec2test.domain.Question;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.transaction.annotation.Transactional;

public interface QuestionRepository extends JpaRepository<Question, Long> {

    List<Question> findBySectionIdOrderBySeq(Long sectionId);

    List<Question> findByRunIdOrderBySectionIdAscSeqAsc(Long runId);

    @Modifying
    @Transactional
    void deleteBySectionId(Long sectionId);
}
