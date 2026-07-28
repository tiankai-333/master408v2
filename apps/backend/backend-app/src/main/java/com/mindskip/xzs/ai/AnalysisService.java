package com.mindskip.xzs.ai;

import tools.jackson.databind.ObjectMapper;
import com.mindskip.xzs.domain.ai.AiProviderConfig;
import com.mindskip.xzs.domain.ai.AiUserKey;
import com.mindskip.xzs.service.AiUserKeyService;
import com.mindskip.xzs.domain.ai.AiUsageLog;
import com.mindskip.xzs.repository.AiUsageLogMapper;
import com.mindskip.xzs.service.AiProviderConfigService;
import com.mindskip.xzs.ai.prompt.PromptContext;
import com.mindskip.xzs.ai.prompt.PromptRef;
import com.mindskip.xzs.ai.prompt.PromptRegistry;
import com.mindskip.xzs.ai.prompt.ResolvedPrompt;
import com.mindskip.xzs.ai.prompt.WorkbenchPromptTemplates;
import com.mindskip.xzs.ai.client.AiAnalysisRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.io.BufferedReader;
import java.io.OutputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.*;

@Service
public class AnalysisService {

    private static final Logger logger = LoggerFactory.getLogger(AnalysisService.class);

    private static final ThreadLocal<Integer> currentUser = new ThreadLocal<>();
    private static final ThreadLocal<String> currentKeySource = new ThreadLocal<>();

    public static void setCurrentUserId(Integer userId) { currentUser.set(userId); }
    public static void clearCurrentUserId() { currentUser.remove(); currentKeySource.remove(); }
    public static Integer getCurrentUserId() { return currentUser.get(); }
    public static String getCurrentKeySource() { return currentKeySource.get(); }

    private final Map<String, PromptTemplate> promptTemplates;
    private final ObjectMapper objectMapper;

    public interface StreamTokenConsumer {
        void accept(String token) throws Exception;
    }

    @Autowired
    private AiProviderConfigService aiProviderConfigService;

    @Autowired
    private AiUsageLogMapper aiUsageLogMapper;

    @Autowired
    private AiUserKeyService aiUserKeyService;

    @Autowired
    private PromptRegistry promptRegistry;

    @Value("${ai.api.type:glm}")
    private String aiApiType;

    @Value("${ai.api.key:}")
    private String aiApiKey;

    @Value("${ai.api.url:https://open.bigmodel.cn/api/paas/v4/chat/completions}")
    private String aiApiUrl;

    public AnalysisService() {
        this.promptTemplates = new HashMap<>();
        this.objectMapper = new ObjectMapper();
        loadTemplates();
    }

    private void loadTemplates() {
        try {
            String[] styles = {"default", "feynman", "plato", "first-principles"};
            for (String style : styles) {
                String path = "ai/prompts/analysis/" + style + ".json";
                ClassPathResource resource = new ClassPathResource(path);
                if (resource.exists()) {
                    try (InputStream is = resource.getInputStream()) {
                        PromptTemplate template = objectMapper.readValue(is, PromptTemplate.class);
                        promptTemplates.put(style, template);
                        logger.info("Successfully loaded prompt template: {}", style);
                    }
                } else {
                    logger.warn("Prompt template not found: {}", path);
                }
            }
            logger.info("Total loaded prompt templates: {}", promptTemplates.size());
        } catch (Exception e) {
            logger.error("Failed to load prompt templates: " + e.getMessage(), e);
            loadDefaultTemplates();
        }
    }

    private void loadDefaultTemplates() {
        PromptTemplate defaultTemplate = new PromptTemplate();
        defaultTemplate.setName("default");
        defaultTemplate.setStyle("default");
        defaultTemplate.setSystemPrompt("你是一个专业的计算机考研408辅导老师。");
        defaultTemplate.setUserPromptTemplate("请解析以下题目：\n\n{question}\n\n相关知识点：{knowledge_points}");
        defaultTemplate.setVariables(Arrays.asList("question", "knowledge_points"));
        promptTemplates.put("default", defaultTemplate);
    }

    public List<String> getAvailableStyles() {
        return new ArrayList<>(promptTemplates.keySet());
    }

    public PromptTemplate getTemplate(String style) {
        return promptTemplates.getOrDefault(style, promptTemplates.get("default"));
    }

