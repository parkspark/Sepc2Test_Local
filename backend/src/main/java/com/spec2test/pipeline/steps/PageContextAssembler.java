package com.spec2test.pipeline.steps;

import com.spec2test.repo.PageRepository;
import com.spec2test.repo.PageRepository.PageTextView;
import java.util.List;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * 페이지의 텍스트 레이어+비전 캡션을 combined.md 형식(local_pipeline.py의 caption_pages 출력)으로 조립한다.
 * 파일로 저장하지 않고 필요할 때마다 DB 값에서 조립한다.
 */
@Component
public class PageContextAssembler {

    private final PageRepository pageRepository;

    public PageContextAssembler(PageRepository pageRepository) {
        this.pageRepository = pageRepository;
    }

    public String combinedAll(Long runId) {
        return build(pageRepository.findTextViewsByRunId(runId));
    }

    public String combinedRange(Long runId, int pageStart, int pageEnd) {
        List<PageTextView> views = pageRepository.findTextViewsByRunId(runId).stream()
                .filter(v -> v.getPageNo() >= pageStart && v.getPageNo() <= pageEnd)
                .toList();
        return build(views);
    }

    private String build(List<PageTextView> views) {
        return views.stream().map(this::formatPage).collect(Collectors.joining("\n\n"));
    }

    private String formatPage(PageTextView v) {
        String text = (v.getTextLayer() == null || v.getTextLayer().isBlank()) ? "(없음)" : v.getTextLayer().strip();
        String caption = v.getVisionCaption() == null ? "" : v.getVisionCaption().strip();
        return "## 페이지 %03d\n\n### 텍스트 레이어\n%s\n\n### 시각 설명 (비전 모델)\n%s\n"
                .formatted(v.getPageNo(), text, caption);
    }
}
