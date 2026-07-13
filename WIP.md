# WIP: ライブ視聴に番組情報ダイアログを追加

## 目的

録画詳細画面には既に「番組情報」ボタン（`ProgramInfoDialogFragment`）があるが、
ライブ視聴はDetailsActivityを経由せず直接再生するため同等の機能がない。
視聴画面のRECボタンの隣にℹ️アイコンを追加し、押すと現在放送中の番組情報
（チャンネル名・ジャンル・時刻・概要・詳細説明）をダイアログで見られるようにする。

## 決定事項

- 既存の `ProgramInfoDialogFragment.newInstance(title, body)` をそのまま再利用する
  （録画詳細画面の「番組情報」と同じ見た目・D-padスクロール操作）。
- 番組データは既存の `EpgStationV2.getScheduleOnAir()` （番組名更新機能で使用中）を
  そのまま使う。表示に必要な `description`/`extended`/`genre1`/`subGenre1` が
  `ScheduleProgramItem` に不足しているため追加する。
- ℹ️ボタンはHLS・mpegts直送どちらのライブ視聴でも表示（`isAnyLive`で判定、RECボタンと同条件）。
- 録画時と同じ組み立て方（チャンネル名+ジャンル+時刻+description+extended）を踏襲。

## 完了

- [x] ブランチ作成 (`feature/live-program-info`、master から分岐)
- [x] 既存の録画詳細画面の「番組情報」実装（`VideoDetailsFragment.kt`/`ProgramInfoDialogFragment.kt`）を調査

- [x] `Schedule.kt` の `ScheduleProgramItem` に `description`/`extended`/`genre1`/`subGenre1` を追加
- [x] `PlaybackVideoFragment.kt`
  - [x] `ic_action_info` アイコン追加（Material風の丸に"i"）
  - [x] `MyPlaybackTransportControlGlue` に `infoAction` を追加、RECボタンの隣に配置（`isLive`時のみ表示）
  - [x] `showCurrentProgramInfo()`: `getScheduleOnAir()` で現在番組を取得し
        チャンネル名+ジャンル+時刻+description+extendedを組み立てて
        `ProgramInfoDialogFragment` を表示（録画詳細画面と同じ組み立て方）
- [x] `assembleDebug` ビルド成功確認
- [x] 実機確認OK（ユーザー確認済み: 「素晴らしすぎる！」）

## 完了（続き: mpegts再生時にシークバーを非表示）

ユーザー要望：mpegts直送はシーク不可の生ストリームなのでシークバーは不要。
HLSはバッファ状況が見えて便利なので残したい。

- Leanbackにはシークバーの表示/非表示を切り替える公式APIが無いことが判明
  （`PlaybackBaseControlGlue.getDuration()`は`final`で上書き不可、
  `LeanbackPlayerAdapter`（Media3版）も`final`でサブクラス化不可）。
  → コントロール行のビューが実際に生成されるのを待って、
  `androidx.leanback.R.id.playback_progress` を直接 `GONE` にする方式に。
- **実機クラッシュ発生→修正**: `ClassCastException: androidx.leanback.widget.SeekBar
  cannot be cast to android.widget.SeekBar`。Leanbackは標準の`android.widget.SeekBar`
  ではなく独自の`androidx.leanback.widget.SeekBar`クラスを使っていたのが原因。
  キャスト先を`androidx.leanback.widget.SeekBar`に修正して解決。
  **`EpgTvApplication`のクラッシュログ機能が役に立った最初の実例**
  （USBデバッグなしでスタックトレースを確認できた）。

- [x] `PlaybackVideoFragment.kt` に `hideSeekBar()` を追加、`isLiveMpegTs`時のみ
      `onViewCreated()` から呼び出し
- [x] `assembleDebug` ビルド成功確認
- [x] 実機確認OK（ユーザー確認済み: 「うごいたよ！」）

## 残タスク

- [ ] コミット・PR作成