    /**
     * 运行时解析分析型 Prompt：优先走 {@link PromptRegistry}（DB 已发布版本，记录 versionId/releaseId），
     * 未命中则回退到启动加载的 JSON 模板（ref=unknown），保证「DB 异常不影响已发布 Prompt 读取」。
     * 工作台使用独立的 workbench.{style} key，避免与旧 analysis 模板互相污染。
     */
    public ResolvedAnalysisPrompt resolveAnalysisPrompt(String style, String taskType,
                                                        PromptContext ctx, boolean applyWorkbenchOverride) {
        boolean workbench = isWorkbenchTask(taskType);
        String promptKey = (workbench ? "workbench." : "analysis.") + style;
        String systemPrompt;
        String userPromptTemplate;
        PromptRef ref;

        ResolvedPrompt resolved = promptRegistry.resolve(promptKey, ctx);
        if (resolved != null && resolved.systemPrompt() != null) {
            systemPrompt = resolved.systemPrompt();
            userPromptTemplate = resolved.userPromptTemplate();
            ref = resolved.toRef();
            if (userPromptTemplate == null) {
                // 版本只配了 system；user 模板回退 JSON，避免 formatUserPrompt NPE
                userPromptTemplate = workbench
                        ? WorkbenchPromptTemplates.templateFor(style)
                        : getTemplate(style).getUserPromptTemplate();
            }
        } else {
            PromptTemplate t = getTemplate(style);
            systemPrompt = workbench ? WorkbenchPromptTemplates.SYSTEM_PROMPT : t.getSystemPrompt();
            userPromptTemplate = workbench ? WorkbenchPromptTemplates.templateFor(style) : t.getUserPromptTemplate();
            ref = PromptRef.unknown(promptKey);
        }

        PromptTemplate tpl = new PromptTemplate();
        tpl.setSystemPrompt(systemPrompt);
        tpl.setUserPromptTemplate(userPromptTemplate);
        return new ResolvedAnalysisPrompt(ref, tpl, systemPrompt);
    }

    public ResolvedAnalysisPrompt resolveAnalysisPrompt(String style, String taskType, PromptContext ctx) {
        return resolveAnalysisPrompt(style, taskType, ctx, true);
    }

    /**
     * 构建一次分析请求的输入（systemPrompt + userPrompt + promptRef），不调用 LLM。
     * Spring 路径（AiAnalysisGateway）使用；统一解析点，避免在 gateway 重复 workbench 分支。
     * 草稿测试（PromptOps Playground）也复用此方法。
     */
    public AiAnalysisRequest buildAnalysisRequest(String style, String question, String knowledgePoints,
                                                  String referenceDocs, String taskType, String conversationId) {
        ResolvedAnalysisPrompt rap = resolveAnalysisPrompt(
                style, taskType, PromptContext.of(currentUser.get(), conversationId));
        String userPrompt = renderUserPrompt(rap, style, question, knowledgePoints, referenceDocs, taskType);
        return new AiAnalysisRequest(rap.systemPrompt(), userPrompt, conversationId, rap.ref());
    }

    /**
     * Playground 一次性测试：用任意 system/user 文案调用 LLM（用于测试某个版本内容，绝不经过 active release）。
     * 记录一条 prompt_key="test" 的 usage log。
     */
    public String runPromptTest(String systemPrompt, String userPrompt) throws Exception {
        PromptRef ref = PromptRef.unknown("test");
        ResolvedProvider rp = resolveProvider();
        if (rp != null) {
            currentKeySource.set(rp.source);
            return callAiApi(systemPrompt, userPrompt, rp.type, rp.key, rp.url, rp.model, ref);
        }
        return callAiApi(systemPrompt, userPrompt, aiApiType, aiApiKey, aiApiUrl, "glm-4.5-air", ref);
    }

    public String generatePrompt(String style, String question, String knowledgePoints) {
        return generatePrompt(style, question, knowledgePoints, null);
    }

    public String generatePrompt(String style, String question, String knowledgePoints, String referenceDocs) {
        return generatePrompt(style, question, knowledgePoints, referenceDocs, "chat");
    }

    public String generatePrompt(String style, String question, String knowledgePoints, String referenceDocs, String taskType) {
        if (isWorkbenchTask(taskType)) {
            return WorkbenchPromptTemplates.render(
                    WorkbenchPromptTemplates.templateFor(style), style, taskType,
                    question, knowledgePoints, referenceDocs);
        }
        PromptTemplate template = getTemplate(style);
        return template.formatUserPrompt(question, knowledgePoints, referenceDocs);
    }

    public String analyzeWithAI(String style, String question, String knowledgePoints) throws Exception {
        return analyzeWithAI(style, question, knowledgePoints, null);
    }

    public String analyzeWithAI(String style, String question, String knowledgePoints, String referenceDocs) throws Exception {
        return analyzeWithAI(style, question, knowledgePoints, referenceDocs, "chat");
    }

    public String analyzeImage(String imageDataUrl) throws Exception {
        String systemPrompt = "你是一个题目分析助手，需要识别图片中的所有题目内容。图片中可能包含多道题目，请逐一分析。对于每道题目，提取以下信息：题目类型（单选题、多选题、判断题、填空题、简答题）、题目内容（题干）、选项（如果有）、正确答案、解析（如果有）。请以JSON数组格式返回结果，数组中每个元素代表一道题目，不要包含任何多余的文字描述。";
        String userPrompt = "请分析这张图片中的所有题目，以JSON数组格式返回";

        ResolvedProvider rp = resolveVisionProvider();
        if (rp != null) {
            currentKeySource.set(rp.source);
            return callAiApiWithImage(systemPrompt, userPrompt, imageDataUrl, rp.type, rp.key, rp.url, rp.model);
        }
        if (aiApiKey == null || aiApiKey.trim().isEmpty()) {
            throw new Exception("图片识别服务暂不可用：未配置 AI 密钥，请在管理后台配置视觉模型供应商。");
        }
        return callAiApiWithImage(systemPrompt, userPrompt, imageDataUrl, aiApiType, aiApiKey, aiApiUrl, "glm-4.6v-flash");
    }

