# WIP: 字幕・音声トラック選択の一般化 (Issue #54)

ブランチ: `feature/subtitle-audio-track-selection`

## 目的

エンコード済み動画（および変換ありのライブTSプロファイル）でも、ExoPlayer が検出した
トラック構成に応じて字幕・音声切り替えUIを出す。あわせて「tsreadex を通してよいのは
どの入力か」の判定を実態に合わせて整理する。

## 中心となる設計判断: 生TS / 加工済TS

tsreadex の `servicefilter` は PMT を**作り直す**。出力に残るのは
video(0x0100) / audio1(0x0110) / audio2(0x0111) / caption(0x0130) / superimpose(0x0138)
の5本のみで、ARIB字幕（`stream_type=0x06` かつ `component_tag=0x30/0x87`）以外の
字幕ストリームはPMTからもパケット列からも**消える**。

したがって入力を2種類に分ける必要がある。

| 区分 | 中身 | 該当するもの | tsreadex |
|---|---|---|---|
| **生TS** | 放送波そのもの。ARIB字幕以外が付く余地がない | 録画オリジナルTS（追っかけ再生含む）/ ライブTS（無変換プロファイル） | **通す**（消える情報がないため正当） |
| **加工済TS** | 既にストリームが加工済み。音声が最適化されていたり、ARIB字幕が他形式へ変換されている可能性がある | エンコード済み動画 / ライブTS（無変換以外のプロファイル） | **通さない** |

### 現状の不整合（本ブランチで修正する）

```kotlin
isTsContent = (intent の IS_TS_CONTENT) || isLiveMpegTs   // ← isLiveMpegTs が無条件
useNativeTsProcessing = isTsContent && nativeTsProcessingPref
```

ライブTSで無変換以外のプロファイルを選んでいても tsreadex を通してしまう。
判定材料は既にある（`M2tsStreamParam.isUnconverted` / `EpgStationV2.resolveM2tsProfileIndex`）。

フラグを意味で切り直す:

| 名前 | 意味 | 用途 |
|---|---|---|
| `isTsContent` | TSコンテナか（既存の意味を維持） | `TsReadexDataSource` でのラップ・シーク方式の判定 |
| `isRawTs` | 生TSか（新規） | tsreadex を通すかの判定 |
| `useNativeTsProcessing` | `isRawTs && ユーザー設定` | ARIB字幕/文字スーパー処理・UI |

ユーザー設定（デフォルトOFF）は Issue #33 の保険として維持する。
`streamConfig` 未取得時は `isUnconverted` を判定できないため false（＝通さない）に倒す。

## UI方針

- **字幕**: ON/OFF の1トグル。ONのとき「解釈できる字幕」を描画する
  （ARIB字幕は libaribcaption + `SubtitleOverlayView`、それ以外は ExoPlayer の
  text トラック + `SubtitleView`）。両方含まれるケースは実質ありえないので考慮しない。
- **文字スーパー(SI)**: ARIB固有のため生TS + ネイティブ処理ON のときのみ（現状維持）。
- **音声**: 1トラック目=白 / 2トラック目=青 の現行UIのまま。3トラック以上は考慮しない。
  ボタンを出す条件だけ `hasSubAudio`（2トラック以上）に変える。
- ボタンは動的に出し入れするが、順序は常に `CC → SI → 音声 → REC → 情報` で固定する
  （トラック検出のタイミングで並びが変わらないように、挿入位置を予約する）。

## 完了済み

- [x] ブランチ作成・WIP.md 作成
- [x] 依存関係の確認: `media3-ui-leanback` は `media3-ui` を推移的に持たないため、
      `SubtitleView`（Cue描画）を使うには `androidx.media3:media3-ui` の追加が必要。
      キャッシュ済み AAR の manifest で media3 1.3.1 の minSdk が 19 であることを確認済み
      （minSdk 22 は維持される）
- [x] `app/build.gradle` に `media3-ui:1.3.1` を追加
- [x] `isRawTs` の導入とライブTSプロファイル判定（`isUnconverted`）
- [x] `onTracksChanged` で text トラックも収集し `hasTextTrack` を持つ
- [x] `SubtitleView` の追加と `Player.Listener.onCues()` の実装
- [x] text トラックの選択/解除（`applyTextTrackSelection()`）
- [x] `onCreatePrimaryActions` のゲート解除と動的なボタン出し入れ
      （`trackActionIndex` で挿入位置を予約し `refreshTrackActions()` で入れ直す）

## 動作確認で出た問題と対応

- [x] 字幕の下地が不透明な黒 → `windowColor` に移して半透明化（`33936db` / `c83844e` / `36fb0c0`）
- [x] フォントが明朝体 → tx3g が font name "Serif" を `TypefaceSpan` として本文に付けており、
      `CaptionStyleCompat` の typeface を上書きしていた。`adjustCue()` で除去（`20d3ded`）
