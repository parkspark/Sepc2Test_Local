package com.spec2test.pipeline.steps;

import com.spec2test.config.Spec2TestProperties;
import com.spec2test.csv.TcValidator;
import com.spec2test.domain.Document;
import com.spec2test.domain.DocumentKind;
import com.spec2test.domain.Question;
import com.spec2test.domain.Section;
import com.spec2test.domain.SectionStatus;
import com.spec2test.domain.TestCase;
import com.spec2test.llm.LlmException;
import com.spec2test.llm.LlmGateway;
import com.spec2test.llm.PromptLibrary;
import com.spec2test.llm.dto.QuestionEntry;
import com.spec2test.llm.dto.SectionGenerationResult;
import com.spec2test.llm.dto.TestCaseEntry;
import com.spec2test.repo.DocumentRepository;
import com.spec2test.repo.QuestionRepository;
import com.spec2test.repo.SectionRepository;
import com.spec2test.repo.TestCaseRepository;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Component;

/**
 * 섹션 하나에 대해 test_case/question을 생성하고 검증-재시도 루프를 돈다 (phase1_section+generate_section 이식).
 * 검증 실패 시 오류 텍스트를 모델에 피드백해 최대 max-validate-retries회까지 재시도하고,
 * 그래도 실패하면 섹션을 BLOCKED로 표시하고 다음 섹션으로 넘어간다 (사람 개입 없이 계속 진행).
 */
@Component
public class SectionGenerationStep {

    private static final Logger log = LoggerFactory.getLogger(SectionGenerationStep.class);
    private static final Pattern INLINE_SOURCE = Pattern.compile("\\s*\\(출처\\s*[:：].*?\\)\\s*$");

    private final SectionRepository sectionRepository;
    private final TestCaseRepository testCaseRepository;
    private final QuestionRepository questionRepository;
    private final DocumentRepository documentRepository;
    private final PageContextAssembler pageContextAssembler;
    private final ReferenceRagStep referenceRagStep;
    private final LlmGateway llmGateway;
    private final PromptLibrary prompts;
    private final TcValidator validator;
    private final Spec2TestProperties properties;

    public SectionGenerationStep(SectionRepository sectionRepository, TestCaseRepository testCaseRepository,
            QuestionRepository questionRepository, DocumentRepository documentRepository,
            PageContextAssembler pageContextAssembler, ReferenceRagStep referenceRagStep, LlmGateway llmGateway, PromptLibrary prompts,
            TcValidator validator, Spec2TestProperties properties) {
        this.sectionRepository = sectionRepository;
        this.testCaseRepository = testCaseRepository;
        this.questionRepository = questionRepository;
        this.documentRepository = documentRepository;
        this.pageContextAssembler = pageContextAssembler;
        this.referenceRagStep = referenceRagStep;
        this.llmGateway = llmGateway;
        this.prompts = prompts;
        this.validator = validator;
        this.properties = properties;
    }

    public Optional<Section> nextPendingSection(Long runId) {
        return sectionRepository.findFirstByRunIdAndStatusOrderBySectionNo(runId, SectionStatus.PENDING);
    }

