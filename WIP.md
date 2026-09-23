# WIP: 設定画面のルートにアイコンと2つのアクションを足す

Issue #103 / v1.40 リリース前に入れる。

## 目的

タイトル行の歯車（#98）から入る設定のルート画面を、サイドバー最下段の「設定」行と
同じ見た目・同じ品揃えにする。入口が2つある状態で行き先の内容が違うと、利用者は
「上から入った設定」と「下から入った設定」を別物だと受け取るため。

将来サイドバー最下段の行を引退させる前提の布石でもある（引退の条件は Issue #103 に記載）。

## やること

1. ルート画面の3項目にアイコンを足す（接続設定・再生設定・表示設定）
2. 「録画の再読み込み」をルート画面に足す
3. 「アップデートを確認」をルート画面に足す

## 決めたこと

- **アイコン**: leanback-preference の `leanback_preference.xml` に `icon_frame` と
  `@android:id/icon` の枠が最初からあるので、`app:icon` を書けば出る。サイドバーのカードと
  同じ drawable を使う（`ic_settings_connection` / `ic_settings_player` / `ic_settings_image`）
- **2つのアクションは `PreferenceCategory` でまとめる**。値を変える設定ではなく、押すと
  その場で何かが起きるものなので、見た目で区別が付くようにする
- **「録画の再読み込み」の合図は SharedPreferences 経由**。既存の「検索履歴を今すぐ消す」
  （`pref_key_clear_history`）が同じ形で、押すと SharedPreferences を書き換え、MainFragment の
  `mDisplayPrefChangeListener` が拾って動く。`SettingsActivity` は `windowIsTranslucent` で
  MainActivity が `PAUSED` 止まり（`onStop` が呼ばれない）ため、リスナーは登録されたままで
  **その場で効く**。実機で dumpsys 確認済み
- **`SettingsActivity` を `android.app.Activity` から `FragmentActivity` へ変更する**。
  `AppUpdateDialogFragment` が `androidx.fragment.app.DialogFragment` なので
  `supportFragmentManager` が要る。テーマの親は `Theme.Leanback` で AppCompat ではないため
  `AppCompatActivity` は使えないが、`FragmentActivity` は AppCompat を要求しない。
  既存の platform fragment（`LeanbackSettingsFragment`）はそのまま動く

## 残タスク

- [ ] preferences.xml: 3画面に app:icon
- [ ] preferences.xml: アクション2つを PreferenceCategory で追加
- [ ] strings.xml / values-ja-rJP: 新しい pref key と、必要ならラベル
- [ ] SettingsActivity を FragmentActivity へ
- [ ] SettingsFragment: 2つの setOnPreferenceClickListener
- [ ] MainFragment: 再読み込みの合図を拾う
- [ ] 実機確認

## 実機確認の観点

- 歯車から入ったルート画面に、アイコン付きで5項目が並ぶこと
- 「録画の再読み込み」を押して、戻ったときに一覧が読み直されていること
- 「アップデートを確認」を押してダイアログが出ること（設定画面の上に出る）
- サイドバー最下段の既存5カードが従来どおり動くこと（壊していないこと）
- 接続設定・再生設定・表示設定へ入って戻る動作が従来どおりであること
