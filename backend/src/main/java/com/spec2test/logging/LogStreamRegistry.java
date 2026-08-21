package com.spec2test.logging;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * run별 SSE 구독자 레지스트리. 재생(DB replay)과 실시간 publish가 겹쳐도 각 로그 라인이
 * 구독자당 정확히 한 번만 전달되도록 CAS 기반 watermark(claim)로 경합을 봉합한다.
 */
@Component
public class LogStreamRegistry {

    public static final class Subscription {
        private final SseEmitter emitter;
        private final AtomicLong lastSentId = new AtomicLong(0);

        private Subscription(SseEmitter emitter) {
            this.emitter = emitter;
        }

        public SseEmitter emitter() {
            return emitter;
        }
    }

    private final Map<Long, CopyOnWriteArrayList<Subscription>> subscribers = new ConcurrentHashMap<>();

    public Subscription register(Long runId) {
        Subscription sub = new Subscription(new SseEmitter(0L));
        CopyOnWriteArrayList<Subscription> list = subscribers.computeIfAbsent(runId, id -> new CopyOnWriteArrayList<>());
        list.add(sub);
        Runnable cleanup = () -> {
            CopyOnWriteArrayList<Subscription> l = subscribers.get(runId);
            if (l != null) {
                l.remove(sub);
            }
        };
        sub.emitter.onCompletion(cleanup);
        sub.emitter.onTimeout(cleanup);
        sub.emitter.onError(e -> cleanup.run());
        return sub;
    }

    /** id가 이 구독자에게 아직 전달되지 않았으면 watermark를 갱신하고 true를 반환한다 (원자적 1회 보장). */
    public boolean claim(Subscription sub, long lineId) {
        long prev = sub.lastSentId.get();
        while (lineId > prev) {
            if (sub.lastSentId.compareAndSet(prev, lineId)) {
                return true;
            }
            prev = sub.lastSentId.get();
        }
        return false;
    }

    public void publish(Long runId, long lineId, String line) {
        List<Subscription> list = subscribers.get(runId);
        if (list == null) {
            return;
        }
        for (Subscription sub : list) {
            if (!claim(sub, lineId)) {
                continue;
            }
            try {
                sub.emitter.send(SseEmitter.event().id(String.valueOf(lineId)).data(line));
            } catch (IOException e) {
                list.remove(sub);
            }
        }
    }

    public void heartbeat() {
        for (CopyOnWriteArrayList<Subscription> list : subscribers.values()) {
            for (Subscription sub : list) {
                try {
                    sub.emitter.send(SseEmitter.event().comment("keep-alive"));
                } catch (IOException e) {
                    list.remove(sub);
                }
            }
        }
    }
}
