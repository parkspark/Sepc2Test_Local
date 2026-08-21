package com.spec2test.pipeline.steps;

import com.spec2test.domain.Page;
import com.spec2test.llm.LlmGateway;
import com.spec2test.llm.PromptLibrary;
import com.spec2test.repo.PageRepository;
import java.util.function.IntConsumer;
import org.springframework.stereotype.Component;

/**
 * 각 페이지 이미지를 비전 모델로 캡션한다 (caption_pages 이식). 이미 캡션이 있는 페이지는 건너뛴다.
 */
@Component
public class CaptionStep {

    private final PageRepository pageRepository;
    private final LlmGateway llmGateway;
    private final PromptLibrary prompts;

    public CaptionStep(PageRepository pageRepository, LlmGateway llmGateway, PromptLibrary prompts) {
        this.pageRepository = pageRepository;
        this.llmGateway = llmGateway;
        this.prompts = prompts;
    }

    public void captionAll(Long runId, int pageCount, IntConsumer onPageDone) {
        String promptText = prompts.raw("vision_caption");
        for (int pageNo = 1; pageNo <= pageCount; pageNo++) {
            int currentPageNo = pageNo;
            Page page = pageRepository.findByRunIdAndPageNo(runId, pageNo)
                    .orElseThrow(() -> new IllegalStateException(
                            "page %d not found for run %d".formatted(currentPageNo, runId)));
            if (page.getVisionCaption() != null) {
                continue;
            }
            String caption = llmGateway.caption(promptText, page.getPng());
            page.setVisionCaption(caption);
            pageRepository.save(page);
            onPageDone.accept(pageNo);
        }
    }
}
