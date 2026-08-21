package com.spec2test.pipeline.steps;

import com.spec2test.config.Spec2TestProperties;
import com.spec2test.domain.Section;
import com.spec2test.llm.LlmException;
import com.spec2test.llm.LlmGateway;
import com.spec2test.llm.PromptLibrary;
import com.spec2test.llm.dto.SectionEntry;
import com.spec2test.llm.dto.SectionInventoryResult;
import com.spec2test.repo.SectionRepository;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 기획서 전체 페이지를 중분류(화면/PU) 단위 섹션으로 나눈다 (build_sections_md 이식).
 * 모델이 빠뜨린 페이지 갭은 인접 섹션에 자동으로 편입시키고(_autoclose_gap_pages), 그래도 남으면
 * 최대 2회까지 모델에게 누락 페이지를 알려 재시도한다.
 */
@Component
public class SectionInventoryStep {

    private static final Logger log = LoggerFactory.getLogger(SectionInventoryStep.class);
    private static final int MAX_ATTEMPTS = 2;
    private static final Pattern INVALID_TITLE_CHARS = Pattern.compile("[\\\\/:*?\"<>|]");

    private final SectionRepository sectionRepository;
    private final PageContextAssembler pageContextAssembler;
    private final LlmGateway llmGateway;
    private final PromptLibrary prompts;
    private final Spec2TestProperties properties;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public SectionInventoryStep(SectionRepository sectionRepository, PageContextAssembler pageContextAssembler,
            LlmGateway llmGateway, PromptLibrary prompts, Spec2TestProperties properties) {
        this.sectionRepository = sectionRepository;
        this.pageContextAssembler = pageContextAssembler;
        this.llmGateway = llmGateway;
        this.prompts = prompts;
        this.properties = properties;
    }

    public List<Section> buildSections(Long runId, int pageCount) {
        List<Section> existing = sectionRepository.findByRunIdOrderBySectionNo(runId);
        if (!existing.isEmpty()) {
            return existing;
        }

        String combinedAll = pageContextAssembler.combinedAll(runId);
        String userPrompt = prompts.render("section_inventory",
                Map.of("nPages", pageCount, "combinedAll", combinedAll));

        List<Message> messages = new ArrayList<>(List.of(new UserMessage(userPrompt)));
        List<SectionEntry> sections = List.of();
        boolean closed = false;

        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            SectionInventoryResult result = llmGateway.json(messages, SectionInventoryResult.class,
                    properties.getSectionsNumCtx());
            sections = result.sections();

            List<Integer> gaps = coverageGaps(sections, pageCount);
            if (!gaps.isEmpty()) {
                sections = autoCloseGaps(sections, pageCount);
                gaps = coverageGaps(sections, pageCount);
            }
            if (gaps.isEmpty()) {
                closed = true;
                break;
            }
            log.warn("섹션 인벤토리가 {}개 페이지를 누락함: {} (시도 {}/{})", gaps.size(), gaps, attempt, MAX_ATTEMPTS);
            messages.add(new AssistantMessage(toJson(new SectionInventoryResult(sections))));
            messages.add(new UserMessage(
                    prompts.render("section_inventory_gap_retry", Map.of("gaps", gaps.toString()))));
        }

        if (!closed) {
            sections = autoCloseGaps(sections, pageCount);
            List<Integer> gaps = coverageGaps(sections, pageCount);
            if (!gaps.isEmpty()) {
                throw new LlmException(
                        "섹션 인벤토리가 %d페이지 중 %d개를 계속 누락한다: %s".formatted(pageCount, gaps.size(), gaps));
            }
        }

