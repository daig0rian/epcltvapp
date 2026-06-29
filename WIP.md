# 内蔵プレーヤーを ExoPlayer に移行

## 目的
libVLC ベースの内蔵プレーヤーを ExoPlayer (Media3) に完全移行。mirakurun-tvinput の知見を活かし、TS コンテンツで ARIB 字幕・デュアルモノ副音声に対応する。

## ブランチ
`feature/exoplayer-migration`

## 完了済み
- [x] build.gradle: libVLC → Media3 ExoPlayer 依存関係入替、NDK/CMake 設定追加
- [x] git submodule: tsreadex, libaribcaption 追加（mirakurun-tvinput と同じコミット）
- [x] ネイティブ: CMakeLists.txt, tsreadex_jni.cpp, aribcaption_jni.cpp 作成
- [x] Kotlin 移植: TsReadexFilter, TsReadexDataSource, AribCaptionFilter, CaptionImage, SubtitleOverlayView
- [x] PlaybackVideoFragment: ExoPlayer + VideoSupportFragment + LeanbackPlayerAdapter で書き換え
  - TS/エンコード済み/HLS のコンテンツタイプ別 MediaSource 生成
  - ARIB 字幕・文字スーパー処理（PES リスナー + SubtitleOverlayView）
  - デュアルモノ副音声切替（TrackSelectionOverride）
  - 3つのボタン: 字幕(CC)・文字スーパー(SI)・副音声切替
  - ボタン状態は SharedPreferences で永続化
  - 副音声なし時はボタン無効化、検出後に有効化
- [x] VlcPlayerAdapter.kt, video_layout.xml 削除
- [x] VideoDetailsFragment: IS_TS_CONTENT フラグ伝達
- [x] strings.xml: プレーヤーラベル変更（非推奨→内蔵）、ボタン用文字列追加

## 残タスク
- [ ] ビルド確認（Android Studio で assembleDebug）
- [ ] 動作確認（エンコード済み動画、TS 動画、HLS 追いかけ再生）

## 重要な決定事項
- libVLC を完全削除し ExoPlayer に一本化（H.264 緑フチ問題も解消）
- ARIB 字幕・デュアルモノ副音声は TS コンテンツのみ
- V1 API サポートは維持
- VLC 化以前の VideoSupportFragment ベースに戻した上で ExoPlayer 化
