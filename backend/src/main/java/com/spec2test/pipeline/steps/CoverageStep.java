package com.spec2test.pipeline.steps;

import com.spec2test.csv.TcCsvWriter;
import com.spec2test.domain.Document;
import com.spec2test.domain.DocumentKind;
import com.spec2test.domain.Run;
import com.spec2test.domain.Section;
import com.spec2test.domain.TestCase;
import com.spec2test.llm.LlmGateway;
import com.spec2test.llm.PromptLibrary;
import com.spec2test.repo.DocumentRepository;
import com.spec2test.repo.SectionRepository;
import com.spec2test.repo.TestCaseRepository;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * RULES.md §7 체크리스트를 근거로 LLM이 자체 감사하는 커버리지 리포트를 생성한다 (write_coverage_report 이식).
 */
@Component
public class CoverageStep {

    private static final int TC_SAMPLE_CHARS = 6000;

    private final SectionRepository sectionRepository;
    private final TestCaseRepository testCaseRepository;
    private final DocumentRepository documentRepository;
    private final SectionInventoryStep sectionInventoryStep;
    private final TcCsvWriter csvWriter;
    private final LlmGateway llmGateway;
    private final PromptLibrary prompts;

    public CoverageStep(SectionRepository sectionRepository, TestCaseRepository testCaseRepository,
            DocumentRepository documentRepository, SectionInventoryStep sectionInventoryStep,
            TcCsvWriter csvWriter, LlmGateway llmGateway, PromptLibrary prompts) {
        this.sectionRepository = sectionRepository;
        this.testCaseRepository = testCaseRepository;
        this.documentRepository = documentRepository;
        this.sectionInventoryStep = sectionInventoryStep;
        this.csvWriter = csvWriter;
        this.llmGateway = llmGateway;
        this.prompts = prompts;
    }

    public String buildReport(Run run) {
        java.util.List<Section> sections = sectionRepository.findByRunIdOrderBySectionNo(run.getId());
        String sectionsMd = sectionInventoryStep.renderSectionsMd(run.getSpecName(), sections);
        String styleGuide = documentRepository.findByRunIdAndKind(run.getId(), DocumentKind.STYLE_GUIDE)
                .map(Document::getContent).orElse("");

        java.util.List<TestCase> rows = testCaseRepository.findByRunIdOrderByGlobalNo(run.getId());
        byte[] csvBytes = csvWriter.write(rows);
        String csvText = new String(csvBytes, StandardCharsets.UTF_8);
        String tcSample = csvText.length() > TC_SAMPLE_CHARS ? csvText.substring(0, TC_SAMPLE_CHARS) : csvText;

        String userPrompt = prompts.render("coverage_audit",
                Map.of("sectionsMd", sectionsMd, "styleGuide", styleGuide, "tcSample", tcSample));
        String report = llmGateway.text(null, userPrompt);

        Document document = documentRepository.findByRunIdAndKind(run.getId(), DocumentKind.COVERAGE_REPORT)
                .orElseGet(() -> {
                    Document d = new Document();
                    d.setRunId(run.getId());
                    d.setKind(DocumentKind.COVERAGE_REPORT);
                    return d;
                });
        document.setContent(report);
        documentRepository.save(document);
        return report;
    }
}
