package com.mindskip.xzs.ai;

import com.mindskip.xzs.repository.AiUsageLogMapper;
import org.springframework.stereotype.Service;

@Service
public class AiFeedbackService {

    private final AiUsageLogMapper usageLogMapper;

    public AiFeedbackService(AiUsageLogMapper usageLogMapper) {
        this.usageLogMapper = usageLogMapper;
    }

    public boolean submit(Integer usageLogId, Integer userId, Integer rating, String feedback) {
        if (usageLogId == null || userId == null || rating == null
                || rating < 1 || rating > 5) {
            throw new IllegalArgumentException("评分必须为 1 到 5");
        }
        String safeFeedback = feedback == null ? null : feedback.trim();
        if (safeFeedback != null && safeFeedback.length() > 1000) {
            throw new IllegalArgumentException("反馈内容不能超过 1000 字");
        }
        return usageLogMapper.updateFeedbackForUser(
                usageLogId, userId, rating, safeFeedback) == 1;
    }
}
