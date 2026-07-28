-- MySQL ngram full-text index supplies a local lexical recall path for Chinese
-- content and keeps RAG available when the external vector store is unavailable.
ALTER TABLE `rag_chunk`
    ADD FULLTEXT INDEX `ft_rag_chunk_hybrid` (`content_text`, `citation_label`)
    WITH PARSER ngram;
