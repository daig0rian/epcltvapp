# 内蔵プレーヤーを ExoPlayer に移行

## 目的
libVLC ベースの内蔵プレーヤーを ExoPlayer (Media3) に完全移行。mirakurun-tvinput の知見を活かし、TS コンテンツで ARIB 字幕・デュアルモノ副音声に対応する。

## ブランチ
`feature/exoplayer-migration`

## 完了済み
- [x] build.gradle: libVLC → Media3 ExoPlayer 依存関係入替、NDK/CMake 設定追加
- [x] git submodule: tsreadex, libaribcaption 追加
- [x] ネイティブ: CMakeLists.txt, tsreadex_jni.cpp, aribcaption_jni.cpp 作成
- [x] Kotlin 移植: TsReadexFilter, TsReadexDataSource, AribCaptionFilter, CaptionImage, SubtitleOverlayView
- [x] PlaybackVideoFragment: ExoPlayer + VideoSupportFragment + LeanbackPlayerAdapter で書き換え
- [x] TS 再生: TsReadexDataSource 経由で再生成功（LENGTH_UNSET 修正済み）
- [x] ARIB 字幕: PES 抽出 → libaribcaption デコード → SubtitleOverlayView 描画が動作
- [x] 文字スーパー・デュアルモノ副音声: ボタン追加、SharedPreferences で状態永続化
- [x] VlcPlayerAdapter.kt, video_layout.xml 削除
- [x] VideoDetailsFragment: IS_TS_CONTENT フラグ伝達
- [x] コントロールオーバーレイ: 初回表示時の自動非表示が動作

## 既知の制限（このブランチでは対応しない）
- 再表示時のコントロールオーバーレイ自動非表示が効かない
- 再表示時のフォーカスがシークバーに当たる（一時停止ボタンが望ましい）
- TS コンテンツのシークが不可（LENGTH_UNSET を返しているため）

## 残タスク
- [ ] 動作確認（エンコード済み動画、HLS 追いかけ再生）
- [ ] ユーザー確認後 PR 作成
