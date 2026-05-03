# EPGStation の録画を見る
"EPGStation の録画を見る" は Android TV / Fire TV 向けに設計された EPGStation クライアントアプリです。

リモコンの↑↓←→キー操作が基本となる Android TV から EPGStation の録画を見るために開発されました。

## 特徴
- Android TV 向け標準 UI の Leanback テーマを使用
- Android TV のリモコンだけで操作が完結
- Android TV のリモコン内蔵マイクから録画済番組を声で検索
- MX Player や VLC といった外部動画プレーヤーに対応
- 内蔵プレーヤーで ARIB STD-B24 字幕に対応（v1.28〜）

## 必要な環境
- EPGStation Version 1.x.x または 2.x.x（Version 1.x.x については動作確認が限定的です）
- Android 5.1 以上の Android TV / Fire TV

## テスト環境
- SONY ブラビア KJ-43X8000H（4K 液晶 Android TV）
- EPGStation Version 2.3.8

## インストール方法

### Downloader アプリを使う方法（推奨）
1. Fire TV / Android TV に「Downloader」アプリをインストール
2. [GitHub Releases](https://github.com/daig0rian/epcltvapp/releases) から最新の `app-release.apk` の URL をコピー
3. Downloader でその URL を入力してダウンロード・インストール

### ADB を使う方法
1. 端末の開発者オプションで「ADB デバッグ」を有効化
2. [GitHub Releases](https://github.com/daig0rian/epcltvapp/releases) から `app-release.apk` をダウンロード
3. PC から以下を実行：
```sh
adb install app-release.apk
```

> **v1.27 以前からアップデートする場合は、一度アンインストールしてから再インストールしてください。**  
> v1.28 より署名方式が変わったため、上書きインストールはできません。

## 今後やりたいこと
- UI を Leanback から Compose for TV へ段階的に移行
- NX-Jikkyo のコメントを内蔵プレーヤーで表示

## ストアへの公開状況
- 2021/06/10 Google Play Store での公開審査完了（3日間・審査1回目でパス）
- 2021/06/11 Android TV 端末への配信オプトイン審査完了（操作から1日未満で完了）
- 2021/07/01 Amazon Appstore 公開審査完了（26日間・審査7回目でパス）
- 2022/03/04 第三者（日本国内で放送されている内容に利害関係がある団体）からの Amazon アプリストアのコンテンツポリシー違反の指摘により Amazon アプリストアから削除
- 2022/03/04 上記指摘を受け Google Play Store での公開も停止
- v1.28 以降は [GitHub Releases](https://github.com/daig0rian/epcltvapp/releases) のみで配布
