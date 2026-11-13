package com.samsung.gsre.agent.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.MetadataMode;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 로컬 LLM 연동 설정.
 *
 * <h2>기준정보</h2>
 * <ul>
 *   <li>엔드포인트: {@code spring.ai.openai.base-url} = {@code LOCAL_LLM_BASE_URL}</li>
 *   <li>모델: {@code LOCAL_LLM_MODEL} (Ollama / vLLM / llama.cpp / LM Studio / Qwen, Muse 모두 OpenAI 호환)</li>
 *   <li>임베딩 모델: {@code LOCAL_LLM_EMBEDDING_MODEL} (없으면 시맨틱 캐시가 어휘 유사도로 폴백)</li>
 * </ul>
 *
 * <p>Spring AI OpenAI starter 의 자동구성 대신 여기서 직접 빈을 만든다.
 * base-url·모델·토큰 수를 한 곳에서 명시적으로 제어하고, Boot 3.3.5 + Spring AI 1.0.0-M3 조합에서
 * 자동구성 세부 동작에 의존하지 않기 위해서다.</p>
 */
@Configuration
public class ChatClientConfig {

    @Bean
    public OpenAiApi openAiApi(@Value("${spring.ai.openai.base-url}") String baseUrl,
                               @Value("${spring.ai.openai.api-key}") String apiKey) {
        return new OpenAiApi(baseUrl, apiKey);
    }

    @Bean
    public OpenAiChatModel openAiChatModel(OpenAiApi openAiApi,
                                           @Value("${spring.ai.openai.chat.options.model}") String model,
                                           AgentProperties props) {
        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .withModel(model)
                .withTemperature(0.0)
                .withMaxTokens(props.getLlm().getMaxTokens())
                .build();
        return new OpenAiChatModel(openAiApi, options);
    }

    @Bean
    public EmbeddingModel embeddingModel(OpenAiApi openAiApi,
                                         @Value("${spring.ai.openai.embedding.options.model}") String model) {
        return new OpenAiEmbeddingModel(openAiApi, MetadataMode.EMBED,
                OpenAiEmbeddingOptions.builder().withModel(model).build());
    }

    @Bean
    public ChatClient chatClient(OpenAiChatModel chatModel) {
        return ChatClient.builder(chatModel).build();
    }
}
