package com.spec2test.config;

import com.spec2test.domain.Run;
import com.spec2test.domain.RunStatus;
import com.spec2test.repo.RunRepository;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 부팅 시 RUNNING 상태로 남아있는 run은 이전 JVM이 죽으면서 고아가 된 것이므로 STOPPED로 정리한다.
 * 기존 PID 파일 방식을 대체한다 — 인메모리 PipelineService.activeHandle이 유일한 생존 판단 기준이며,
 * 새 프로세스는 그 어떤 run도 활성으로 알지 못하므로 재개는 사용자가 /api/resume으로 명시해야 한다.
 */
@Component
public class StartupReconciler {

    private static final Logger log = LoggerFactory.getLogger(StartupReconciler.class);

    private final RunRepository runRepository;

    public StartupReconciler(RunRepository runRepository) {
        this.runRepository = runRepository;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void reconcile() {
        List<Run> stale = runRepository.findByStatus(RunStatus.RUNNING);
        for (Run run : stale) {
            run.setStatus(RunStatus.STOPPED);
            runRepository.save(run);
            log.warn("[run {}] 이전 실행 중 프로세스가 재시작되어 STOPPED로 정리함", run.getId());
        }
    }
}