        List<Section> saved = new ArrayList<>();
        int sectionNo = 1;
        for (SectionEntry entry : sections) {
            Section section = new Section();
            section.setRunId(runId);
            section.setSectionNo(sectionNo++);
            section.setTitle(sanitizeTitle(entry.title()));
            section.setPageStart(entry.pageStart());
            section.setPageEnd(entry.pageEnd());
            section.setCategoryHint(entry.categoryHint());
            saved.add(sectionRepository.save(section));
        }
        return saved;
    }

    private List<Integer> coverageGaps(List<SectionEntry> sections, int pageCount) {
        Set<Integer> covered = new LinkedHashSet<>();
        for (SectionEntry s : sections) {
            if (s.pageStart() == null || s.pageEnd() == null) {
                log.warn("섹션 '{}'에 page_start/page_end가 없어 커버리지 계산에서 제외한다: {}", s.title(), s);
                continue;
            }
            for (int p = s.pageStart(); p <= s.pageEnd(); p++) {
                covered.add(p);
            }
        }
        List<Integer> gaps = new ArrayList<>();
        for (int p = 1; p <= pageCount; p++) {
            if (!covered.contains(p)) {
                gaps.add(p);
            }
        }
        return gaps;
    }

    /**
     * 콘텐츠 없는 챕터 구분용 타이틀 슬라이드처럼, 인접 섹션에 붙일 수 있는 갭 페이지는 자동으로 편입시킨다.
     * 양쪽 다 인접 섹션이 없는 고립된 갭은 그대로 남겨 호출자가 처리하게 한다.
     */
    private List<SectionEntry> autoCloseGaps(List<SectionEntry> sections, int pageCount) {
        List<SectionEntry> mutable = new ArrayList<>(sections);
        mutable.removeIf(s -> s.pageStart() == null || s.pageEnd() == null);
        for (int gap : coverageGaps(mutable, pageCount)) {
            mutable.sort((a, b) -> Integer.compare(a.pageStart(), b.pageStart()));
            int gapPage = gap;
            int nextIdx = indexWhere(mutable, s -> s.pageStart().equals(gapPage + 1));
            int prevIdx = indexWhere(mutable, s -> s.pageEnd().equals(gapPage - 1));
            if (nextIdx >= 0) {
                SectionEntry s = mutable.get(nextIdx);
                mutable.set(nextIdx, new SectionEntry(s.no(), s.title(), gapPage, s.pageEnd(), s.categoryHint()));
            } else if (prevIdx >= 0) {
                SectionEntry s = mutable.get(prevIdx);
                mutable.set(prevIdx, new SectionEntry(s.no(), s.title(), s.pageStart(), gapPage, s.categoryHint()));
            }
        }
        return mutable;
    }

    private static int indexWhere(List<SectionEntry> list, java.util.function.Predicate<SectionEntry> predicate) {
        for (int i = 0; i < list.size(); i++) {
            if (predicate.test(list.get(i))) {
                return i;
            }
        }
        return -1;
    }

    private static String sanitizeTitle(String title) {
        String cleaned = INVALID_TITLE_CHARS.matcher(title).replaceAll("_").strip();
        while (cleaned.endsWith(".")) {
            cleaned = cleaned.substring(0, cleaned.length() - 1);
        }
        return cleaned.isBlank() ? "섹션" : cleaned;
    }

    private String toJson(SectionInventoryResult result) {
        try {
            return objectMapper.writeValueAsString(result);
        } catch (Exception e) {
            throw new LlmException("섹션 인벤토리 JSON 직렬화 실패", e);
        }
    }

    /** state/coverage_report.md 프롬프트(§7 감사)에 넣을 사람이 읽는 형태의 섹션 인벤토리 텍스트. */
    public String renderSectionsMd(String specName, List<Section> sections) {
        StringBuilder sb = new StringBuilder("기획서명: ").append(specName).append("\n\n");
        sb.append("번호 | 섹션 제목 | 페이지 범위 | 분류 후보\n");
        sb.append(sections.stream()
                .map(s -> "%03d | %s | p.%d-%d | %s".formatted(
                        s.getSectionNo(), s.getTitle(), s.getPageStart(), s.getPageEnd(), s.getCategoryHint()))
                .collect(Collectors.joining("\n")));
        return sb.toString();
    }
}