- [x] 表示位置が低い → tx3g パーサが必ず `line=0.85` を設定するため
      `bottomPaddingFraction` が効かない。`adjustCue()` で 0.80 に統一（`20d3ded`）
- [x] 下地の左右に余白がない → `backgroundColor` は `BackgroundColorSpan` として適用される
      ため。`windowColor` へ移し、既定文字サイズの水増しで余白量を確保（`36fb0c0` / `23766a6`）
- [x] シーク確定後にコントロールオーバーレイが閉じない（`f2e3eae`）
      - Leanback は `seekTo()` の直後に同じメッセージ内で
        `setSeekMode(false)` → `showControlsOverlay(true)` を実行し、
        フェードタイマーは再開しない（`startFadeTimer` の呼び出し元は
        `tickle()` / `onResume()` / `setControlsOverlayAutoHideEnabled()` のみ）。
        同期的に閉じると開き直されるうえ `stopFadeTimer()` でタイマーごと消えるため、
        `mainHandler.post` して Leanback の処理後に閉じる。
- [ ] **字幕がたまに「詰まる」（再現待ち）**
      - 症状: 字幕が止まりセリフに追従しなくなった後、読めない速さで一気に流れて追いつく
      - 動画によらず発生。内容由来（長時間保持のキュー）ではないとユーザー確認済み
      - ExoPlayer 側の単純な枯渇は否定的: videoId=722 では字幕サンプル全399個が先頭78MB以内に
        あり、映像が t≈400s に達する時点で全て読み込み済みになる
      - 調査用に `onCues` へ一時ログを入れてある（`71c1453`）。
        logcat の時刻・`pos`・`buffered` の進み方で
        ローダー / TextRenderer への供給 / 描画側 のどれかに切り分ける

## 残タスク

- [ ] 字幕の「詰まり」の原因特定と対応
- [ ] `onCues` の調査用一時ログを削除（`71c1453` で追加、コードに TODO 記載あり）
- [ ] MANUAL.md 4.2 の制約記述を更新（Issue #54 に記載あり）
- [ ] `WIP.md` を削除してからコミットし、PR 作成

## 実装メモ

- 音声ボタンの「副音声なしのとき label を `---` にする」方式（`updateAudioActionState`）は
  廃止し、ボタン自体を出さない方式に変えた。`toggleAudioTrack()` 内の `hasSubAudio` ガードは
  到達しなくなるが防御的に残してある。
- 字幕OFF時は `setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)` でデコード自体を止める。
  ONにするときは先頭グループを `TrackSelectionOverride` で明示選択する
  （`DefaultTrackSelector` の既定は優先言語/forcedフラグ依存で「ONなら必ず出る」にならないため）。
- 再生開始前に `applyTextTrackSelection()` を1回呼び、字幕OFFのはずのtextトラックが
  検出直後に一瞬表示されるのを防いでいる。
- 字幕スタイル: `SubtitleView.setUserDefaultStyle()` は端末のユーザー補助で字幕が
  有効化されていないと `CaptionStyleCompat.DEFAULT`（白文字・**不透明な黒背景**）に
  フォールバックする。TVでは通常無効なので黒帯が出てしまうため、`applySubtitleStyle()` で
  「背景透明＋黒の縁取り」を既定にした（ARIB字幕は背景色の初期値がB24 CLUT index 8 =
  完全透明で、縁取りで可読性を確保している）。ユーザー補助で明示設定されている場合は
  そちらを優先する。
  - 明るい映像の上で読みにくいようなら、半透明の黒背景（例: `0x80000000`）に変える案もある。

## 動作確認したい組み合わせ

| 入力 | ネイティブ処理 | 期待 |
|---|---|---|
| 録画オリジナルTS | ON | 現状維持（CC/SI/音声すべて表示、ARIB字幕が出る） |
| 録画オリジナルTS | OFF | 字幕なし（libaribcaption 自体が動かないため定義どおり）・音声は実トラック数次第 |
| エンコード済み（字幕あり） | - | CCボタンが出て字幕が描画される |
| エンコード済み（複数音声） | - | 音声ボタンが出て切り替わる |
| エンコード済み（字幕なし・単一音声） | - | ボタンが出ない |
| ライブTS 無変換 | ON | 現状維持 |
| ライブTS 変換あり | ON | **tsreadex を通さなくなる**（今回の修正点） |

## 関連

- Issue #54: エンコード済み動画で字幕・音声トラックの切り替えができない
- Issue #58: `TsReadexDataSource` のクラス名が実態と合っていない（本ブランチでは触らない）
- Issue #33: ライブmpegts直送でのクラッシュ（ネイティブ処理設定の存在理由）
