package com.mindskip.xzs.newdb.domain;

/**
 * 题目分页查询（controller 归一化后传入；offset 为 LIMIT 起始）。
 * 关键词走 stem_text LIKE——10 行规模不上 FULLTEXT，ngram 索引留待后续批次验证。
 */
public class NewdbPageQuery {

    private int pageIndex = 1;
    private int pageSize = 10;
    private String keyword;
    private Integer questionType;
    private Integer status;
    private Integer sourceYear;

    public int getPageIndex() {
        return pageIndex;
    }

    public void setPageIndex(int pageIndex) {
        this.pageIndex = pageIndex;
    }

    public int getPageSize() {
        return pageSize;
    }

    public void setPageSize(int pageSize) {
        this.pageSize = pageSize;
    }

    public long getOffset() {
        return (long) (pageIndex - 1) * pageSize;
    }

    public String getKeyword() {
        return keyword;
    }

    public void setKeyword(String keyword) {
        this.keyword = keyword;
    }

    public Integer getQuestionType() {
        return questionType;
    }

    public void setQuestionType(Integer questionType) {
        this.questionType = questionType;
    }

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
    }

    public Integer getSourceYear() {
        return sourceYear;
    }

    public void setSourceYear(Integer sourceYear) {
        this.sourceYear = sourceYear;
    }
}
