package com.spec2test.logging;

import com.spec2test.domain.LogLine;
import com.spec2test.repo.LogLineRepository;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * run.log 파일을 대체한다: 로그 한 줄마다 log_line 테이블에 적재하고 SSE 구독자에게 즉시 팬아웃한다.
 * "[HH:mm:ss +경과]" 접두사는 기존 local_pipeline.py의 log()와 동일한 형식이다.
 */
@Service
public class RunLogService {

    private static final Logger log = LoggerFactory.getLogger(RunLogService.class);
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final LogLineRepository logLineRepository;
    private final LogStreamRegistry registry;
    private final Map<Long, Instant> runStartTimes = new ConcurrentHashMap<>();

    public RunLogService(LogLineRepository logLineRepository, LogStreamRegistry registry) {
        this.logLineRepository = logLineRepository;
        this.registry = registry;
    }

    public void markStart(Long runId) {
        runStartTimes.put(runId, Instant.now());
    }

    public void append(Long runId, String message) {
        Instant start = runStartTimes.computeIfAbsent(runId, id -> Instant.now());
        Duration elapsed = Duration.between(start, Instant.now());
        String prefix = "[%s +%02d:%02d:%02d] ".formatted(
                LocalTime.now().format(TIME_FORMAT), elapsed.toHours(), elapsed.toMinutesPart(), elapsed.toSecondsPart());
        String line = prefix + message;

        LogLine entity = new LogLine();
        entity.setRunId(runId);
        entity.setLine(line);
        entity = logLineRepository.save(entity);

        log.info("[run {}] {}", runId, message);
        registry.publish(runId, entity.getId(), line);
    }

    @Scheduled(fixedRate = 15_000)
    public void heartbeat() {
        registry.heartbeat();
    }
}
