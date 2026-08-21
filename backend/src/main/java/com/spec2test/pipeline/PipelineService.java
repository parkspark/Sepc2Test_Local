package com.spec2test.pipeline;

import com.spec2test.domain.Run;
import com.spec2test.domain.RunPhase;
import com.spec2test.domain.RunStatus;
import com.spec2test.domain.Section;
import com.spec2test.domain.Upload;
import com.spec2test.domain.UploadKind;
import com.spec2test.logging.RunLogService;
import com.spec2test.pipeline.steps.CaptionStep;
import com.spec2test.pipeline.steps.CoverageStep;
import com.spec2test.pipeline.steps.FinalizeStep;
import com.spec2test.pipeline.steps.MergeCsvStep;
import com.spec2test.pipeline.steps.MergeQuestionsStep;
import com.spec2test.pipeline.steps.PageRenderStep;
import com.spec2test.pipeline.steps.ReferenceRagStep;
import com.spec2test.pipeline.steps.SectionGenerationStep;
import com.spec2test.pipeline.steps.SectionInventoryStep;
import com.spec2test.pipeline.steps.StyleGuideStep;
import com.spec2test.repo.RunRepository;
import com.spec2test.repo.UploadRepository;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 파이프라인을 단일 스레드에서 순차 실행하는 오케스트레이터 (run_one_unit 루프의 상시 실행 버전).
 * 각 단계는 이미 완료된 작업을 스스로 건너뛰도록 설계되어 있어(PageRenderStep/CaptionStep/
 * SectionInventoryStep/StyleGuideStep/SectionGenerationStep의 존재 확인), resume()이 같은
 * runLoop를 재실행하면 DB 상태에서 자연히 이어진다. Stop은 협조적 취소이며(각 주요 체크포인트에서만
 * 확인), 진행 중인 LLM 호출 하나가 끝날 때까지는 지연될 수 있다.
 */
@Service
public class PipelineService {

    private static final Logger log = LoggerFactory.getLogger(PipelineService.class);

    private static final class RunHandle {
        final Long runId;
        final AtomicBoolean cancelled = new AtomicBoolean(false);

