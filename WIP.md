# WIP: OS設定に依存しない字幕スタイル

## 目的

エンコード済み動画の字幕を、OSのユーザー補助の字幕設定のON/OFFに関わらず常に同じ見た目で
表示する。狙う見た目は「半透明の黒地に白文字・パディングほぼなし・縁取りなし」。

従来は OS 字幕設定が OFF のとき、`SubtitleView.setUserDefaultStyle()` が
`CaptionStyleCompat.DEFAULT`(白文字・**不透明**な黒背景)へフォールバックして映像が黒帯で
潰れるのを避けるため、独自スタイルを当てていた。その独自スタイルが `windowColor` +
既定文字サイズ3倍水増しという構成のため、OS設定 ON のときと見た目が変わっていた。

## 決定事項と根拠

- **OS設定を一切参照しない完全固定。** `applySubtitleStyle()` から `CaptioningManager` の
  分岐を削除した。OS側の `fontScale` は効かなくなるが、見た目の一貫性を優先する。
- 下地は `windowColor` ではなく **`backgroundColor`**。
  - `background` は `BackgroundColorSpan` として字面に密着して塗られる。
  - `window` はブロック全体を1矩形で塗り、`textPaddingX`(既定文字サイズ×0.125)の余白がつく。
  - ARIB は文字セル矩形をベタ塗りするので、`background` の方が近い。
- 不透明度は **アルファ128 (50%)**。ARIB 字幕の実装を追って確定した値:
  - `b24_colors.cpp` の `kB24ColorCLUT` はフラット添字 0-64 が不透明(255)、65-127 が
    そのミラーでアルファ128。**二択で中間値は存在しない**。半透明の黒は添字65 = `(0,0,0,128)`。
  - `region_renderer.cpp:134-136` が `Canvas::ClearRect(ch.back_color, section_rect)` で
    文字セル矩形をベタ塗り。`canvas.cpp:37-50` → `alphablend_generic.hpp:80-84` の
    `FillLine` は `dest[i] = color` の**単純な上書き**でブレンドしない。
  - `SubtitleOverlayView.kt:80` は通常の SRC_OVER 合成。追加の減衰なし。
  - `force_stroke_text_` は既定 false (`region_renderer.hpp:111`) で、JNI も設定していない
    (`aribcaption_jni.cpp:29-55`)。もし true だと縁取りに `back_color` が使われ、
    文字周りだけアルファが二重に乗って実効191(≈75%)になる。今はそうならない。
- 縁取りは **`EDGE_TYPE_NONE`**。ARIB 字幕にも縁取りがないため。
- フォントは `resolveGothicTypeface()` で日本語ゴシックを明示指定（継続）。

## 実機実測ログ (Google TV Streamer, OS字幕ON時)

調査コードで採取済み。この値は既にコードへ反映済みなので、調査コードは削除した。

```
enabled=true fontScale=1.0 locale=null
foreground=FFFFFFFF(set=true) background=C0000000(set=true) window=00000000(set=true)
edgeType=NONE(set=true) edgeColor=FF000000(set=true) typeface=null
```

`window=00000000` = OS の見た目は 100% `background` が作っている。`typeface=null` = OS は
フォントを指定していないので、ゴシック体の明示指定は OS ON の見た目を壊さない。
`background` の実測は `C0`(75%) だったが、ARIB に合わせる方針で **128(50%)** を採用した。

## 完了済み

- [x] OSの字幕設定の実測値をログ出力する調査コードを追加 → 採取完了 → 削除済み
- [x] `applySubtitleStyle()` を OS 非依存の固定スタイルに置き換え
- [x] `adjustCue()` から `.setTextSize(...)` を削除
      (`Tx3gParser` は Cue に textSize を設定しないので、未設定なら `SubtitlePainter` が
      `SubtitleView` の既定文字サイズをそのまま使う。`SubtitlePainter.java:236,248`)
- [x] `SUBTITLE_PADDING_SCALE` / `subtitleTextSizeFraction` / `CaptioningManager` import を削除

## 残タスク

- [ ] **実機での動作確認**
  - エンコード済み動画の字幕が「半透明黒地・白文字・縁取りなし・ゴシック体」で出るか
  - OS の字幕設定を ON / OFF で切り替えても見た目が変わらないか
  - 折り返す長い字幕で下地が破綻しないか
  - 生TSのARIB字幕と並べて濃さを比較する
- [ ] 濃さが合わなければ `SUBTITLE_BACKGROUND_COLOR` のアルファのみ調整する
      （数値上は128が正解。ARIBの方が濃く見えるのは塗る面積の差で、印象で合わせるなら
      150〜170あたりが候補）
- [ ] 確認が取れたら `WIP.md` を削除してコミットし、PR を作成する
