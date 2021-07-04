# EPGStation の録画を見る
"EPGStationの録画を見る"  はAndroid TV向けに設計されたEPGStation クライアントアプリです。

Webブラウザを持たないAndroid TV から EPGStationの録画を見るために開発されました

## デモ
![](https://raw.githubusercontent.com/wiki/daig0rian/epcltvapp/images/demo.gif)

## Google Play URL
https://play.google.com/store/apps/details?id=com.daigorian.epcltvapp

 - PCのWebブラウザで上記URLにアクセスして Android TV端末へ送信 するのが一番楽なインストール方法です。
 - Android TV 端末上のPlay Storeでは epgst と検索すると見つかります。

## amazon appstore URL
https://www.amazon.co.jp/dp/B0977CXK64/

 - PCのWebブラウザでamazonにログインした状態で上記URLにアクセスすると Fire TV端末へ送信できます。
 - Fire TV ではリモコンに話しかけるインアプリ音声検索が使えません。(Fire TVの仕様)

## 特徴
 - Android TV むけ標準UIのLeanbackテーマを使用
 - Android TVのリモコンだけで操作が完結
 - Android TVのリモコン内蔵マイクから録画済番組を声で検索
 - MX Player やVLCといった外部動画プレーヤーに対応することで高い再生性能を確保

## 必要な環境
 - EPGStation Version 1.x.x or 2.x.x　
 - Android 5.1 以上の Android TV
    または
 - すべての Amazon Fire TV 
   - Fire TV ではインアプリ音声検索が使えません。(Fire TVの仕様)

## テスト環境
 - SONY ブラビア KJ-43X8000H (4K液晶androidテレビ)
 - Google Chromecast with Google TV snow　GA01919-JP (HDMIドングル型 Android TV端末) 
 - Fire TV Stick - 第2世代（Fire OS 5)
 - Fire TV Stick 4K - 第1世代（Fire OS 6)
 - EPGStation Version 1.7.6
 - EPGStation Version 2.3.8
 

## 今後やりたいこと
 - ~~動画リストの一番右に「さらに読み込む」ボタンの追加~~ DONE!
 - ~~検索履歴を残して再検索を楽にする~~ DONE !
 - ~~設定画面をLeanbackSettingsFragmentに変えてかっこよくする~~  DONE !
 - ~~検索画面をメイン画面とシームレスに接続する~~  DONE !
 - ~~割と入手性の良い端末であるAmazon FireTVシリーズへの対応~~  DONE !
 - ~~Amazonのストアでの公開~~  DONE !
 - 録画情報の表示画面が5行から伸びないのを直したい（直し方がわからない。）
 - 内蔵プレイヤーでコメントとか表示してみたい。（何にも調べてない。）

## たぶんできないこと
 - 録画予約関連UIの実装
   -  ソファーにLeanback（ふんぞり返り）ながらリモコンでやる作業ではないのではないか説
   -  ソファーでスマホからEPGStationの秀逸なUIを操作するのが現実的という説

## 現在の公開状況
 - 2021/06/10 Google Play Storeでの公開審査完了 (3日間 審査1回目でパス)
 - 2021/06/11 Android TV 端末への配信オプトイン審査完了 (操作から1日未満で完了)
 - 2021/07/01 amazon appstore 公開審査完了 (26日間 審査7回目でパス)
