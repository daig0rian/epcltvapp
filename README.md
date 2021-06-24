# EPGStation の録画を見る
"EPGStationの録画を見る"  はAndroid TV向けに設計されたEPGStation クライアントアプリです。

Webブラウザを持たないAndroid TV から EPGStationの録画を見るために開発されました

## デモ
![](https://raw.githubusercontent.com/wiki/daig0rian/epcltvapp/images/demo.gif)

## Google Play URL
https://play.google.com/store/apps/details?id=com.daigorian.epcltvapp

 - パソコンで上記URLにアクセスして Android TV端末へ送信 するのが一番楽なインストール方法です。
 - Android TV 端末上のPlay Storeでは epgst と検索すると見つかります。

## Amazon app ストア
 - 審査通らないので断念しました。
 - [リリース](https://github.com/daig0rian/epcltvapp/releases)のページにある apk リンクを [Downloader](https://www.amazon.co.jp/dp/B01N0BP507) を使って直接ロードしてください。
 - 設定 > マイFire TV > 開発者オプション > 不明ソースからのアプリ 　を　"オン" にする必要があります。

## 特徴
 - Android TV むけ標準UIのLeanbackテーマを使用
 - Android TVのリモコンだけで操作が完結
 - Android TVのリモコン内蔵マイクから録画済番組を声で検索
 - MX Player やVLCといった外部動画プレーヤーに対応することで高い再生性能を確保

## 必要な環境
 - EPGStation Version 1.x.x or 2.x.x　
 - APIレベル22(Android 5.1,Lollipop MR1)以上の Android TV

## テスト環境
 - SONY BRAVIA KJ-43X8000H (4K液晶androidテレビ)
 - Google Chromecast with Google TV snow　GA01919-JP (HDMIドングル型 Android TV端末) 
 - EPGStation Version 1.7.6
 - EPGStation Version 2.3.8

## 今後やりたいこと
 - ~~動画リストの一番右に「さらに読み込む」ボタンの追加~~ DONE!
 - ~~検索履歴を残して再検索を楽にする~~ DONE !
 - ~~設定画面をLeanbackSettingsFragmentに変えてかっこよくする~~  DONE !
 - 検索画面をメイン画面とシームレスに接続する
 - 割と入手性の良い端末であるAmazon FireTVシリーズへの対応 一応 DONE,少しTODO残あり
 - ~~Amazonのストアでの公開~~　断念
 - 初回起動時のOnboarding画面の追加
 - ホームスクリーンに最近の録画を表示させることができる
 - 録画情報の表示画面が5行から伸びないのを直したい（調査着手したけど直し方がわからないのでペンディング中）

## たぶんできないこと
 - 録画予約関連UIの実装
   -  ソファーにLeanback（ふんぞり返り）ながらリモコンでやる作業ではないのではないか説
   -  ソファーでスマホからEPGStationの秀逸なUIを操作するのが現実的という説

## 現在の公開状況
 - 2021/06/10 Google Play Storeでの公開審査完了 (ストア登録から３日くらいかかったかな)
 - 2021/06/11 Android TV 端末への配信オプトイン審査完了 (操作から1日未満で完了)
