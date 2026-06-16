package com.kbo.crawlerapi.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.kbo.crawlerapi.repository.GameEventReadRepository.GameEventRow;
import java.util.List;
import org.junit.jupiter.api.Test;

class ScoringPlayNotificationFormatterTest {

    @Test
    void formatsSingleWithOneRun() {
        var detail = detail("고승민", "안타", 1, 1);

        var text = ScoringPlayNotificationFormatter.scoreChangeText(detail).orElseThrow();

        assertThat(text.title()).isEqualTo("롯데 득점");
        assertThat(text.body()).isEqualTo("고승민 안타, 1득점");
    }

    @Test
    void formatsDoubleWithTwoRuns() {
        var detail = detail("레이예스", "2루타", 2, 2, 4, 2);

        var text = ScoringPlayNotificationFormatter.scoreChangeText(detail).orElseThrow();

        assertThat(text.body()).isEqualTo("레이예스 2루타, 2득점");
    }

    @Test
    void formatsHomeRunWithOneRun() {
        var detail = detail("전준우", "홈런", null, 1, 4, 2);

        var text = ScoringPlayNotificationFormatter.scoreChangeText(detail).orElseThrow();

        assertThat(text.body()).isEqualTo("전준우 홈런, 1득점");
    }

    @Test
    void formatsHomeRunWithThreeRuns() {
        var detail = detail("전준우", "홈런", null, 3, 6, 4);

        var text = ScoringPlayNotificationFormatter.scoreChangeText(detail).orElseThrow();

        assertThat(text.body()).isEqualTo("전준우 쓰리런 홈런, 3득점");
    }

    @Test
    void formatsErrorScoringPlay() {
        var detail = detail(null, "상대 실책", null, 1, 5, 5);

        var text = ScoringPlayNotificationFormatter.scoreChangeText(detail).orElseThrow();

        assertThat(text.body()).isEqualTo("상대 실책으로 1득점");
    }

    @Test
    void extractsWalkHitByPitchAndSacrificeFly() {
        assertThat(extractResult("김민성 : 우월 투런")).isEqualTo("홈런");
        assertThat(extractResult("김민성 : 좌중간 쓰리런")).isEqualTo("홈런");
        assertThat(extractResult("김민성 : 우월 만루홈런")).isEqualTo("홈런");
        assertThat(extractResult("김민성 : 볼넷")).isEqualTo("밀어내기 볼넷");
        assertThat(extractResult("박승욱 : 몸에 맞는 공")).isEqualTo("밀어내기 사구");
        assertThat(extractResult("나승엽 : 좌익수 희생플라이 아웃")).isEqualTo("희생플라이");
    }

    @Test
    void formatsMissingBatterFallbackWhenResultIsKnown() {
        var detail = detail(null, "안타", 1, 1, 3, 2);

        var text = ScoringPlayNotificationFormatter.scoreChangeText(detail).orElseThrow();

        assertThat(text.body()).isEqualTo("안타로 1득점");
    }

    @Test
    void missingPlayDetailFallsBackToEmptyFormatterResult() {
        var detail = detail(null, null, null, 1, 3, 2);

        assertThat(ScoringPlayNotificationFormatter.scoreChangeText(detail)).isEmpty();
    }

    @Test
    void formatsLeadChangeTitleAndBody() {
        var detail = detail("전준우", "홈런", null, 3, 6, 4);

        var text = ScoringPlayNotificationFormatter.leadChangeText(detail, 3, 4).orElseThrow();

        assertThat(text.title()).isEqualTo("롯데 역전");
        assertThat(text.body()).isEqualTo("7회초 전준우 쓰리런 홈런, 3득점 · 롯데 6-4 한화");
    }

    @Test
    void formatsTieLeadChangeTitle() {
        var detail = detail(null, "상대 실책", null, 1, 5, 5);

        var text = ScoringPlayNotificationFormatter.leadChangeText(detail, 4, 5).orElseThrow();

        assertThat(text.title()).isEqualTo("동점");
    }

    @Test
    void extractsBatterResultBeforeRunMarkerFromLiveTextEvents() {
        var detail = ScoringPlayDetailExtractor.extract(
                List.of(
                        event(10, "HIT", "레이예스 : 좌익수 왼쪽 2루타"),
                        event(11, "RUN_SCORED", "2루주자 고승민 : 홈인"),
                        event(12, "RUN_SCORED", "1루주자 손성빈 : 홈인")
                ),
                context("레이예스", 2, 2, 4, 2)
        ).orElseThrow();

        assertThat(detail.batterName()).isEqualTo("레이예스");
        assertThat(detail.resultText()).isEqualTo("2루타");
        assertThat(detail.selectedEventType()).isEqualTo("HIT");
        assertThat(detail.selectedEventText()).isEqualTo("레이예스 : 좌익수 왼쪽 2루타");
        assertThat(detail.runScoredEventCount()).isEqualTo(2);
        assertThat(detail.runsScored()).isEqualTo(2);
    }

    private String extractResult(String eventText) {
        return ScoringPlayDetailExtractor.parse(eventText).orElseThrow().resultText();
    }

    private GameEventRow event(int sequence, String type, String text) {
        return new GameEventRow(sequence, 7, "top", type, text);
    }

    private ScoringPlayDetailExtractor.ScoringPlayContext context(
            String previousBatterName,
            Integer awayScoreBefore,
            Integer homeScoreBefore,
            Integer awayScoreAfter,
            Integer homeScoreAfter
    ) {
        return new ScoringPlayDetailExtractor.ScoringPlayContext(
                previousBatterName,
                7,
                "top",
                true,
                true,
                false,
                "lotte",
                "롯데",
                "lotte",
                "hanwha",
                awayScoreBefore,
                homeScoreBefore,
                awayScoreAfter,
                homeScoreAfter,
                "롯데",
                "한화"
        );
    }

    private ScoringPlayDetail detail(String batterName, String resultText, Integer hitBaseCount, Integer runsScored) {
        return detail(batterName, resultText, hitBaseCount, runsScored, 3, 2);
    }

    private ScoringPlayDetail detail(
            String batterName,
            String resultText,
            Integer hitBaseCount,
            Integer runsScored,
            Integer awayScoreAfter,
            Integer homeScoreAfter
    ) {
        return new ScoringPlayDetail(
                batterName,
                resultText,
                hitBaseCount,
                runsScored,
                runsScored,
                7,
                "top",
                "lotte",
                "롯데",
                awayScoreAfter,
                homeScoreAfter,
                "롯데",
                "한화"
        );
    }
}