    private ResolvedProvider resolveVisionProvider() {
        List<ResolvedProvider> candidates = new ArrayList<>();

        try {
            List<AiProviderConfig> publicConfigs = aiProviderConfigService.listSafe();
            for (AiProviderConfig pc : publicConfigs) {
                if (!Boolean.TRUE.equals(pc.getEnabled())) continue;
                ResolvedProvider rp = new ResolvedProvider();
                rp.type = apiType(pc.getProviderCode());
                rp.key = aiProviderConfigService.resolveApiKey(pc.getProviderCode());
                rp.url = chatEndpoint(pc);
                rp.model = resolveVisionModel(pc.getProviderCode(), pc.getChatModel(), pc.getVisionModel());
                if (rp.model == null) continue;
                rp.source = "public";
                rp.priority = pc.getPriority() != null ? pc.getPriority() : 100;
                candidates.add(rp);
            }
        } catch (Exception e) {
            logger.warn("Failed to load public providers for vision: {}", e.getMessage());
        }

        Integer userId = currentUser.get();
        if (userId != null) {
            try {
                List<AiUserKey> userKeys = aiUserKeyService.listEnabledByUser(userId);
                for (AiUserKey uk : userKeys) {
                    if (!Boolean.TRUE.equals(uk.getEnabled()) || uk.getApiKeyCipher() == null) continue;
                    ResolvedProvider rp = new ResolvedProvider();
                    rp.type = apiType(uk.getProviderCode());
                    rp.key = aiUserKeyService.decryptKey(uk.getApiKeyCipher());
                    rp.url = chatEndpointFromBase(uk.getApiBaseUrl());
                    rp.model = resolveVisionModel(uk.getProviderCode(), uk.getChatModel(), uk.getVisionModel());
                    if (rp.model == null) continue;
                    rp.source = "private";
                    rp.priority = uk.getPriority() != null ? uk.getPriority() : 100;
                    candidates.add(rp);
                }
            } catch (Exception e) {
                logger.warn("Failed to load user keys for vision: {}", e.getMessage());
            }
        }

        candidates.sort(Comparator.comparingInt(r -> r.priority));
        return candidates.isEmpty() ? null : candidates.get(0);
    }

    private String resolveVisionModel(String providerCode, String chatModel, String visionModel) {
        if (visionModel != null && !visionModel.trim().isEmpty()) {
            return visionModel.trim();
        }
        if ("glm".equals(providerCode) || "zhipu".equals(providerCode)) {
            return "glm-4.6v-flash";
        }
        return null;
    }

