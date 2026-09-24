# WIP: ネイティブTS処理の初期状態をONにする

ブランチ `chore/default-native-ts-processing-on`

## 目的

「ネイティブTS処理」の初期状態をONにする。放送そのままのTSでも、最初から字幕・副音声・
文字スーパーが使える状態にするため。

## 完了

- `preferences.xml` の `defaultValue` を `true` に
- `PlaybackVideoFragment.kt` の `getBoolean` の既定値を `true` に（2箇所の既定値は揃える必要がある）
- MANUAL.md の3箇所（4.3節のCCボタン、5.2節の項目説明、その下の注意書き）を更新
- Issue #33（ライブmpegts直送のクラッシュ再現待ち）を再現報告なしで close

## 適用範囲

**新規インストールのみ。** `MainFragment.onCreate()` の
`PreferenceManager.setDefaultValues(..., readAgain = false)` は初回起動時に一度だけ
既定値を書き込むため、既存端末には保存済みの値（多くはOFF）が残る。移行処理は入れない
（自分でOFFにした人の選択を上書きしないため）。

## 残タスク

- 実機確認
- WIP.md を削除してから PR 作成
- v1.40 のリリースノートに「初期状態の変更」として載せる
