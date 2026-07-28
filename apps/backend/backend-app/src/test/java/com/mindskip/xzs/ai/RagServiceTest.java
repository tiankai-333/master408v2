package com.mindskip.xzs.ai;

import com.mindskip.xzs.domain.rag.RagLexicalHit;
import com.mindskip.xzs.domain.rag.RagRetrievalLogRecord;
import com.mindskip.xzs.repository.RagRetrievalMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RagServiceTest {

    @AfterEach
    void clearUser() {
        RagService.clearCurrentUserId();
    }

    @Test
    void fusesDeduplicatesReranksAndPersistsEvidence() {
        @SuppressWarnings("unchecked")
        ObjectProvider<VectorStore> provider = mock(ObjectProvider.class);
        VectorStore vectors = mock(VectorStore.class);
        when(provider.getIfAvailable()).thenReturn(vectors);
        when(vectors.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(
                Document.builder().text("进程切换需要保存上下文")
                        .metadata(Map.of("chunk_id", 10L, "citation_label", "操作系统"))
                        .score(0.91).build(),
                Document.builder().text("缓存内容")
                        .metadata(Map.of("chunk_id", 11L, "citation_label", "计算机组成"))
                        .score(0.7).build()));
        RagRetrievalMapper mapper = mock(RagRetrievalMapper.class);
        when(mapper.selectLexicalCandidates(any(), anyInt())).thenReturn(List.of(
                lexical(10L, "操作系统", "进程上下文切换", 4D),
                lexical(12L, "补充资料", "进程与线程", 2D)));
        doAnswer(invocation -> {
            invocation.<RagRetrievalLogRecord>getArgument(0).setId(99L);
            return 1;
        }).when(mapper).insertRetrievalLog(any(RagRetrievalLogRecord.class));
        RagService service = new RagService(provider, mapper, true, 0.5, 3);
        RagService.setCurrentUserId(7);

        List<RagService.RagDocument> results = service.retrieve("进程上下文切换", 2);

        assertThat(results).hasSize(2);
        assertThat(results.get(0).getId()).isEqualTo(10L);
        assertThat(results.get(0).getVectorScore()).isEqualTo(0.91);
        assertThat(results.get(0).getLexicalScore()).isEqualTo(1D);
        assertThat(results.get(0).getRetrievalLogId()).isEqualTo(99L);
        verify(mapper).insertCitation(99L, 10L, 1,
                results.get(0).getRerankScore(), "操作系统");
    }

    @Test
    void keepsLexicalRecallAvailableWhenVectorStoreFails() {
        @SuppressWarnings("unchecked")
        ObjectProvider<VectorStore> provider = mock(ObjectProvider.class);
        VectorStore vectors = mock(VectorStore.class);
        when(provider.getIfAvailable()).thenReturn(vectors);
        when(vectors.similaritySearch(any(SearchRequest.class)))
                .thenThrow(new IllegalStateException("qdrant down"));
        RagRetrievalMapper mapper = mock(RagRetrievalMapper.class);
        when(mapper.selectLexicalCandidates(any(), anyInt())).thenReturn(List.of(
                lexical(12L, "本地资料", "死锁必要条件", 3D)));
        RagService service = new RagService(provider, mapper, true, 0.5, 3);

        List<RagService.RagDocument> results = service.retrieve("死锁必要条件", 5);

        assertThat(results).extracting(RagService.RagDocument::getId).containsExactly(12L);
    }

    @Test
    void marksOnlyCitationsActuallyReferencedByTheAnswer() {
        @SuppressWarnings("unchecked")
        ObjectProvider<VectorStore> provider = mock(ObjectProvider.class);
        RagRetrievalMapper mapper = mock(RagRetrievalMapper.class);
        RagService service = new RagService(provider, mapper, false, 0.5, 3);
        RagService.RagDocument first = new RagService.RagDocument("资料A", "a", 0.5, 1L);
        first.setRankNo(1);
        first.setRetrievalLogId(8L);
        RagService.RagDocument second = new RagService.RagDocument("资料B", "b", 0.4, 2L);
        second.setRankNo(2);
        second.setRetrievalLogId(8L);

        service.markCitationsUsed(List.of(first, second), "结论来自【参考1】。");

        verify(mapper).markCitationUsed(8L, 1);
    }

    private RagLexicalHit lexical(Long id, String title, String content, Double score) {
        RagLexicalHit value = new RagLexicalHit();
        value.setId(id);
        value.setTitle(title);
        value.setContent(content);
        value.setLexicalScore(score);
        return value;
    }
}
