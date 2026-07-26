package com.mindskip.xzs.ai.prompt;

import com.mindskip.xzs.domain.ai.AiPromptDefinition;
import com.mindskip.xzs.domain.ai.AiPromptRelease;
import com.mindskip.xzs.domain.ai.AiPromptVersion;
import com.mindskip.xzs.repository.AiPromptDefinitionMapper;
import com.mindskip.xzs.repository.AiPromptReleaseMapper;
import com.mindskip.xzs.repository.AiPromptVersionMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DbPromptRegistryTest {

    private AiPromptDefinitionMapper defMapper;
    private AiPromptVersionMapper versionMapper;
    private AiPromptReleaseMapper releaseMapper;
    private DbPromptRegistry registry;

    @BeforeEach
    void setup() {
        defMapper = mock(AiPromptDefinitionMapper.class);
        versionMapper = mock(AiPromptVersionMapper.class);
        releaseMapper = mock(AiPromptReleaseMapper.class);
        registry = new DbPromptRegistry(defMapper, versionMapper, releaseMapper);
    }

    @Test
    void cacheMissReturnsNull() {
        when(defMapper.selectAll()).thenReturn(List.of());
        when(releaseMapper.selectAll()).thenReturn(List.of());
        registry.refresh();
        assertThat(registry.resolve("analysis.unknown", PromptContext.of(1, null))).isNull();
    }

    @Test
    void canaryZeroServesStableAnd100ServesCanary() {
        AiPromptDefinition def = definition(1L, "analysis.default");
        AiPromptVersion stable = version(10L, 1, "stable system", "stable user");
        AiPromptVersion canary = version(11L, 2, "canary system", "canary user");
        AiPromptRelease rel = release(1L, 1L, 10L, 11L, 0, "active");

        when(defMapper.selectAll()).thenReturn(List.of(def));
        when(releaseMapper.selectAll()).thenReturn(List.of(rel));
        when(versionMapper.selectByIds(any(Collection.class))).thenReturn(List.of(stable, canary));

        registry.refresh();
        ResolvedPrompt r = registry.resolve("analysis.default", PromptContext.of(7, null));
        assertThat(r.versionId()).isEqualTo(10L);
        assertThat(r.systemPrompt()).isEqualTo("stable system");
        assertThat(r.canaryPercent()).isZero();

        // canary 100% → 永远命中 canary（floorMod < 100 恒真）
        rel.setCanaryPercent(100);
        registry.refresh();
        r = registry.resolve("analysis.default", PromptContext.of(7, null));
        assertThat(r.versionId()).isEqualTo(11L);
        assertThat(r.systemPrompt()).isEqualTo("canary system");
    }

    @Test
    void disabledFreezesCanaryAndStillServesStable() {
        AiPromptDefinition def = definition(1L, "analysis.default");
        AiPromptVersion stable = version(10L, 1, "stable system", "stable user");
        AiPromptVersion canary = version(11L, 2, "canary system", "canary user");
        AiPromptRelease rel = release(1L, 1L, 10L, 11L, 100, "disabled");

        when(defMapper.selectAll()).thenReturn(List.of(def));
        when(releaseMapper.selectAll()).thenReturn(List.of(rel));
        when(versionMapper.selectByIds(any(Collection.class))).thenReturn(List.of(stable, canary));
        registry.refresh();

        ResolvedPrompt r = registry.resolve("analysis.default", PromptContext.of(7, null));
        assertThat(r.killed()).isTrue();
        assertThat(r.canaryPercent()).isZero(); // 生效比例被冻结为 0
        assertThat(r.versionId()).isEqualTo(10L); // 仍服务 stable
    }

    @Test
    void refreshFailureKeepsCachedSnapshot() {
        AiPromptDefinition def = definition(1L, "analysis.default");
        AiPromptVersion stable = version(10L, 1, "stable system", "stable user");
        AiPromptRelease rel = release(1L, 1L, 10L, null, 0, "active");
        when(defMapper.selectAll()).thenReturn(List.of(def));
        when(releaseMapper.selectAll()).thenReturn(List.of(rel));
        when(versionMapper.selectByIds(any(Collection.class))).thenReturn(List.of(stable));
        registry.refresh();
        assertThat(registry.resolve("analysis.default", PromptContext.of(1, null)).versionId()).isEqualTo(10L);

        // DB 异常 → 保留旧缓存，不清空
        when(defMapper.selectAll()).thenThrow(new RuntimeException("db down"));
        registry.refresh();
        ResolvedPrompt r = registry.resolve("analysis.default", PromptContext.of(1, null));
        assertThat(r).isNotNull();
        assertThat(r.versionId()).isEqualTo(10L);
    }

    private static AiPromptDefinition definition(Long id, String key) {
        AiPromptDefinition d = new AiPromptDefinition();
        d.setId(id);
        d.setPromptKey(key);
        d.setEnabled(Boolean.TRUE);
        return d;
    }

    private static AiPromptVersion version(Long id, int no, String system, String user) {
        AiPromptVersion v = new AiPromptVersion();
        v.setId(id);
        v.setVersionNo(no);
        v.setSystemPrompt(system);
        v.setUserPromptTemplate(user);
        v.setStatus("active");
        return v;
    }

    private static AiPromptRelease release(Long id, Long defId, Long stable, Long canary, int percent, String status) {
        AiPromptRelease r = new AiPromptRelease();
        r.setId(id);
        r.setDefinitionId(defId);
        r.setStableVersionId(stable);
        r.setCanaryVersionId(canary);
        r.setCanaryPercent(percent);
        r.setStatus(status);
        return r;
    }
}
