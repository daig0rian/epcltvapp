# WIP: Wake on LAN 対応

## 目的

普段電源を切っているEPGStationサーバーを、アプリからWake on LANのマジックパケットを
送って起動できるようにする。

## 決定事項

- 設定画面の接続設定に **MACアドレス入力欄** を追加。
- 実際に起動する「Wake on LANで起動」ボタンは、接続設定画面の中ではなく
  **メイン画面の設定行**（接続設定・プレイヤー・表示設定・再読み込みが並ぶカード列）の
  「再読み込み」の隣に表示する（ユーザー要望）。
- MACアドレスが設定されているときだけボタンが出現する。設定/削除するとその場でカードが
  出入りする（`SharedPreferences.OnSharedPreferenceChangeListener` で検知）。
- マジックパケットはブロードキャストアドレス `255.255.255.255` のUDPポート9番に送信
  （`WakeOnLan.kt`）。特別なパーミッションは不要（既存のINTERNET権限のみ）。

## 完了

- [x] ブランチ作成 (`feature/wake-on-lan`、master から分岐)
- [x] `WakeOnLan.kt` 新規作成（マジックパケット送信ユーティリティ）
- [x] `preferences.xml` に MACアドレス入力欄を追加（接続設定画面内）
- [x] `SettingsFragment.kt` に MACアドレスの形式バリデーション追加
- [x] `SettingsCardPresenter.kt` の `Action` enum に `WAKE_ON_LAN` を追加
- [x] `MainFragment.kt`
  - [x] `loadRows()` で設定行構築後に `updateWakeOnLanCard()` を呼び、MAC設定時のみカード追加
  - [x] `mDisplayPrefChangeListener` に MACアドレス変更時のカード出し入れを追加
  - [x] `ItemViewClickedListener` に `WAKE_ON_LAN` クリック時の送信処理を追加（バックグラウンドスレッド）
- [x] `ic_settings_power.xml` アイコン追加
- [x] `strings.xml` / `values-ja-rJP/strings.xml` に関連文字列追加
- [x] `assembleDebug` ビルド成功確認
- [x] 実機確認OK（ユーザー確認済み: 「完璧！すばらしい！」）

## 残タスク

- [ ] コミット
- [ ] `gh pr create` へ誘導（この環境には `gh` CLI がないため、ブラウザでのPR作成URLを案内する）