    private String callAiApiWithImage(String systemPrompt, String userPrompt, String imageDataUrl,
                                       String aiType, String apiKey, String apiUrl, String model) throws Exception {
        if (imageDataUrl == null || imageDataUrl.isEmpty()) {
            throw new Exception("图片数据为空");
        }
        boolean validImage = imageDataUrl.startsWith("data:image/png;base64,")
                || imageDataUrl.startsWith("data:image/jpeg;base64,")
                || imageDataUrl.startsWith("data:image/jpg;base64,")
                || imageDataUrl.startsWith("http://")
                || imageDataUrl.startsWith("https://");
        if (!validImage) {
            throw new Exception("图片格式不支持，必须是 data:image/png;base64,... 或 data:image/jpeg;base64,... 或公网 URL");
        }

        long startTime = System.currentTimeMillis();
        org.springframework.http.client.SimpleClientHttpRequestFactory factory = new org.springframework.http.client.SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(60000);
        factory.setReadTimeout(300000);
        org.springframework.web.client.RestTemplate restTemplate = new org.springframework.web.client.RestTemplate(factory);
        org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
        headers.set("Content-Type", "application/json");
        headers.set("Authorization", "Bearer " + apiKey);

        // 智谱 GLM base64 示例不带 data:image/...;base64, 前缀，需要剥离
        final String imageUrl;
        if (("glm".equals(aiType) || "zhipu".equals(aiType)) && imageDataUrl.startsWith("data:image/")) {
            int commaIndex = imageDataUrl.indexOf(',');
            imageUrl = commaIndex > 0 ? imageDataUrl.substring(commaIndex + 1) : imageDataUrl;
        } else {
            imageUrl = imageDataUrl;
        }

        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("model", model);
        requestBody.put("messages", Arrays.asList(
            new HashMap<String, Object>() {{
                put("role", "user");
                put("content", Arrays.asList(
                    new HashMap<String, Object>() {{
                        put("type", "image_url");
                        put("image_url", new HashMap<String, String>() {{ put("url", imageUrl); }});
                    }},
                    new HashMap<String, String>() {{ put("type", "text"); put("text", systemPrompt + "\n" + userPrompt); }}
                ));
            }}
        ));
        requestBody.put("temperature", 0.7);
        requestBody.put("max_tokens", 8192);

        org.springframework.http.HttpEntity<Map<String, Object>> entity = new org.springframework.http.HttpEntity<>(requestBody, headers);

        String imagePrefix = imageDataUrl.length() > 50 ? imageDataUrl.substring(0, 50) + "..." : imageDataUrl;
        logger.info("Vision API call: model={}, url={}, apiKey={}..., imageDataLen={}, imageDataPrefix={}, contentTypes=[image_url, text]",
                model, apiUrl,
                apiKey != null && apiKey.length() > 8 ? apiKey.substring(0, 8) + "***" : "null",
                imageDataUrl.length(), imagePrefix);

        try {
            org.springframework.http.ResponseEntity<String> response = restTemplate.postForEntity(apiUrl, entity, String.class);

            ObjectMapper mapper = new ObjectMapper();
            tools.jackson.databind.JsonNode rootNode = mapper.readTree(response.getBody());
            String resultContent = rootNode.path("choices").get(0).path("message").path("content").asText();
            resultContent = resultContent.replaceAll("^[`\\s]*json[\\s]*", "").replaceAll("[`\\s]*$", "");

            tools.jackson.databind.JsonNode usageNode = rootNode.path("usage");
            int tokensUsed = usageNode.path("total_tokens").asInt(estimateTokens(systemPrompt, userPrompt, resultContent));
            int inputTokens = usageNode.path("prompt_tokens").asInt(0);
            int outputTokens = usageNode.path("completion_tokens").asInt(0);
            int cacheHitTokens = usageNode.path("prompt_cache_hit_tokens").asInt(0);
            saveVisionUsageLog(aiType, model, userPrompt, systemPrompt, resultContent, tokensUsed,
                    inputTokens, outputTokens, cacheHitTokens, (int) (System.currentTimeMillis() - startTime), true, null);
            return resultContent;
        } catch (org.springframework.web.client.HttpClientErrorException e) {
            String body = e.getResponseBodyAsString();
            logger.error("Vision API error: {} - {}", e.getStatusCode(), body);
            saveVisionUsageLog(aiType, model, userPrompt, systemPrompt, null, estimateTokens(systemPrompt, userPrompt, null),
                    0, 0, 0, (int) (System.currentTimeMillis() - startTime), false, e.getStatusCode() + " " + body);
            throw new Exception("图片识别失败: " + e.getStatusCode() + " - " + body, e);
        } catch (Exception e) {
            logger.error("Vision API exception: {}", e.getMessage());
            saveVisionUsageLog(aiType, model, userPrompt, systemPrompt, null, estimateTokens(systemPrompt, userPrompt, null),
                    0, 0, 0, (int) (System.currentTimeMillis() - startTime), false, e.getMessage());
            throw new Exception("图片识别失败: " + e.getMessage(), e);
        }
    }

    public String analyzeWithAI(String style, String question, String knowledgePoints, String referenceDocs, String taskType) throws Exception {
        ResolvedAnalysisPrompt rap = resolveAnalysisPrompt(style, taskType, PromptContext.of(currentUser.get(), null));
        String userPrompt = renderUserPrompt(rap, style, question, knowledgePoints, referenceDocs, taskType);
        String systemPrompt = rap.systemPrompt();
        PromptRef ref = rap.ref();
        ResolvedProvider rp = resolveProvider();
        if (rp != null) {
            currentKeySource.set(rp.source);
            return callAiApi(systemPrompt, userPrompt, rp.type, rp.key, rp.url, rp.model, ref);
        }
        return callAiApi(systemPrompt, userPrompt, aiApiType, aiApiKey, aiApiUrl, "glm-4.5-air", ref);
    }

    public String analyzeWithAIStream(String style, String question, String knowledgePoints, String referenceDocs,
                                      String taskType, StreamTokenConsumer tokenConsumer) throws Exception {
        ResolvedAnalysisPrompt rap = resolveAnalysisPrompt(style, taskType, PromptContext.of(currentUser.get(), null));
        String userPrompt = renderUserPrompt(rap, style, question, knowledgePoints, referenceDocs, taskType);
        String systemPrompt = rap.systemPrompt();
        PromptRef ref = rap.ref();
        ResolvedProvider rp = resolveProvider();
        if (rp != null) {
            currentKeySource.set(rp.source);
            return callAiApiStream(systemPrompt, userPrompt, rp.type, rp.key, rp.url, rp.model, tokenConsumer, ref);
        }
        return callAiApiStream(systemPrompt, userPrompt, aiApiType, aiApiKey, aiApiUrl, "glm-4.5-air", tokenConsumer, ref);
    }

    private static class ResolvedProvider {
        String type, key, url, model, source;
        int priority;
    }

    private ResolvedProvider resolveProvider() {
        List<ResolvedProvider> candidates = resolveProviders();
        return candidates.isEmpty() ? null : candidates.get(0);
    }

