package com.mindskip.xzs.newdb.controller;

import com.mindskip.xzs.base.RestResponse;
import com.mindskip.xzs.newdb.config.NewdbDbClient;
import com.mindskip.xzs.newdb.domain.NewdbPageQuery;
import com.mindskip.xzs.newdb.domain.NewdbQuestionDO;
import com.mindskip.xzs.newdb.repository.NewdbQuestionMapper;
import com.mindskip.xzs.newdb.viewmodel.NewdbQuestionDetailVM;
import com.mindskip.xzs.newdb.viewmodel.NewdbQuestionListVM;
import com.mindskip.xzs.newdb.viewmodel.NewdbQuestionPageRequestVM;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 题目只读查询（D-31：无编辑端点，写入唯一入口是导入管道）。
 */
@RestController
@RequestMapping("/api/newdb/question")
@Profile("newdb")
public class NewdbQuestionController {

    private static final int MAX_PAGE_SIZE = 100;
    private static final int SHORT_TITLE_LENGTH = 60;

    private final NewdbDbClient dbClient;

    public NewdbQuestionController(NewdbDbClient dbClient) {
        this.dbClient = dbClient;
    }

    @PostMapping("/page")
    public RestResponse<Object> page(@RequestBody NewdbQuestionPageRequestVM request) {
        NewdbPageQuery query = new NewdbPageQuery();
        query.setPageIndex(request.pageIndex() == null ? 1 : Math.max(1, request.pageIndex()));
        int pageSize = request.pageSize() == null ? 10 : request.pageSize();
        query.setPageSize(Math.min(Math.max(1, pageSize), MAX_PAGE_SIZE));
        query.setKeyword(blankToNull(request.keyword()));
        query.setQuestionType(request.questionType());
        query.setStatus(request.status());
        query.setSourceYear(request.sourceYear());

        record PageResult(long total, List<NewdbQuestionListVM> list) {
        }
        PageResult result = dbClient.withMapper(NewdbQuestionMapper.class, mapper -> {
            long total = mapper.countPage(query);
            List<NewdbQuestionListVM> list = mapper.page(query).stream()
                    .map(NewdbQuestionController::toListVM)
                    .toList();
            return new PageResult(total, list);
        });
        return RestResponse.ok(result);
    }

    @GetMapping("/{id}")
    public ResponseEntity<RestResponse<Object>> detail(@PathVariable long id) {
        NewdbQuestionDO row = dbClient.withMapper(NewdbQuestionMapper.class,
                mapper -> mapper.selectDetail(id));
        if (row == null) {
            RestResponse<Object> body = new RestResponse<>(404, "题目不存在");
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(body);
        }
        NewdbQuestionDetailVM vm = new NewdbQuestionDetailVM(row.getId(), row.getQuestionType(),
                row.getSubjectName(), row.getScore(), row.getSourceName(), row.getSourceYear(),
                row.getSourceQuestionNo(), row.getSourceRef(), row.getStemHtml(),
                row.getAnalysisHtml(), row.getStemText(), row.getCorrect(), row.getAnswerText(),
                row.getStatus(), row.getStatusReason(), row.getDisplayReady(), row.getLicenseTag(),
                row.getBatchId(), row.getBatchTag(), row.getCreateTime(), row.getUpdateTime());
        return ResponseEntity.ok(RestResponse.ok(vm));
    }

    private static NewdbQuestionListVM toListVM(NewdbQuestionDO row) {
        String stemText = row.getStemText() == null ? "" : row.getStemText();
        String shortTitle = stemText.length() <= SHORT_TITLE_LENGTH
                ? stemText
                : stemText.substring(0, SHORT_TITLE_LENGTH) + "…";
        return new NewdbQuestionListVM(row.getId(), row.getQuestionType(), row.getSubjectName(),
                row.getSourceName(), row.getSourceYear(), row.getSourceQuestionNo(), shortTitle,
                row.getCorrect(), row.getStatus(), row.getDisplayReady(), row.getLicenseTag(),
                row.getBatchTag(), row.getCreateTime());
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
