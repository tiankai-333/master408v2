package com.mindskip.xzs.ai.prompt;

import com.mindskip.xzs.domain.ai.AiPromptDefinition;
import com.mindskip.xzs.domain.ai.AiPromptVersion;

/**
 * 运行时获取 Prompt 的唯一入口。
 * <p>
 * 设计要点：
 * <ul>
 *   <li>{@link #resolve} 只读内存缓存，绝不落库，保证运行时低延迟且 DB 异常时不影响已发布 Prompt 读取；</li>
 *   <li>缓存为最后一次成功加载的快照，DB 异常时保留旧缓存（"兜底"）；</li>
 *   <li>管理端每次发版/回滚/kill-switch 后调用 {@link #refresh()} 让运行时立即生效。</li>
 * </ul>
 */
public interface PromptRegistry {

    /** 解析当前生效的版本（含灰度分桶）。未命中返回 null，调用方自行走 JSON 兜底。 */
    ResolvedPrompt resolve(String promptKey, PromptContext ctx);

    /** 取某个具体版本（用于 Playground 测试草稿，绝不经过发布通道）。 */
    AiPromptVersion getVersion(Long versionId);

    AiPromptDefinition getDefinition(String promptKey);

    /** 从 DB 重新加载缓存。管理端发版后调用。 */
    void refresh();
}
