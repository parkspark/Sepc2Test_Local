package com.spec2test.pipeline;

import com.spec2test.domain.Run;
import com.spec2test.domain.RunPhase;
import com.spec2test.domain.RunStatus;
import com.spec2test.domain.Section;
import com.spec2test.domain.SectionStatus;
import com.spec2test.repo.PageRepository;
import com.spec2test.repo.SectionRepository;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * PROGRESS.md 파싱(app.py의 parse_progress)을 대체한다: run/page/section 테이블 상태에서
 * {phase0,phase1,phase2} 체크리스트를 그때그때 계산한다.
 */
@Component
public class ProgressAssembler {

    private final PageRepository pageRepository;
    private final SectionRepository sectionRepository;

    public ProgressAssembler(PageRepository pageRepository, SectionRepository sectionRepository) {
        this.pageRepository = pageRepository;
        this.sectionRepository = sectionRepository;
    }

    public Map<String, Object> build(Run run) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("phase0", buildPhase0(run));
        result.put("phase1", buildPhase1(run));
        result.put("phase2", buildPhase2(run));
        return result;
    }

    private List<Map<String, String>> buildPhase0(Run run) {
        List<Map<String, String>> items = new ArrayList<>();
        String pageCountText = run.getPageCount() != null ? " (%d페이지)".formatted(run.getPageCount()) : "";
        items.add(item("PDF 페이지 렌더링" + pageCountText, phaseItemStatus(run, RunPhase.RENDERING)));

        int totalPages = pageRepository.countByRunId(run.getId());
        long remaining = pageRepository.countByRunIdAndVisionCaptionIsNull(run.getId());
        String captionText = totalPages > 0 ? " (%d/%d)".formatted(totalPages - remaining, totalPages) : "";
        items.add(item("슬라이드 캡션 생성" + captionText, phaseItemStatus(run, RunPhase.CAPTIONING)));

        items.add(item("섹션 인벤토리 작성", phaseItemStatus(run, RunPhase.SECTIONING)));
        items.add(item("스타일 가이드 작성", phaseItemStatus(run, RunPhase.STYLE_GUIDE)));
        return items;
    }

    private List<Map<String, String>> buildPhase1(Run run) {
        List<Section> sections = sectionRepository.findByRunIdOrderBySectionNo(run.getId());
        List<Map<String, String>> items = new ArrayList<>();
        boolean runningAssigned = false;
        for (Section s : sections) {
            String text = "%03d %s (p.%d-%d)".formatted(s.getSectionNo(), s.getTitle(), s.getPageStart(), s.getPageEnd());
            String status;
            if (s.getStatus() == SectionStatus.DONE) {
                status = "done";
            } else if (s.getStatus() == SectionStatus.BLOCKED) {
                status = "blocked";
            } else if (!runningAssigned && run.getStatus() == RunStatus.RUNNING && run.getPhase() == RunPhase.GENERATING) {
                status = "running";
                runningAssigned = true;
            } else {
                status = "pending";
            }
            items.add(item(text, status));
        }
        return items;
    }

    private List<Map<String, String>> buildPhase2(Run run) {
        List<Map<String, String>> items = new ArrayList<>();
        items.add(item("CSV 병합 → TC_%s.csv (No 전체 재부여)".formatted(run.getSpecName()),
                phaseItemStatus(run, RunPhase.MERGING_CSV)));
        items.add(item("의문점 병합 → 의문점_%s.md".formatted(run.getSpecName()),
                phaseItemStatus(run, RunPhase.MERGING_QUESTIONS)));
        items.add(item("최종 검증 및 커버리지 리포트 작성", phaseItemStatus(run, RunPhase.FINALIZING)));
        items.add(item("완료 처리", run.getPhase() == RunPhase.DONE ? "done" : "pending"));
        return items;
    }

    private String phaseItemStatus(Run run, RunPhase itemPhase) {
        int current = run.getPhase().ordinal();
        int target = itemPhase.ordinal();
        if (current > target) {
            return "done";
        }
        if (current == target && run.getStatus() == RunStatus.RUNNING) {
            return "running";
        }
        return "pending";
    }

    private Map<String, String> item(String text, String status) {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("text", text);
        map.put("status", status);
        return map;
    }
}
