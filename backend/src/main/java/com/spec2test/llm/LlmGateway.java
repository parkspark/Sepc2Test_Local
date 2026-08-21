package com.spec2test.llm;

import com.spec2test.config.Spec2TestProperties;
import java.util.List;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.content.Media;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.stereotype.Component;
import org.springframework.util.MimeTypeUtils;

/**
 * Spring AI ChatModel을 감싸 기존 local_pipeline.py의 ollama_chat/ollama_chat_json/with_ollama_retries를
 * 대체한다. spec2test.llm.provider 설정만으로 Ollama/OpenAI/Anthropic 사이를 전환할 수 있도록
 * 옵션 생성부만 프로바이더별로 분기하고, 그 외 로직(재시도, JSON 재프롬프트)은 공통이다.
 */
@Component
public class LlmGateway {

    private static final Logger log = LoggerFactory.getLogger(LlmGateway.class);
    private static final int TRANSPORT_RETRIES = 3;
    private static final long TRANSPORT_RETRY_DELAY_MS = 10_000;
    private static final int JSON_RETRIES = 2;

    private final ChatModel chatModel;
    private final Spec2TestProperties properties;

    public LlmGateway(ChatModel chatModel, Spec2TestProperties properties) {
        this.chatModel = chatModel;
        this.properties = properties;
    }

    public String text(String systemPrompt, String userPrompt) {
        return text(systemPrompt, userPrompt, null);
    }

    public String text(String systemPrompt, String userPrompt, Integer numCtxOverride) {
        return callWithRetries(() -> callOnce(systemPrompt, userPrompt, textOptions(numCtxOverride, null)));
    }

    public <T> T json(String systemPrompt, String userPrompt, Class<T> type) {
        List<Message> messages = (systemPrompt == null || systemPrompt.isBlank())
                ? new java.util.ArrayList<>(List.of(new UserMessage(userPrompt)))
                : new java.util.ArrayList<>(List.of(new SystemMessage(systemPrompt), new UserMessage(userPrompt)));
        return json(messages, type, null);
    }

    /** 섹션 인벤토리 갭 재시도처럼 대화 히스토리를 직접 이어붙여야 하는 호출용. */
    public <T> T json(List<Message> messages, Class<T> type) {
        return json(messages, type, null);
    }

    public <T> T json(List<Message> messages, Class<T> type, Integer numCtxOverride) {
        BeanOutputConverter<T> converter = new BeanOutputConverter<>(type);
        String schema = converter.getJsonSchema();

        List<Message> currentMessages = messages;
        Exception lastParseError = null;
        for (int attempt = 1; attempt <= JSON_RETRIES; attempt++) {
            List<Message> messagesForThisAttempt = currentMessages;
            String content = callWithRetries(() -> {
                ChatResponse response = chatModel.call(
                        new Prompt(messagesForThisAttempt, textOptions(numCtxOverride, schema)));
                return response.getResult().getOutput().getText();
            });
            try {
                return converter.convert(content);
            } catch (Exception e) {
                lastParseError = e;
                log.warn("모델 출력이 유효한 JSON이 아님 (시도 {}/{}): {}", attempt, JSON_RETRIES, e.getMessage());
                currentMessages = new java.util.ArrayList<>(currentMessages);
                currentMessages.add(new org.springframework.ai.chat.messages.AssistantMessage(content));
                currentMessages.add(new UserMessage("출력이 올바른 JSON이 아니다. JSON만 다시 출력하라."));
            }
        }
        throw new LlmException("모델이 유효한 JSON을 반환하지 않았다", lastParseError);
    }

    public String caption(String promptText, byte[] pngBytes) {
        return callWithRetries(() -> {
            UserMessage userMessage = UserMessage.builder()
                    .text(promptText)
                    .media(new Media(MimeTypeUtils.IMAGE_PNG, new ByteArrayResource(pngBytes)))
                    .build();
            ChatResponse response = chatModel.call(new Prompt(List.of(userMessage), visionOptions()));
            return response.getResult().getOutput().getText();
        });
    }

    private String callOnce(String systemPrompt, String userPrompt, ChatOptions options) {
        List<Message> messages = (systemPrompt == null || systemPrompt.isBlank())
                ? List.of(new UserMessage(userPrompt))
                : List.of(new SystemMessage(systemPrompt), new UserMessage(userPrompt));
        ChatResponse response = chatModel.call(new Prompt(messages, options));
        return response.getResult().getOutput().getText();
    }

    private ChatOptions textOptions(Integer numCtxOverride, String jsonSchema) {
        int ctx = numCtxOverride != null ? numCtxOverride : properties.getNumCtx();
        return switch (properties.getProvider()) {
            case "openai" -> {
                OpenAiChatOptions.Builder b = OpenAiChatOptions.builder()
                        .model(properties.getTextModel())
                        .temperature(properties.getTemperature());
                if (jsonSchema != null) {
                    b.outputSchema(jsonSchema);
                }
                yield b.build();
            }
            case "anthropic" -> {
                AnthropicChatOptions.Builder b = AnthropicChatOptions.builder()
                        .model(properties.getTextModel())
                        .temperature(properties.getTemperature());
                if (jsonSchema != null) {
                    b.outputSchema(jsonSchema);
                }
                yield b.build();
            }
            default -> {
                OllamaChatOptions.Builder b = OllamaChatOptions.builder()
                        .model(properties.getTextModel())
                        .numCtx(ctx)
                        .temperature(properties.getTemperature())
                        .keepAlive(formatKeepAlive());
                if (jsonSchema != null) {
                    b.outputSchema(jsonSchema);
                }
                yield b.build();
            }
        };
    }

    private ChatOptions visionOptions() {
        return switch (properties.getProvider()) {
            case "openai" -> OpenAiChatOptions.builder()
                    .model(properties.getVisionModel())
                    .temperature(properties.getTemperature())
                    .build();
            case "anthropic" -> AnthropicChatOptions.builder()
                    .model(properties.getVisionModel())
                    .temperature(properties.getTemperature())
                    .build();
            default -> OllamaChatOptions.builder()
                    .model(properties.getVisionModel())
                    .numCtx(properties.getNumCtx())
                    .temperature(properties.getTemperature())
                    .keepAlive(formatKeepAlive())
                    .build();
        };
    }

    private String formatKeepAlive() {
        return properties.getKeepAlive().toMinutes() + "m";
    }

    /**
     * 일시적 전송 오류(네트워크 단절/타임아웃)만 흡수해 재시도한다. 여기서도 실패하면 호출자가
     * 이를 섹션 검증 실패와 동일하게 다루거나 NEEDS_HUMAN으로 전환한다 (with_ollama_retries 이식).
     */
    private String callWithRetries(Supplier<String> call) {
        RuntimeException lastError = null;
        for (int attempt = 1; attempt <= TRANSPORT_RETRIES; attempt++) {
            try {
                return call.get();
            } catch (RuntimeException e) {
                lastError = e;
                log.warn("LLM 호출 실패 (시도 {}/{}): {}", attempt, TRANSPORT_RETRIES, e.getMessage());
                if (attempt < TRANSPORT_RETRIES) {
                    sleep(TRANSPORT_RETRY_DELAY_MS);
                }
            }
        }
        throw new LlmException("LLM 호출이 반복 재시도 후에도 실패했다: " + lastError.getMessage(), lastError);
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LlmException("대기 중 인터럽트", e);
        }
    }
}
