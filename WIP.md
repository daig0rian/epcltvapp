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

- [x] コミット・プッシュ済み（PRはブラウザから作成する必要あり: `gh` CLI 未インストール）

## 完了（続き: カスタムURL実行ボタン）

ユーザー要望：サーバー側にシャットダウン用URL等を用意しておき、アプリからワンタッチで
GETリクエストを送れるボタンが欲しい。セキュリティ考慮は不要（家庭内システムのため）。

- [x] `preferences.xml` の接続設定に「ボタンの名称」「実行するURL」の入力欄を追加
- [x] `SettingsFragment.kt` にURLの簡易バリデーション追加（http(s)://始まりのみ）
- [x] `SettingsCardPresenter.kt`
  - [x] `Action` enum に `CUSTOM_URL` を追加
  - [x] `Item` に `iconChar: String?` を追加。指定時は `TextIconDrawable`（自前の
        簡易Drawable、1文字を中央描画するだけ）でアイコンとして描画。
        今回はU+2934(⤴)をアイコン文字として使用
- [x] `CustomUrlAction.kt` 新規作成（`HttpURLConnection` でGETするだけの薄いユーティリティ）
- [x] `MainFragment.kt`
  - [x] `updateWakeOnLanCard()` を `syncOptionalSettingsCards()` に統合。固定4枚
        （接続設定/プレイヤー/表示設定/再読み込み）より後ろを毎回作り直す方式にして、
        WAKE_ON_LAN・CUSTOM_URLの2枚が常に正しい順序（再読み込みの次にWOL、その次にURL）
        で並ぶようにした（個別add/remove方式だと順序保証が面倒になるため）
  - [x] `mDisplayPrefChangeListener` にカスタムURL関連キー変更時の同期処理を追加
  - [x] `ItemViewClickedListener` に `CUSTOM_URL` クリック時のGET送信処理を追加
- [x] `strings.xml` / `values-ja-rJP/strings.xml` に関連文字列追加
- [x] `assembleDebug` ビルド成功確認

- [x] 実機確認OK（ユーザー確認済み: 「カスタムURLボタン、上手く行ったね！」）
- [x] コミット・プッシュ済み

## 残タスク

- [ ] PRの作成（ブラウザから。`gh` CLI がこの環境にないため）
      URL: https://github.com/imaiworks/epcltvapp/pull/new/feature/wake-on-lan
