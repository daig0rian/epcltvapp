# WIP: Issue #34 ストリームmode設定のハードコード解消

## 目的

録画追いかけHLS・ライブHLS・ライブMpegTSの3箇所で`mode`クエリパラメータが`0`固定になっている問題を解消する。
`GET /api/config`の`streamConfig`からサーバーのプロファイル一覧を取得・キャッシュし、設定画面でユーザーが選択できるようにする。
あわせて、ライブmpegts直送で無効化されているtsreadex+libaribcaptionのネイティブTS処理（#33対応でfalse固定）を、デフォルトON・トグル可能にする。

詳細設計は plan ファイル参照（`C:\Users\daigo\.claude\plans\fuzzy-doodling-hejlsberg.md`）。

## 設計の要点（Issue本文からの重要な変更点）

- プロファイル選択の永続化は **index ではなく「プロファイル名」の文字列** で行う。config.ymlの並び順が変わってもユーザーの選択がズレないようにするため。
- 「自動」選択肢は型ごとに意味をラベルに明記する:
  - 録画追いかけHLS・ライブHLS: 「自動（サーバー設定の先頭）」
  - ライブMpegTS: 「自動（無変換を優先）」
- サーバー側の`mode`は必須パラメータ・範囲外は500エラー（`l3tnun/EPGStation`ソースで確認済み）。streamConfig未取得時のフォールバックは今と同じ`mode=0`。

## 完了済み

- [x] ブランチ作成・WIP.md作成
- [x] `StreamConfig.kt` 新規作成（data class群）
- [x] `EpgStationV2.kt`: `getConfig` API・`streamConfig`キャッシュ・`resolveHlsProfileIndex`/`resolveM2tsProfileIndex`追加
- [x] `MainFragment.kt`: `EpgStationV2.fetchStreamConfig()`呼び出し追加
- [x] `preferences.xml`: Stream Quality / TS Processing カテゴリ追加
- [x] `strings.xml` + `values-ja-rJP/strings.xml`: 新規文字列リソース追加
- [x] `SettingsFragment.kt`: 3つのListPreferenceへ動的にentries設定
- [x] `PlaybackVideoFragment.kt`: mode解決・nativeTsProcessing反映

## 残タスク

- [ ] ユーザーによる動作確認（Android Studioビルド・実機/エミュレーター）
- [ ] 動作確認OKならコミット → PR作成

## 実装メモ（動作確認時に見るポイント）

- Settings → Playback Settings に「Stream Quality」（3つのプロファイル選択）と「TS Processing」（ネイティブTS処理トグル）が追加されている
- プロファイル選択肢の先頭は常に自動ラベル（録画/ライブHLSは「自動（サーバー設定の先頭）」、ライブTSは「自動（無変換を優先）」）。以降がサーバーから取得した実プロファイル名
- 選択値はプロファイル「名前」で保存される。config.ymlの並び順を変えても、名前が存在する限りユーザーの選択は追従する

## 動作確認中に見つかった修正

- ネイティブTS処理（tsreadex+ARIB字幕）のデフォルトは、Issue #34本文では「true」提案だったが、**falseに変更**（従来通りバイパス）。
  - 初回動作確認時にライブTS再生でスピナーが止まらない・ライブHLSで「Failed to start HLS Stream」が発生。原因はEPGStation側でチューナーが全て使用中だったことと判明（コード側のバグではない）。
  - ただしユーザー判断として、#33のクラッシュ疑いが実機で未解決のため、念のためデフォルトはOFFのまま維持し、必要な人だけ設定でONにできるようにした。

## 重要な決定事項

- `recorded.encoded.hls`ではなく`recorded.ts.hls`を使う（Issue本文通り、`isEncodedVideo`判定はサーバー側が自動分岐するためアプリは関知しない）
- ビルドコマンドはClaudeが実行しない。Android Studioでユーザーが手動ビルドする。
