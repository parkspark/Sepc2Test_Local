package com.spec2test.pipeline.steps;

import com.spec2test.domain.Document;
import com.spec2test.domain.DocumentKind;
import com.spec2test.domain.Question;
import com.spec2test.domain.Section;
import com.spec2test.llm.LlmException;
import com.spec2test.llm.LlmGateway;
import com.spec2test.llm.PromptLibrary;
import com.spec2test.repo.DocumentRepository;
import com.spec2test.repo.QuestionRepository;
import com.spec2test.repo.SectionRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 섹션별 의문점을 기획서 등장 순서로 병합하고, 섹션이 2개 이상이면 LLM으로 중복을 제거한다
 * (merge_questions 이식). LLM 호출이 실패하면 단순 병합 결과로 대체한다.
 */
@Component
public class MergeQuestionsStep {

    private static final Logger log = LoggerFactory.getLogger(MergeQuestionsStep.class);

    private final SectionRepository sectionRepository;
    private final QuestionRepository questionRepository;
    private final DocumentRepository documentRepository;
    private final LlmGateway llmGateway;
    private final PromptLibrary prompts;

    public MergeQuestionsStep(SectionRepository sectionRepository, QuestionRepository questionRepository,
            DocumentRepository documentRepository, LlmGateway llmGateway, PromptLibrary prompts) {
        this.sectionRepository = sectionRepository;
        this.questionRepository = questionRepository;
        this.documentRepository = documentRepository;
        this.llmGateway = llmGateway;
        this.prompts = prompts;
    }

    public String merge(Long runId) {
        List<Section> sections = sectionRepository.findByRunIdOrderBySectionNo(runId);
        List<String> chunks = new ArrayList<>();
        for (Section section : sections) {
            List<Question> questions = questionRepository.findBySectionIdOrderBySeq(section.getId());
            if (questions.isEmpty()) {
                continue;
            }
            chunks.add(renderSectionBlock(section, questions));
        }

        String merged = chunks.isEmpty() ? "(의문점 없음)\n" : String.join("\n\n", chunks) + "\n";

        if (chunks.size() > 1) {
            try {
                merged = llmGateway.text(null, prompts.render("question_dedup", Map.of("merged", merged)));
            } catch (LlmException e) {
                log.warn("의문점 중복 병합용 LLM 호출 실패, 단순 병합으로 대체: {}", e.getMessage());
            }
        }

        Document document = documentRepository.findByRunIdAndKind(runId, DocumentKind.MERGED_QUESTIONS)
                .orElseGet(() -> {
                    Document d = new Document();
                    d.setRunId(runId);
                    d.setKind(DocumentKind.MERGED_QUESTIONS);
                    return d;
                });
        document.setContent(merged);
        documentRepository.save(document);
        return merged;
    }

    private String renderSectionBlock(Section section, List<Question> questions) {
        String header = "## %03d %s".formatted(section.getSectionNo(), section.getTitle());
        String items = questions.stream()
                .map(q -> "- %s (출처: %s)".formatted(q.getText(), q.getSource()))
                .collect(Collectors.joining("\n"));
        return header + "\n" + items;
    }
}
