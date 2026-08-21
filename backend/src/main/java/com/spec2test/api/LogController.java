package com.spec2test.api;

import com.spec2test.domain.LogLine;
import com.spec2test.domain.Run;
import com.spec2test.logging.LogStreamRegistry;
import com.spec2test.logging.LogStreamRegistry.Subscription;
import com.spec2test.repo.LogLineRepository;
import com.spec2test.repo.RunRepository;
import java.io.IOException;
import java.util.Optional;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * run.log 파일 tail(app.py의 /api/logs)을 대체하는 SSE 엔드포인트.
 * Last-Event-ID(브라우저 자동 재연결 시 전송) 또는 ?after= 로 이어보기를 지원한다.
 */
@RestController
@RequestMapping("/api")
public class LogController {

    private final RunRepository runRepository;
    private final LogLineRepository logLineRepository;
    private final LogStreamRegistry registry;

    public LogController(RunRepository runRepository, LogLineRepository logLineRepository,
            LogStreamRegistry registry) {
        this.runRepository = runRepository;
        this.logLineRepository = logLineRepository;
        this.registry = registry;
    }

    @GetMapping(value = "/logs", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter logs(@RequestParam(value = "after", required = false) Long after,
            @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId) {
        Optional<Run> currentRun = runRepository.findTopByOrderByIdDesc();
        if (currentRun.isEmpty()) {
            SseEmitter emitter = new SseEmitter(0L);
            try {
                emitter.send(SseEmitter.event().data("[Web Server] 아직 실행된 파이프라인이 없다."));
                emitter.complete();
            } catch (IOException ignored) {
                // 클라이언트가 이미 연결을 끊은 경우
            }
            return emitter;
        }

        Long runId = currentRun.get().getId();
        long afterId = resolveAfterId(after, lastEventId);

        Subscription sub = registry.register(runId);
        for (LogLine line : logLineRepository.findByRunIdAndIdGreaterThanOrderById(runId, afterId)) {
            if (registry.claim(sub, line.getId())) {
                try {
                    sub.emitter().send(SseEmitter.event().id(String.valueOf(line.getId())).data(line.getLine()));
                } catch (IOException e) {
                    break;
                }
            }
        }
        return sub.emitter();
    }

    private long resolveAfterId(Long after, String lastEventId) {
        if (after != null) {
            return after;
        }
        if (lastEventId != null) {
            try {
                return Long.parseLong(lastEventId);
            } catch (NumberFormatException ignored) {
                // 잘못된 헤더 값은 무시하고 처음부터 재생
            }
        }
        return 0L;
    }
}
