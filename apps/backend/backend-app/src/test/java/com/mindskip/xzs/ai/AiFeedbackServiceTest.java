package com.mindskip.xzs.ai;

import com.mindskip.xzs.repository.AiUsageLogMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiFeedbackServiceTest {

    @Test
    void updatesOnlyTheCurrentUsersObservedCall() {
        AiUsageLogMapper mapper = mock(AiUsageLogMapper.class);
        when(mapper.updateFeedbackForUser(9, 3, 5, "helpful")).thenReturn(1);

        boolean updated = new AiFeedbackService(mapper).submit(9, 3, 5, " helpful ");

        assertThat(updated).isTrue();
        verify(mapper).updateFeedbackForUser(9, 3, 5, "helpful");
    }

    @Test
    void rejectsInvalidRatingsBeforeWriting() {
        AiUsageLogMapper mapper = mock(AiUsageLogMapper.class);

        assertThatThrownBy(() -> new AiFeedbackService(mapper).submit(9, 3, 6, "x"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
