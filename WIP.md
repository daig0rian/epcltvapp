# WIP: ライブ視聴カードのヒーローアイコンを放送局アイコンに (Issue #35)

## 目的
ライブ視聴のチャンネル一覧カードが常に「NO IMAGE」アイコンになっている問題を解消し、
EPGStationが提供する放送局ロゴ画像を表示する。

## 調査結果
- EPGStation V2 API に `GET /api/channels/{channelId}/logo` が存在する（`l3tnun/EPGStation` の
  `src/model/service/api/channels/{channelId}/logo.ts` で確認）。ロゴが無いチャンネルは404を返す。
- `ChannelItem` のAPIスキーマには `hasLogoData: boolean` フィールドがあり、事前にロゴの有無を判定できる。
- ロゴ画像は Mirakurun 経由でARIB放送データ（logo_type 0x05、推定200×112px）から取得され、
  このアプリのカード比率（313×176 ≒ 1.78:1）とほぼ一致するが、正確な解像度は未検証（実機確認が必要）。
- V1 (EpgStation) にはライブ視聴UI自体が存在しないため、今回はV2のみ対応。

## 完了済み
- [x] `ChannelItem.kt` に `hasLogoData: Boolean = false` を追加
- [x] `EpgStationV2.kt` に `getChannelLogoURL(channelId: Long): String` を追加
      （`baseUrl + "channels/" + channelId + "/logo"`、`getThumbnailURL` と同パターン）
- [x] `OriginalCardPresenter.kt` の `is ChannelItem ->` 分岐を変更：
      `item.hasLogoData` が true の場合、既存の `authForGlide` パターンでGlide読み込みを行い、
      失敗時は `mDefaultCardImage`（NO IMAGE）にフォールバック。false の場合は従来通り即NO IMAGE。
      スケールは `centerCrop()` ではなく `fitCenter()` を採用（ロゴは透過/白背景が多く、
      クロップで端が切れるより収める方が見た目が良いと判断。実機での見た目確認が必要）。

## 残タスク
- [ ] Android Studio でビルド・実機/エミュレーターでの動作確認
  - ロゴがあるチャンネルで正しく表示されるか
  - ロゴが無い/取得失敗時にNO IMAGEへ正しくフォールバックするか
  - `fitCenter()` の見た目（レターボックス部分の背景色など）が許容できるか。
    気になる場合は `centerCrop()` への変更や、`mainImageView` の背景色調整を検討。
- [ ] 動作確認OKならコミット → PR作成

## 重要な決定事項
- `hasLogoData` による事前判定を採用し、ロゴが無いことが分かっているチャンネルへの
  無駄なHTTPリクエスト（404待ち）を避けた。
- V1 (`EpgStation`/`ChannelItemV1`) は対象外（ライブ視聴機能がV1には無いため）。
