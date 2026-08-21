package com.spec2test.pipeline.steps;

import com.spec2test.csv.TcValidator;
import com.spec2test.domain.TestCase;
import com.spec2test.llm.dto.TestCaseEntry;
import com.spec2test.repo.TestCaseRepository;
import java.util.List;
import org.springframework.stereotype.Component;

/** 병합된 최종 CSV를 --final 모드로 검증한다 (검증 실패해도 진행은 막지 않는다, run_validate(final=True) 이식). */
@Component
public class FinalizeStep {

    private final TestCaseRepository testCaseRepository;
    private final TcValidator validator;

    public FinalizeStep(TestCaseRepository testCaseRepository, TcValidator validator) {
        this.testCaseRepository = testCaseRepository;
        this.validator = validator;
    }

    public TcValidator.ValidationResult validateFinal(Long runId) {
        List<TestCase> rows = testCaseRepository.findByRunIdOrderByGlobalNo(runId);
        List<TestCaseEntry> entries = rows.stream()
                .map(r -> new TestCaseEntry(r.getCategoryMajor(), r.getCategoryMid(), r.getCategoryMinor(),
                        r.getTestItem(), r.getPrecondition(), r.getTestSteps(), r.getExpectedResult(), r.getRemark()))
                .toList();
        return validator.validate(entries, true);
    }
}
