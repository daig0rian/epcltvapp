package com.daigorian.epcltvapp

import android.content.Context
import android.content.SharedPreferences
import androidx.preference.PreferenceManager


object Settings {

    const val IP_ADDR_KEY = "IP_ADDR"
    const val IP_ADDR_DEFAULT = "192.168.0.0"

    const val PORT_NUM_KEY = "PORT_NUM"
    const val PORT_NUM_DEFAULT = 8888

    private var mChanged = false


    private const val FETCH_LIMIT_24 = 31L
    private const val FETCH_LIMIT_48 = 32L
    private const val FETCH_LIMIT_72 = 33L
    private const val FETCH_LIMIT_96 = 34L
    val FETCH_LIMIT_ID_TO_NUM = mapOf(
        FETCH_LIMIT_24 to 24,
        FETCH_LIMIT_48 to 48,
        FETCH_LIMIT_72 to 72,
        FETCH_LIMIT_96 to 96)

    val FETCH_LIMIT_ID_TO_DESCRIPTION = mapOf(
        FETCH_LIMIT_24 to "デフォルト",
        FETCH_LIMIT_48 to "",
        FETCH_LIMIT_72 to "",
        FETCH_LIMIT_96 to "")

    const val FETCH_LIMIT_KEY = "FETCH_LIMIT"
    val FETCH_LIMIT_DEFAULT = FETCH_LIMIT_ID_TO_NUM[FETCH_LIMIT_24]!!

    const val PLAYER_ID_INTERNAL = 40L
    const val PLAYER_ID_VLC = 41L
    const val PLAYER_ID_MX = 42L

    val PLAYER_ID_TO_PACKAGE = mapOf(
        PLAYER_ID_VLC to "org.videolan.vlc",
        PLAYER_ID_MX to "com.mxtech.videoplayer.ad",
        PLAYER_ID_INTERNAL to "internal"
    )
    val PLAYER_ID_TO_NAME = mapOf(
        PLAYER_ID_VLC to "VLC",
        PLAYER_ID_MX to "MX Player",
        PLAYER_ID_INTERNAL to "Internal",
    )
    val PLAYER_ID_TO_DESCRIPTION = mapOf(
        PLAYER_ID_VLC to "デフォルト",
        PLAYER_ID_MX to "",
        PLAYER_ID_INTERNAL to "非推奨",
    )

    const val PLAYER_ID_KEY = "PLAYER_ID"
    const val PLAYER_ID_DEFAULT = PLAYER_ID_VLC

    fun loadDefaultPreferencesIfNotExist(context: Context){
        val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)
        val editor: SharedPreferences.Editor = sharedPreferences.edit()

        if (!sharedPreferences.contains(IP_ADDR_KEY)) {
            editor.putString(IP_ADDR_KEY,IP_ADDR_DEFAULT)
            mChanged = true
        }
        if (!sharedPreferences.contains(PORT_NUM_KEY)) {
            editor.putInt(PORT_NUM_KEY,PORT_NUM_DEFAULT)
            mChanged = true

        }else if(sharedPreferences.getInt(PORT_NUM_KEY,-1) !in 0 .. 65535){
            editor.putInt(PORT_NUM_KEY,PORT_NUM_DEFAULT)
            mChanged = true

        }
        if (!sharedPreferences.contains(FETCH_LIMIT_KEY)) {
            editor.putInt(FETCH_LIMIT_KEY,FETCH_LIMIT_DEFAULT)
            mChanged = true
        }
        if (!sharedPreferences.contains(PLAYER_ID_KEY)) {
            editor.putInt(PLAYER_ID_KEY,PLAYER_ID_DEFAULT.toInt())
            mChanged = true
        }
        editor.apply()

    }

    fun getIP_ADDRESS(context: Context):String{
        val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)
        val ip = sharedPreferences.getString(IP_ADDR_KEY,IP_ADDR_DEFAULT)
        if(ip != null ){
            return ip
        }else{
            return ""
        }
    }

    fun setIP_ADDRESS(context: Context,ip:String){
        val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)
        val editor: SharedPreferences.Editor = sharedPreferences.edit()
        editor.putString(IP_ADDR_KEY, ip)
        editor.apply()
        mChanged = true
    }

    fun getPORT_NUM(context: Context):Int {
        val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)
        return sharedPreferences.getInt(PORT_NUM_KEY, PORT_NUM_DEFAULT)
    }

    fun setIP_PORT_NUM(context: Context,port:Int) {
        val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)
        val editor: SharedPreferences.Editor = sharedPreferences.edit()
        editor.putInt(PORT_NUM_KEY, port)
        editor.apply()
        mChanged = true
    }

    fun getFETCH_LIMIT(context: Context):Int {
        val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)
        return sharedPreferences.getInt(FETCH_LIMIT_KEY, FETCH_LIMIT_DEFAULT)
    }

    fun setFETCH_LIMIT(context: Context,limit:Int) {
        val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)
        val editor: SharedPreferences.Editor = sharedPreferences.edit()
        editor.putInt(FETCH_LIMIT_KEY, limit)
        editor.apply()
        mChanged = true
    }


    fun getPLAYER_ID(context: Context):Long {
        val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)
        return sharedPreferences.getInt(PLAYER_ID_KEY, PLAYER_ID_DEFAULT.toInt()).toLong()
    }

    fun setPLAYER_ID(context: Context,id:Int) {
        val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)
        val editor: SharedPreferences.Editor = sharedPreferences.edit()
        editor.putInt(PLAYER_ID_KEY , id)
        editor.apply()
        mChanged = true
    }

    fun hasChanged():Boolean{
        if(mChanged){
            mChanged = false
            return true
        }else{
            return mChanged
        }
    }


}