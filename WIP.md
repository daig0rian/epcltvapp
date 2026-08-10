# WIP: OS設定に依存しない字幕スタイル

## 目的

エンコード済み動画の字幕を、OSのユーザー補助の字幕設定のON/OFFに関わらず常に同じ見た目で
表示する。狙う見た目は「半透明の黒地に白文字・パディングほぼなし・縁取りなし」。

現状は OS 字幕設定が OFF のとき、`SubtitleView.setUserDefaultStyle()` が
`CaptionStyleCompat.DEFAULT`(白文字・**不透明**な黒背景)へフォールバックして映像が黒帯で
潰れるのを避けるため、独自スタイルを当てている。その独自スタイルが `windowColor` +
既定文字サイズ3倍水増しという構成のため、OS設定 ON のときと見た目が変わってしまう。

## 決定事項

- **OS設定を一切参照しない完全固定にする。** `applySubtitleStyle()` の `CaptioningManager`
  分岐を丸ごと削除する。OS側の `fontScale` は効かなくなるが、見た目の一貫性を優先する。
- 下地は `windowColor` ではなく **`backgroundColor`** に指定する。
  - `background` は `BackgroundColorSpan` としてグリフに密着して塗られ、`textPaddingX` と無関係。
  - `window` はブロック全体を1つの矩形で塗り、`textPaddingX`(既定文字サイズ×0.125)の余白がつく。
  - ARIB字幕(libaribcaption)も文字セル単位で下地を焼き込んだビットマップを返すので、
    見た目の統一という当初の目的にも `background` の方が近い。
- 不透明度は **50%** → `Color.argb(128, 0, 0, 0)`
- 縁取りは **`EDGE_TYPE_NONE`**（ARIB字幕にも縁取りがないため）
- フォントは引き続き `resolveGothicTypeface()` で日本語ゴシックを明示指定する

## 完了済み

- [x] OSの字幕設定が `CaptionStyleCompat` としてどう解決されるかをログ出力する調査コードを追加
      (`logUserCaptionStyle()` / `edgeTypeName()`)。**移行完了後に削除する。**

## 残タスク

- [ ] 実機で `logUserCaptionStyle()` の出力を確認し、再現すべき6つの値を確定する
      （logcat を `userCaptionStyle` で絞る）
- [ ] `applySubtitleStyle()` を固定スタイルに置き換える
  - `CaptioningManager` の分岐と import を削除
  - `SUBTITLE_WINDOW_COLOR` → `SUBTITLE_BACKGROUND_COLOR = Color.argb(128, 0, 0, 0)`
  - `setFractionalTextSize()` から `SUBTITLE_PADDING_SCALE` 倍を削除
- [ ] `adjustCue()` から `.setTextSize(...)` を削除する
  - `Tx3gParser` は Cue に `textSize` を設定しないため、Cue側が unset なら
    `SubtitleView` の既定文字サイズがそのまま効く（`SubtitlePainter.java:236,248`）
  - `TypefaceSpan` の剥がしは ffmpeg が付ける "Serif" 対策として**残す**
- [ ] 定数 `SUBTITLE_PADDING_SCALE` とフィールド `subtitleTextSizeFraction` を削除
- [ ] `logUserCaptionStyle()` / `edgeTypeName()` を削除
- [ ] 生TSのARIB字幕と並べて見た目を確認する

## 調査メモ (media3 1.3.1 のソース根拠)

- `SubtitleView.java:333-343` — `setUserDefaultStyle()` は `CaptioningManager.isEnabled()` が
  false なら OS を読まず `CaptionStyleCompat.DEFAULT` を返す
- `SubtitlePainter.java:271-283` — `backgroundColor` は `BackgroundColorSpan` として本文に付く。
  `EDGE_TYPE_OUTLINE` のときは縁取りレイヤー側に付け替えられる
- `SubtitlePainter.java:237,298` — `textPaddingX = 既定文字サイズ × 0.125` はレイアウト幅に
  常に加算される（window色の有無と無関係）
- `SubtitlePainter.java:417-425` — 矩形を実際に塗るのは `Color.alpha(windowColor) > 0` のときだけ
- `SubtitlePainter.java:163` — window色は Cue 側 (`cue.windowColorSet`) に上書きされうる。
  `backgroundColor` にはこの上書き経路がない
- `Tx3gParser.java:187-188,283-285` — Cue に line/lineAnchor は設定するが textSize は設定しない。
  fontFamily が sans-serif 以外のとき `TypefaceSpan` を付ける