    private List<ResolvedProvider> resolveProviders() {
        String defaultModel = "glm-4.5-air";
        List<ResolvedProvider> candidates = new ArrayList<>();

        // Public keys
        try {
            List<AiProviderConfig> publicConfigs = aiProviderConfigService.listSafe();
            for (AiProviderConfig pc : publicConfigs) {
                if (!Boolean.TRUE.equals(pc.getEnabled())) continue;
                ResolvedProvider rp = new ResolvedProvider();
                rp.type = apiType(pc.getProviderCode());
                rp.key = aiProviderConfigService.resolveApiKey(pc.getProviderCode());
                rp.url = chatEndpoint(pc);
                rp.model = pc.getChatModel() == null || pc.getChatModel().isEmpty() ? defaultModel : pc.getChatModel();
                rp.source = "public";
                rp.priority = pc.getPriority() != null ? pc.getPriority() : 100;
                candidates.add(rp);
            }
        } catch (Exception e) {
            logger.warn("Failed to load public providers: {}", e.getMessage());
        }

        // User private keys
        Integer userId = currentUser.get();
        if (userId != null) {
            try {
                List<AiUserKey> userKeys = aiUserKeyService.listEnabledByUser(userId);
                for (AiUserKey uk : userKeys) {
                    if (!Boolean.TRUE.equals(uk.getEnabled()) || uk.getApiKeyCipher() == null) continue;
                    ResolvedProvider rp = new ResolvedProvider();
                    rp.type = apiType(uk.getProviderCode());
                    rp.key = aiUserKeyService.decryptKey(uk.getApiKeyCipher());
                    rp.url = chatEndpointFromBase(uk.getApiBaseUrl());
                    rp.model = uk.getChatModel() == null || uk.getChatModel().isEmpty() ? defaultModel : uk.getChatModel();
                    rp.source = "private";
                    rp.priority = uk.getPriority() != null ? uk.getPriority() : 100;
                    candidates.add(rp);
                }
            } catch (Exception e) {
                logger.warn("Failed to load user keys: {}", e.getMessage());
            }
        }

        // Sort by priority ascending (lowest number = highest priority)
        candidates.sort(Comparator.comparingInt(r -> r.priority));
        return candidates;
    }

    /**
     * Fallback path used after the primary Spring AI provider has exhausted
     * transient retries or its circuit is open. The public provider/model used
     * by Spring AI is excluded while independent private credentials remain
     * eligible fallback candidates.
     */
    public String analyzeWithFallbackProviders(
            String style, String question, String knowledgePoints,
            String referenceDocs, String taskType) throws Exception {
        ResolvedAnalysisPrompt rap = resolveAnalysisPrompt(
                style, taskType, PromptContext.of(currentUser.get(), null));
        String userPrompt = renderUserPrompt(
                rap, style, question, knowledgePoints, referenceDocs, taskType);
        List<ResolvedProvider> candidates = resolveProviders();
        Exception last = null;
        for (ResolvedProvider provider : candidates) {
            if (isSpringPrimaryProvider(provider)) {
                continue;
            }
            try {
                currentKeySource.set(provider.source);
                return callAiApi(rap.systemPrompt(), userPrompt, provider.type, provider.key,
                        provider.url, provider.model, rap.ref());
            } catch (Exception error) {
                last = error;
            }
        }
        if (last != null) {
            throw last;
        }
        throw new IllegalStateException("No secondary AI provider is enabled");
    }

    public String analyzeStreamWithFallbackProviders(
            String style, String question, String knowledgePoints,
            String referenceDocs, String taskType,
            StreamTokenConsumer tokenConsumer) throws Exception {
        ResolvedAnalysisPrompt rap = resolveAnalysisPrompt(
                style, taskType, PromptContext.of(currentUser.get(), null));
        String userPrompt = renderUserPrompt(
                rap, style, question, knowledgePoints, referenceDocs, taskType);
        List<ResolvedProvider> candidates = resolveProviders();
        Exception last = null;
        for (ResolvedProvider provider : candidates) {
            if (isSpringPrimaryProvider(provider)) {
                continue;
            }
            try {
                currentKeySource.set(provider.source);
                return callAiApiStream(rap.systemPrompt(), userPrompt, provider.type, provider.key,
                        provider.url, provider.model, tokenConsumer, rap.ref());
            } catch (Exception error) {
                last = error;
            }
        }
        if (last != null) {
            throw last;
        }
        throw new IllegalStateException("No secondary AI provider is enabled");
    }

    private boolean isSpringPrimaryProvider(ResolvedProvider candidate) {
        if (!"public".equals(candidate.source)) {
            return false;
        }
        AiProviderConfig primary = aiProviderConfigService.getFirstEnabled();
        return primary != null
                && Objects.equals(apiType(primary.getProviderCode()), candidate.type)
                && Objects.equals(primary.getChatModel(), candidate.model);
    }

    private String chatEndpointFromBase(String baseUrl) {
        String base = baseUrl == null ? "" : baseUrl.replaceAll("/+$", "");
        if (base.endsWith("/chat/completions")) {
            return base;
        }
        return base + "/chat/completions";
    }

    private String apiType(String providerCode) {
        return providerCode;
    }