    public void generate(Long runId, Section section) {
        String rules = prompts.rawFile("rules.md");
        String styleGuide = documentRepository.findByRunIdAndKind(runId, DocumentKind.STYLE_GUIDE)
                .map(Document::getContent).orElse("");
        String notes = documentRepository.findByRunIdAndKind(runId, DocumentKind.NOTES)
                .map(Document::getContent).orElse("");

        String systemPrompt = prompts.render("generation_system",
                Map.of("rules", rules, "styleGuide", styleGuide, "notes", notes.isBlank() ? "(없음)" : notes));
        String context = pageContextAssembler.combinedRange(runId, section.getPageStart(), section.getPageEnd());
        String referenceExamples = referenceRagStep.retrieveExamples(runId,
                section.getTitle() + "\n" + context, properties.getRagTopK());
        String userPrompt = prompts.render("generation_user", Map.of(
                "no", "%03d".formatted(section.getSectionNo()),
                "title", section.getTitle(),
                "pageStart", section.getPageStart(),
                "pageEnd", section.getPageEnd(),
                "context", context,
                "referenceExamples", referenceExamples.isBlank() ? "(관련 참고 TC 없음)" : referenceExamples));

        int maxRetries = properties.getMaxValidateRetries();
        String errorFeedback = null;

        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            List<Message> messages = new ArrayList<>();
            messages.add(new SystemMessage(systemPrompt));
            messages.add(new UserMessage(userPrompt));
            if (errorFeedback != null) {
                messages.add(new UserMessage(prompts.render("generation_retry", Map.of("errorFeedback", errorFeedback))));
            }

            TcValidator.ValidationResult validation;
            SectionGenerationResult result = null;
            try {
                result = llmGateway.json(messages, SectionGenerationResult.class);
                persistAttempt(section, result);
                validation = validator.validate(result.testCases(), false);
            } catch (LlmException e) {
                validation = null;
                errorFeedback = "Ollama 호출 오류: " + e.getMessage();
                log.warn("[run {}] 섹션 {} 생성 시도 {}/{} 중 LLM 오류: {}",
                        runId, section.getSectionNo(), attempt, maxRetries, e.getMessage());
            }

            if (validation != null) {
                if (validation.isValid()) {
                    section.setStatus(SectionStatus.DONE);
                    section.setAttempts(attempt);
                    sectionRepository.save(section);
                    return;
                }
                errorFeedback = validator.formatOutput(validation);
                log.warn("[run {}] 섹션 {} 검증 실패 (시도 {}/{}):\n{}",
                        runId, section.getSectionNo(), attempt, maxRetries, errorFeedback);
            }

            if (attempt == maxRetries) {
                String reason = maxRetries + "회 연속 검증 실패";
                section.setStatus(SectionStatus.BLOCKED);
                section.setBlockedReason(reason);
                section.setAttempts(attempt);
                sectionRepository.save(section);
                appendNotes(runId, "%s %s: %s. 마지막 오류:\n%s"
                        .formatted(section.getSectionNo(), section.getTitle(), reason, errorFeedback));
            }
        }
    }

    private void persistAttempt(Section section, SectionGenerationResult result) {
        testCaseRepository.deleteBySectionId(section.getId());
        questionRepository.deleteBySectionId(section.getId());
        saveTestCases(section, result.testCases());
        saveQuestions(section, result.questions());
    }

    private void saveTestCases(Section section, List<TestCaseEntry> entries) {
        List<TestCase> toSave = new ArrayList<>();
        int seq = 1;
        for (TestCaseEntry e : entries) {
            TestCase tc = new TestCase();
            tc.setRunId(section.getRunId());
            tc.setSectionId(section.getId());
            tc.setSeqInSection(seq++);
            tc.setCategoryMajor(nullToEmpty(e.categoryMajor()));
            tc.setCategoryMid(nullToEmpty(e.categoryMid()));
            tc.setCategoryMinor(nullToEmpty(e.categoryMinor()));
            tc.setTestItem(nullToEmpty(e.testItem()));
            tc.setPrecondition(nullToEmpty(e.precondition()));
            tc.setTestSteps(nullToEmpty(e.testSteps()));
            tc.setExpectedResult(nullToEmpty(e.expectedResult()));
            tc.setRemark(nullToEmpty(e.remark()));
            toSave.add(tc);
        }
        testCaseRepository.saveAll(toSave);
    }

    private void saveQuestions(Section section, List<QuestionEntry> entries) {
        List<Question> toSave = new ArrayList<>();
        int seq = 1;
        for (QuestionEntry e : entries) {
            Question q = new Question();
            q.setRunId(section.getRunId());
            q.setSectionId(section.getId());
            q.setSeq(seq++);
            String text = e.text() == null ? "" : INLINE_SOURCE.matcher(e.text().strip()).replaceAll("");
            q.setText(text);
            q.setSource(nullToEmpty(e.source()));
            toSave.add(q);
        }
        questionRepository.saveAll(toSave);
    }

    private void appendNotes(Long runId, String line) {
        Document doc = documentRepository.findByRunIdAndKind(runId, DocumentKind.NOTES).orElseGet(() -> {
            Document d = new Document();
            d.setRunId(runId);
            d.setKind(DocumentKind.NOTES);
            d.setContent("# NOTES\n");
            return d;
        });
        String ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        String existing = doc.getContent() == null ? "# NOTES\n" : doc.getContent();
        while (existing.endsWith("\n")) {
            existing = existing.substring(0, existing.length() - 1);
        }
        doc.setContent(existing + "\n- [" + ts + "] " + line + "\n");
        doc.setUpdatedAt(Instant.now());
        documentRepository.save(doc);
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
