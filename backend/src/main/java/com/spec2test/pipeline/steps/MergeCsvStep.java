package com.spec2test.pipeline.steps;

import com.spec2test.domain.Section;
import com.spec2test.domain.TestCase;
import com.spec2test.repo.SectionRepository;
import com.spec2test.repo.TestCaseRepository;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 섹션별 test_case에 전역 No를 순서대로 재부여한다 (merge_csv 이식).
 * 실제 CSV 바이트는 저장하지 않고 TcCsvWriter가 global_no 순으로 읽어 그때그때 만든다.
 */
@Component
public class MergeCsvStep {

    private final SectionRepository sectionRepository;
    private final TestCaseRepository testCaseRepository;

    public MergeCsvStep(SectionRepository sectionRepository, TestCaseRepository testCaseRepository) {
        this.sectionRepository = sectionRepository;
        this.testCaseRepository = testCaseRepository;
    }

    public int assignGlobalNumbers(Long runId) {
        List<Section> sections = sectionRepository.findByRunIdOrderBySectionNo(runId);
        int n = 0;
        for (Section section : sections) {
            List<TestCase> rows = testCaseRepository.findBySectionIdOrderBySeqInSection(section.getId());
            for (TestCase row : rows) {
                row.setGlobalNo(++n);
            }
            testCaseRepository.saveAll(rows);
        }
        return n;
    }
}