    private String chatEndpoint(AiProviderConfig provider) {
        String baseUrl = provider.getApiBaseUrl() == null ? "" : provider.getApiBaseUrl().replaceAll("/+$", "");
        if (baseUrl.endsWith("/chat/completions")) {
            return baseUrl;
        }
        return baseUrl + "/chat/completions";
    }

    public String analyzeWithCustomAI(String aiType, String apiKey, String apiUrl, String model, 
                                      String style, String question, String knowledgePoints) throws Exception {
        return analyzeWithCustomAI(aiType, apiKey, apiUrl, model, style, question, knowledgePoints, null);
    }

    public String analyzeWithCustomAI(String aiType, String apiKey, String apiUrl, String model, 
                                      String style, String question, String knowledgePoints, String referenceDocs) throws Exception {
        return analyzeWithCustomAI(aiType, apiKey, apiUrl, model, style, question, knowledgePoints, referenceDocs, "chat");
    }

    public String analyzeWithCustomAI(String aiType, String apiKey, String apiUrl, String model,
                                      String style, String question, String knowledgePoints, String referenceDocs, String taskType) throws Exception {
        ResolvedAnalysisPrompt rap = resolveAnalysisPrompt(style, taskType, PromptContext.of(currentUser.get(), null), false);
        String userPrompt = renderUserPrompt(rap, style, question, knowledgePoints, referenceDocs, taskType);
        return callAiApi(rap.systemPrompt(), userPrompt, aiType, apiKey, apiUrl, model, rap.ref());
    }

    public String analyzeWithCustomAIStream(String aiType, String apiKey, String apiUrl, String model,
                                            String style, String question, String knowledgePoints, String referenceDocs,
                                            String taskType, StreamTokenConsumer tokenConsumer) throws Exception {
        ResolvedAnalysisPrompt rap = resolveAnalysisPrompt(style, taskType, PromptContext.of(currentUser.get(), null), false);
        String userPrompt = renderUserPrompt(rap, style, question, knowledgePoints, referenceDocs, taskType);
        return callAiApiStream(rap.systemPrompt(), userPrompt, aiType, apiKey, apiUrl, model, tokenConsumer, rap.ref());
    }

    private String renderUserPrompt(ResolvedAnalysisPrompt rap, String style, String question,
                                    String knowledgePoints, String referenceDocs, String taskType) {
        if (isWorkbenchTask(taskType)) {
            return WorkbenchPromptTemplates.render(
                    rap.template().getUserPromptTemplate(), style, taskType,
                    question, knowledgePoints, referenceDocs);
        }
        return rap.formatUserPrompt(question, knowledgePoints, referenceDocs);
    }

    private boolean isWorkbenchTask(String taskType) {
        return "explain".equals(taskType)
                || "exam".equals(taskType)
                || "practice".equals(taskType)
                || "explain_question".equals(taskType)
                || "explain_knowledge".equals(taskType)
                || "learning_profile".equals(taskType)
                || "free_chat".equals(taskType);
    }

