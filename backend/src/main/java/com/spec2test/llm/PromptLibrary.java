package com.spec2test.llm;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/**
 * classpath:prompts/*.st 템플릿을 로드해 {var} 플레이스홀더를 치환한다.
 * 프롬프트 원문은 기존 local_pipeline.py의 한국어 문구를 그대로 이식한 것이다.
 */
@Component
public class PromptLibrary {

    private final Map<String, String> rawCache = new ConcurrentHashMap<>();

    public String render(String templateName, Map<String, Object> variables) {
        String raw = rawCache.computeIfAbsent(templateName, this::load);
        return new PromptTemplate(raw).render(variables);
    }

    public String raw(String templateName) {
        return rawCache.computeIfAbsent(templateName, this::load);
    }

    /** rules.md처럼 플레이스홀더 없이 원문 그대로 쓰는 리소스 로딩용. */
    public String rawFile(String filenameWithExt) {
        return rawCache.computeIfAbsent("file:" + filenameWithExt, key -> readClassPathFile(filenameWithExt));
    }

    private String load(String templateName) {
        return readClassPathFile(templateName + ".st");
    }

    private String readClassPathFile(String filename) {
        try {
            ClassPathResource resource = new ClassPathResource("prompts/" + filename);
            return new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new LlmException("프롬프트 리소스를 읽을 수 없다: " + filename, e);
        }
    }
}
