package io.github.shohei0205.yamamuki.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class StepSliderStateTest {
    @Test
    fun tapSavesLatestValueWithoutRedrawing() {
        val state = StepSliderState(20, 5)
        var saved = 20
        val finish = { saved = state.snapped }

        // 画面更新前に値の変更と操作完了が続いても、新しい値を保存する。
        state.dragging = 60f
        finish()

        assertEquals(60, saved)
        assertEquals(60, StepSliderState(saved, 5).snapped)
    }

    @Test
    fun dragSavesLastValueRoundedToStep() {
        val state = StepSliderState(20, 5)
        val finish = { state.snapped }
        state.dragging = 30f
        state.dragging = 62f
        state.dragging = 68f

        assertEquals(70, finish())
    }

    @Test
    fun elevationAndPrecisionUseTheirOwnSteps() {
        val elevation = StepSliderState(0, 100)
        elevation.dragging = 1560f
        assertEquals(1600, elevation.snapped)

        val precision = StepSliderState(0, 1)
        precision.dragging = 2f
        assertEquals(2, precision.snapped)
    }
}