    private String callAiApi(String systemPrompt, String userPrompt, String aiType,
                            String apiKey, String apiUrl, String model, PromptRef ref) throws Exception {
        long startTime = System.currentTimeMillis();
        org.springframework.http.client.SimpleClientHttpRequestFactory factory = new org.springframework.http.client.SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(60000);
        factory.setReadTimeout(600000);
        org.springframework.web.client.RestTemplate restTemplate = new org.springframework.web.client.RestTemplate(factory);
        org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
        headers.set("Content-Type", "application/json");

        Map<String, Object> requestBody = new HashMap<>();

        if ("glm".equals(aiType) || "zhipu".equals(aiType)) {
            headers.set("Authorization", "Bearer " + apiKey);
            requestBody.put("model", model != null && !model.isEmpty() ? model : "glm-4.5-air");
            requestBody.put("messages", Arrays.asList(
                new HashMap<String, Object>() {{
                    put("role", "system");
                    put("content", systemPrompt != null ? systemPrompt : "你是一个专业的计算机考研408辅导老师。");
                }},
                new HashMap<String, Object>() {{
                    put("role", "user");
                    put("content", userPrompt);
                }}
            ));
            requestBody.put("temperature", 0.7);
            requestBody.put("max_tokens", 4096);
        } else {
            headers.set("Authorization", "Bearer " + apiKey);
            requestBody.put("model", model != null && !model.isEmpty() ? model : "gpt-4o");
            requestBody.put("messages", Arrays.asList(
                new HashMap<String, Object>() {{
                    put("role", "system");
                    put("content", systemPrompt != null ? systemPrompt : "你是一个专业的计算机考研408辅导老师。");
                }},
                new HashMap<String, Object>() {{
                    put("role", "user");
                    put("content", userPrompt);
                }}
            ));
            requestBody.put("temperature", 0.7);
            requestBody.put("max_tokens", 4096);
        }

        org.springframework.http.HttpEntity<Map<String, Object>> entity = new org.springframework.http.HttpEntity<>(requestBody, headers);
        
        try {
            org.springframework.http.ResponseEntity<String> response = restTemplate.postForEntity(apiUrl, entity, String.class);
            
            System.out.println("AI API响应状态: " + response.getStatusCode());

            ObjectMapper mapper = new ObjectMapper();
            tools.jackson.databind.JsonNode rootNode = mapper.readTree(response.getBody());
            
            String resultContent = "";
            if ("glm".equals(aiType) || "zhipu".equals(aiType)) {
                tools.jackson.databind.JsonNode choices = rootNode.path("choices");
                if (choices.isArray() && choices.size() > 0) {
                    tools.jackson.databind.JsonNode message = choices.get(0).path("message");
                    resultContent = message.path("content").asText();
                    if (resultContent.isEmpty()) {
                        resultContent = message.path("reasoning_content").asText();
                    }
                } else {
                    System.out.println("GLM API返回格式异常，尝试其他路径...");
                    resultContent = rootNode.path("response").asText();
                    if (resultContent.isEmpty()) {
                        resultContent = rootNode.path("data").path("content").asText();
                    }
                }
            } else {
                resultContent = rootNode.path("choices").get(0).path("message").path("content").asText();
            }
            
            String cleanedResult = cleanAiAnswer(resultContent);
            tools.jackson.databind.JsonNode usageNode = rootNode.path("usage");
            int tokensUsed = usageNode.path("total_tokens").asInt(estimateTokens(systemPrompt, userPrompt, cleanedResult));
            int inputTokens = usageNode.path("prompt_tokens").asInt(0);
            int outputTokens = usageNode.path("completion_tokens").asInt(0);
            int cacheHitTokens = usageNode.path("prompt_cache_hit_tokens").asInt(0);
            saveUsageLog(aiType, model, userPrompt, systemPrompt, cleanedResult, tokensUsed,
                    inputTokens, outputTokens, cacheHitTokens, (int) (System.currentTimeMillis() - startTime), true, null, ref);
            return cleanedResult;
        } catch (Exception e) {
            System.err.println("AI API调用失败: " + e.getMessage());
            saveUsageLog(aiType, model, userPrompt, systemPrompt, null, estimateTokens(systemPrompt, userPrompt, null),
                    0, 0, 0,
                    (int) (System.currentTimeMillis() - startTime), false, e.getMessage(), ref);
            throw new Exception("AI分析失败: " + e.getMessage(), e);
        }
    }

    private String callAiApiStream(String systemPrompt, String userPrompt, String aiType,
                                   String apiKey, String apiUrl, String model,
                                   StreamTokenConsumer tokenConsumer, PromptRef ref) throws Exception {
        long startTime = System.currentTimeMillis();
        String resolvedModel = model != null && !model.isEmpty() ? model : ("glm".equals(aiType) ? "glm-4.5-air" : "gpt-4o");
        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("model", resolvedModel);
        requestBody.put("messages", Arrays.asList(
            new HashMap<String, Object>() {{
                put("role", "system");
                put("content", systemPrompt != null ? systemPrompt : "你是一个专业的计算机考研408辅导老师。");
            }},
            new HashMap<String, Object>() {{
                put("role", "user");
                put("content", userPrompt);
            }}
        ));
        requestBody.put("temperature", 0.7);
        requestBody.put("max_tokens", 4096);
        requestBody.put("stream", true);

        StringBuilder fullResponse = new StringBuilder();
        HttpURLConnection connection = null;
        try {
            URL url = new URL(apiUrl);
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setConnectTimeout(60000);
            connection.setReadTimeout(600000);
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setRequestProperty("Accept", "text/event-stream");
            connection.setRequestProperty("Authorization", "Bearer " + apiKey);

            byte[] body = objectMapper.writeValueAsString(requestBody).getBytes(StandardCharsets.UTF_8);
            try (OutputStream outputStream = connection.getOutputStream()) {
                outputStream.write(body);
            }

            int statusCode = connection.getResponseCode();
            InputStream inputStream = statusCode >= 200 && statusCode < 300
                ? connection.getInputStream()
                : connection.getErrorStream();

            if (statusCode < 200 || statusCode >= 300) {
                String errorBody = readAll(inputStream);
                throw new Exception("AI流式调用失败: HTTP " + statusCode + " - " + errorBody);
            }

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String trimmed = line.trim();
                    if (!trimmed.startsWith("data:")) {
                        continue;
                    }
                    String data = trimmed.substring(5).trim();
                    if (data.isEmpty()) {
                        continue;
                    }
                    if ("[DONE]".equals(data)) {
                        break;
                    }
                    String token = extractStreamToken(data);
                    if (token != null && !token.isEmpty()) {
                        fullResponse.append(token);
                        tokenConsumer.accept(token);
                    }
                }
            }