        RunHandle(Long runId) {
            this.runId = runId;
        }
    }

    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "pipeline-worker");
        t.setDaemon(true);
        return t;
    });

    private final AtomicReference<RunHandle> activeHandle = new AtomicReference<>();

    private final RunRepository runRepository;
    private final UploadRepository uploadRepository;
    private final PageRenderStep pageRenderStep;
    private final CaptionStep captionStep;
    private final SectionInventoryStep sectionInventoryStep;
    private final StyleGuideStep styleGuideStep;
    private final ReferenceRagStep referenceRagStep;
    private final SectionGenerationStep sectionGenerationStep;
    private final MergeCsvStep mergeCsvStep;
    private final MergeQuestionsStep mergeQuestionsStep;
    private final FinalizeStep finalizeStep;
    private final CoverageStep coverageStep;
    private final RunLogService runLogService;

    public PipelineService(RunRepository runRepository, UploadRepository uploadRepository,
            PageRenderStep pageRenderStep, CaptionStep captionStep, SectionInventoryStep sectionInventoryStep,
            StyleGuideStep styleGuideStep, ReferenceRagStep referenceRagStep, SectionGenerationStep sectionGenerationStep,
            MergeCsvStep mergeCsvStep, MergeQuestionsStep mergeQuestionsStep, FinalizeStep finalizeStep,
            CoverageStep coverageStep, RunLogService runLogService) {
        this.runRepository = runRepository;
        this.uploadRepository = uploadRepository;
        this.pageRenderStep = pageRenderStep;
        this.captionStep = captionStep;
        this.sectionInventoryStep = sectionInventoryStep;
        this.styleGuideStep = styleGuideStep;
        this.referenceRagStep = referenceRagStep;
        this.sectionGenerationStep = sectionGenerationStep;
        this.mergeCsvStep = mergeCsvStep;
        this.mergeQuestionsStep = mergeQuestionsStep;
        this.finalizeStep = finalizeStep;
        this.coverageStep = coverageStep;
        this.runLogService = runLogService;
    }

    public boolean isRunning() {
        return activeHandle.get() != null;
    }

    public void start(Long runId) {
        RunHandle handle = new RunHandle(runId);
        if (!activeHandle.compareAndSet(null, handle)) {
            throw new IllegalStateException("Pipeline is already running");
        }
        runLogService.markStart(runId);
        executor.submit(() -> runLoop(handle));
    }

    /** @return true면 실제로 중단 요청을 보냄, false면 실행 중인 파이프라인이 없었음 */
    public boolean stop() {
        RunHandle handle = activeHandle.get();
        if (handle == null) {
            return false;
        }
        handle.cancelled.set(true);
        return true;
    }

    public void resume(Long runId) {
        Run run = runRepository.findById(runId).orElseThrow();
        if (run.getStatus() != RunStatus.STOPPED && run.getStatus() != RunStatus.NEEDS_HUMAN
                && run.getStatus() != RunStatus.FAILED) {
            throw new IllegalStateException("Run is not resumable in status " + run.getStatus());
        }
        run.setNeedsHumanReason(null);
        runRepository.save(run);
        runLogService.append(runId, "--- Resuming pipeline execution ---");
        start(runId);
    }

    private void checkCancelled(RunHandle handle) {
        if (handle.cancelled.get()) {
            throw new PipelineCancelledException();
        }
    }

    private void runLoop(RunHandle handle) {
        Long runId = handle.runId;
        try {
            Run run = runRepository.findById(runId).orElseThrow();
            run.setStatus(RunStatus.RUNNING);
            if (run.getPhase() == RunPhase.CREATED) {
                run.setPhase(RunPhase.RENDERING);
            }
            runRepository.save(run);

            Upload pdfUpload = uploadRepository.findByRunIdAndKind(runId, UploadKind.SPEC_PDF)
                    .orElseThrow(() -> new IllegalStateException("spec PDF upload not found for run " + runId));
            int pageCount = pageRenderStep.render(runId, pdfUpload.getContent());
            runLogService.append(runId, "[Phase 0] 페이지 렌더링 완료 (%d페이지)".formatted(pageCount));
            checkCancelled(handle);

            run = runRepository.findById(runId).orElseThrow();
            run.setPageCount(pageCount);
            run.setPhase(RunPhase.CAPTIONING);
            runRepository.save(run);

            captionStep.captionAll(runId, pageCount,
                    pageNo -> runLogService.append(runId, "[Phase 0] 슬라이드 캡션 %03d/%03d".formatted(pageNo, pageCount)));
            checkCancelled(handle);

            run = runRepository.findById(runId).orElseThrow();
            run.setPhase(RunPhase.SECTIONING);
            runRepository.save(run);

            List<Section> sections = sectionInventoryStep.buildSections(runId, pageCount);
            runLogService.append(runId, "[Phase 0] 섹션 %d개로 분할".formatted(sections.size()));
            checkCancelled(handle);

            run = runRepository.findById(runId).orElseThrow();
            run.setPhase(RunPhase.STYLE_GUIDE);
            runRepository.save(run);

            styleGuideStep.buildStyleGuide(runId);
            int ragChunkCount = referenceRagStep.index(runId);
            runLogService.append(runId, "[Phase 0 완료] 참고 TC RAG 인덱스 구축 (%d건)".formatted(ragChunkCount));
            runLogService.append(runId, "[Phase 0 완료] 스타일 가이드 작성 — Phase 1 시작");
            checkCancelled(handle);

            run = runRepository.findById(runId).orElseThrow();
            run.setPhase(RunPhase.GENERATING);
            runRepository.save(run);

            Optional<Section> next;
            while ((next = sectionGenerationStep.nextPendingSection(runId)).isPresent()) {
                checkCancelled(handle);
                Section section = next.get();
                runLogService.append(runId, "[Phase 1] %03d %s (p.%d-%d) 시작"
                        .formatted(section.getSectionNo(), section.getTitle(), section.getPageStart(), section.getPageEnd()));
                sectionGenerationStep.generate(runId, section);
                runLogService.append(runId, "[Phase 1] %03d %s -> %s"
                        .formatted(section.getSectionNo(), section.getTitle(), section.getStatus()));
            }
            checkCancelled(handle);

            run = runRepository.findById(runId).orElseThrow();
            run.setPhase(RunPhase.MERGING_CSV);
            runRepository.save(run);

            int totalTc = mergeCsvStep.assignGlobalNumbers(runId);
            runLogService.append(runId, "[Phase 2] CSV 병합 완료 (%d행)".formatted(totalTc));
            checkCancelled(handle);

            run = runRepository.findById(runId).orElseThrow();
            run.setPhase(RunPhase.MERGING_QUESTIONS);
            runRepository.save(run);

            mergeQuestionsStep.merge(runId);
            runLogService.append(runId, "[Phase 2] 의문점 병합 완료");
            checkCancelled(handle);

            run = runRepository.findById(runId).orElseThrow();
            run.setPhase(RunPhase.FINALIZING);
            runRepository.save(run);

            var finalValidation = finalizeStep.validateFinal(runId);
            if (!finalValidation.isValid()) {
                runLogService.append(runId,
                        "[경고] 병합된 최종 CSV가 검증에 실패했으나 계속 진행합니다: " + finalValidation.errors());
            }
            coverageStep.buildReport(run);
            runLogService.append(runId, "[Phase 2] 커버리지 리포트 작성 완료");

            run = runRepository.findById(runId).orElseThrow();
            run.setStatus(RunStatus.COMPLETED);
            run.setPhase(RunPhase.DONE);
            run.setDoneSummary("완료: %s\nTC: %d건\n검수자 확인 사항: coverage_report, 페이지별 vision_caption 정확도"
                    .formatted(run.getSpecName(), totalTc));
            runRepository.save(run);
            runLogService.append(runId, "[완료] TC %d건 — 총 소요 완료".formatted(totalTc));
        } catch (PipelineCancelledException e) {
            runLogService.append(runId, "[System] Pipeline terminated by user.");
            Run run = runRepository.findById(runId).orElseThrow();
            run.setStatus(RunStatus.STOPPED);
            runRepository.save(run);
        } catch (Exception e) {
            log.error("[run {}] pipeline failed", runId, e);
            runLogService.append(runId, "[오류] " + e.getMessage());
            Run run = runRepository.findById(runId).orElseThrow();
            run.setStatus(RunStatus.NEEDS_HUMAN);
            run.setNeedsHumanReason("파이프라인 처리 중 오류: " + e.getMessage());
            runRepository.save(run);
        } finally {
            activeHandle.compareAndSet(handle, null);
        }
    }
}
