package com.spec2test.repo;

import com.spec2test.domain.TestCase;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.transaction.annotation.Transactional;

public interface TestCaseRepository extends JpaRepository<TestCase, Long> {

    List<TestCase> findBySectionIdOrderBySeqInSection(Long sectionId);

    List<TestCase> findByRunIdOrderByGlobalNo(Long runId);

    @Modifying
    @Transactional
    void deleteBySectionId(Long sectionId);
}