            String result = cleanAiAnswer(fullResponse.toString());
            int total = estimateTokens(systemPrompt, userPrompt, result);
            int out = estimateTokens(null, null, result);
            saveUsageLog(aiType, resolvedModel, userPrompt, systemPrompt, result, total,
                total - out, out, 0, (int) (System.currentTimeMillis() - startTime), true, null, ref);
            return result;
        } catch (Exception e) {
            int total = estimateTokens(systemPrompt, userPrompt, fullResponse.toString());
            saveUsageLog(aiType, resolvedModel, userPrompt, systemPrompt, fullResponse.length() == 0 ? null : fullResponse.toString(),
                total, 0, 0, 0, (int) (System.currentTimeMillis() - startTime), false, e.getMessage(), ref);
            throw new Exception("AI流式分析失败: " + e.getMessage(), e);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private String extractStreamToken(String data) {
        try {
            tools.jackson.databind.JsonNode rootNode = objectMapper.readTree(data);
            tools.jackson.databind.JsonNode choices = rootNode.path("choices");
            if (choices.isArray() && choices.size() > 0) {
                tools.jackson.databind.JsonNode choice = choices.get(0);
                tools.jackson.databind.JsonNode delta = choice.path("delta");
                tools.jackson.databind.JsonNode contentNode = delta.path("content");
                String content = contentNode.isTextual() ? contentNode.asText() : "";
                if (!content.isEmpty() && !"null".equalsIgnoreCase(content)) {
                    return content;
                }
            }
            return "";
        } catch (Exception e) {
            logger.debug("Failed to parse stream chunk: {}", e.getMessage());
            return "";
        }
    }

    private String readAll(InputStream inputStream) throws Exception {
        if (inputStream == null) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                builder.append(line).append('\n');
            }
        }
        return builder.toString();
    }

    private String cleanAiAnswer(String answer) {
        if (answer == null) {
            return null;
        }
        String cleaned = answer.trim();
        cleaned = cleaned.replaceFirst("^(?i)(?:null\\s*)+", "");
        cleaned = cleaned.replaceFirst("(?s)^(好的，?)?\\s*(我需要|让我|现在我将|我将|我来)\\s*[^#\\n]*(?:\\n|。|：|:)+\\s*", "");
        cleaned = cleaned.replaceFirst("(?s)\\s*(现在我已经完成|我已经完成|以上就是我|这样我就完成了)[^#]*$", "");
        cleaned = cleaned.replaceAll("(?m)^#\\s*$", "");
        return cleaned.trim();
    }

    private void saveUsageLog(String aiType, String model, String question, String prompt, String response,
                              Integer tokensUsed, Integer inputTokens, Integer outputTokens, Integer cacheHitTokens,
                              Integer durationMs, Boolean success, String errorMessage, PromptRef ref) {
        saveUsageLog(aiType, model, "runtime", question, prompt, response,
                tokensUsed, inputTokens, outputTokens, cacheHitTokens, durationMs, success, errorMessage, ref);
    }

    private void saveVisionUsageLog(String aiType, String model, String question, String prompt, String response,
                                     Integer tokensUsed, Integer inputTokens, Integer outputTokens, Integer cacheHitTokens,
                                     Integer durationMs, Boolean success, String errorMessage) {
        saveUsageLog(aiType, model, "vision", question, prompt, response,
                tokensUsed, inputTokens, outputTokens, cacheHitTokens, durationMs, success, errorMessage,
                PromptRef.unknown("vision.ocr"));
    }

    private void saveUsageLog(String aiType, String model, String style, String question, String prompt, String response,
                              Integer tokensUsed, Integer inputTokens, Integer outputTokens, Integer cacheHitTokens,
                              Integer durationMs, Boolean success, String errorMessage, PromptRef ref) {
        try {
            AiUsageLog log = new AiUsageLog();
            log.setStyle(style);
            log.setAiType(aiType);
            log.setModel(model);
            log.setQuestion(limitText(question, 6000));
            log.setKnowledgePoints("");
            log.setPrompt(limitText(prompt, 6000));
            log.setResponse(limitText(response, 6000));
            log.setResponseLength(response == null ? 0 : response.length());
            log.setTokensUsed(tokensUsed);
            log.setInputTokens(inputTokens);
            log.setOutputTokens(outputTokens);
            log.setCacheHitTokens(cacheHitTokens);
            log.setCost(AiPricing.calculateCost(model, inputTokens, outputTokens, cacheHitTokens));
            log.setDurationMs(durationMs);
            log.setSuccess(success);
            log.setErrorMessage(limitText(errorMessage, 1000));
            log.setUserId(currentUser.get());
            log.setKeySource(currentKeySource.get());
            if (ref != null) {
                log.setPromptKey(ref.promptKey());
                log.setPromptVersionId(ref.versionId());
                log.setPromptReleaseId(ref.releaseId());
            }
            log.setCreateTime(new Date());
            aiUsageLogMapper.insert(log);
        } catch (Exception logError) {
            logger.warn("Failed to save AI usage log: {}", logError.getMessage());
        }
    }

    private int estimateTokens(String systemPrompt, String userPrompt, String response) {
        int length = 0;
        if (systemPrompt != null) {
            length += systemPrompt.length();
        }
        if (userPrompt != null) {
            length += userPrompt.length();
        }
        if (response != null) {
            length += response.length();
        }
        return Math.max(1, length / 3);
    }

    private String limitText(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }
}
