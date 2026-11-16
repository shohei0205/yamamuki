// SPDX-FileCopyrightText: 2026 The yamamuki Authors
// SPDX-License-Identifier: MIT

package io.github.shohei0205.yamamuki

/**
 * 版によって出し入れする機能。
 */
object Features {
    /**
     * 目的地の山データを、都道府県単位で Overpass から前もってダウンロードする(方位盤の左下のボタンと、その画面)。
     * 全国の山データを yamamuki-data からまとめて取得するようにしたので、止めている。
     */
    const val AREA_DOWNLOAD = false

    /**
     * 方位盤の背景に地表データ(森林・水域・陰影)を描く(山データの選択画面の「地表データ」、設定の「地表を表示」、出典)。
     * yamamuki-data が地表データを正式に公開するまでは、開発用のビルドだけで出す。
     */
    val SURFACE = BuildConfig.DEBUG
}
