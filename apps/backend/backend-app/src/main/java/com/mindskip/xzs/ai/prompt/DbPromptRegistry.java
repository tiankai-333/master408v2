package com.mindskip.xzs.ai.prompt;

import com.mindskip.xzs.domain.ai.AiPromptDefinition;
import com.mindskip.xzs.domain.ai.AiPromptRelease;
import com.mindskip.xzs.domain.ai.AiPromptVersion;
import com.mindskip.xzs.repository.AiPromptDefinitionMapper;
import com.mindskip.xzs.repository.AiPromptReleaseMapper;
import com.mindskip.xzs.repository.AiPromptVersionMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class DbPromptRegistry implements PromptRegistry {

    private static final Logger logger = LoggerFactory.getLogger(DbPromptRegistry.class);

    private final AiPromptDefinitionMapper definitionMapper;
    private final AiPromptVersionMapper versionMapper;
    private final AiPromptReleaseMapper releaseMapper;

    /** key = promptKey。volatile 保证 refresh 后其它线程立刻可见。 */
    private volatile Map<String, ReleaseSnapshot> cache = Collections.emptyMap();

    public DbPromptRegistry(AiPromptDefinitionMapper definitionMapper,
                            AiPromptVersionMapper versionMapper,
                            AiPromptReleaseMapper releaseMapper) {
        this.definitionMapper = definitionMapper;
        this.versionMapper = versionMapper;
        this.releaseMapper = releaseMapper;
    }

    @PostConstruct
    public void init() {
        refresh();
    }

    @Override
    public void refresh() {
        try {
            Map<String, ReleaseSnapshot> next = loadSnapshots();
            // 只在加载成功时替换；失败保留旧缓存（兜底）。
            this.cache = Collections.unmodifiableMap(next);
            logger.info("PromptRegistry refreshed: {} definitions active", next.size());
        } catch (Exception e) {
            // 关键：DB 异常时不清空缓存，继续服务最后一次成功快照。
            logger.warn("PromptRegistry refresh failed, serving cached snapshot: {}", e.getMessage());
        }
    }

    private Map<String, ReleaseSnapshot> loadSnapshots() {
        List<AiPromptDefinition> definitions = definitionMapper.selectAll();
        List<AiPromptRelease> releases = releaseMapper.selectAll();
        if (definitions.isEmpty() || releases.isEmpty()) {
            return Collections.emptyMap();
        }

        Set<Long> versionIds = new HashSet<>();
        for (AiPromptRelease r : releases) {
            if (r.getStableVersionId() != null) versionIds.add(r.getStableVersionId());
            if (r.getCanaryVersionId() != null) versionIds.add(r.getCanaryVersionId());
        }
        Map<Long, AiPromptVersion> versionById = new HashMap<>();
        if (!versionIds.isEmpty()) {
            for (AiPromptVersion v : versionMapper.selectByIds(versionIds)) {
                versionById.put(v.getId(), v);
            }
        }
        Map<Long, AiPromptDefinition> defById = new HashMap<>();
        for (AiPromptDefinition d : definitions) {
            defById.put(d.getId(), d);
        }

        Map<String, ReleaseSnapshot> next = new HashMap<>();
        for (AiPromptRelease r : releases) {
            AiPromptDefinition d = defById.get(r.getDefinitionId());
            if (d == null) continue;
            AiPromptVersion stable = versionById.get(r.getStableVersionId());
            if (stable == null) {
                logger.warn("stable version {} not found for prompt_key={}, release skipped",
                        r.getStableVersionId(), d.getPromptKey());
                continue;
            }
            AiPromptVersion canary = r.getCanaryVersionId() == null ? null : versionById.get(r.getCanaryVersionId());
            int percent = r.getCanaryPercent() == null ? 0 : r.getCanaryPercent();
            boolean disabled = "disabled".equalsIgnoreCase(r.getStatus());
            next.put(d.getPromptKey(),
                    new ReleaseSnapshot(d.getId(), r.getId(), stable, canary, percent, disabled));
        }
        return next;
    }

    @Override
    public ResolvedPrompt resolve(String promptKey, PromptContext ctx) {
        ReleaseSnapshot snap = cache.get(promptKey);
        if (snap == null) {
            return null;
        }
        // kill-switch：冻结灰度（有效比例 0），仍服务 stable。
        int effectivePercent = snap.disabled() ? 0 : snap.canaryPercent();
        AiPromptVersion chosen = (snap.canary() != null && effectivePercent > 0
                && bucket(ctx, snap) < effectivePercent)
                ? snap.canary() : snap.stable();
        return new ResolvedPrompt(
                promptKey, snap.definitionId(), chosen.getId(), snap.releaseId(),
                chosen.getSystemPrompt(), chosen.getUserPromptTemplate(),
                chosen.getVariablesJson(), chosen.getModelParamsJson(),
                effectivePercent, snap.disabled());
    }

    @Override
    public AiPromptVersion getVersion(Long versionId) {
        return versionMapper.selectById(versionId);
    }

    @Override
    public AiPromptDefinition getDefinition(String promptKey) {
        return definitionMapper.selectByKey(promptKey);
    }

    /** 稳定分桶：同一学生/会话始终命中同一版本。返回 0..99。 */
    private int bucket(PromptContext ctx, ReleaseSnapshot snap) {
        String s;
        if (ctx != null && ctx.userId() != null) {
            s = "u:" + ctx.userId();
        } else if (ctx != null && ctx.conversationId() != null) {
            s = "c:" + ctx.conversationId();
        } else if (ctx != null && ctx.fallbackKey() != null) {
            s = "k:" + ctx.fallbackKey();
        } else {
            s = "k:" + snap.definitionId();
        }
        return Math.floorMod(s.hashCode(), 100);
    }

    /** 不可变快照：一个 prompt_key 当前发布状态。 */
    private record ReleaseSnapshot(Long definitionId, Long releaseId,
                                   AiPromptVersion stable, AiPromptVersion canary,
                                   int canaryPercent, boolean disabled) {
    }
}
