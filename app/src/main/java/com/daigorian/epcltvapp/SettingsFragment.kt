package com.daigorian.epcltvapp

import android.os.Bundle
import android.text.InputType
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.leanback.app.GuidedStepSupportFragment
import androidx.leanback.widget.GuidanceStylist
import androidx.leanback.widget.GuidedAction

class SettingsFragment : GuidedStepSupportFragment(){
    override fun onCreateGuidance(savedInstanceState: Bundle?): GuidanceStylist.Guidance {
        return GuidanceStylist.Guidance(
            getString(R.string.settings),
            "",
            "",
            ContextCompat.getDrawable(requireContext(), R.drawable.movie)
        )
    }
    override fun onCreateActions(actions: MutableList<GuidedAction>, savedInstanceState: Bundle?) {
        super.onCreateActions(actions, savedInstanceState)

        // EPGStationのIPアドレスの追加
        actions.add(
            GuidedAction.Builder(requireContext())
                .id(IP_ADDR)
                .title(Settings.getIP_ADDRESS(requireContext()))
                .description(getString(R.string.ip_addr_of_epgstation))
                .editable(true)
                .inputType(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS)
                .editInputType(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS)
                .build()
        )
        // Add "Continue" user action for this step
        actions.add(
            GuidedAction.Builder(requireContext())
                .id(PORT_NUM)
                .title(Settings.getPORT_NUM(requireContext()).toString())
                .description(getString(R.string.port_number_of_epgstation))
                .editable(true)
                .inputType(InputType.TYPE_CLASS_NUMBER)
                .editInputType(InputType.TYPE_CLASS_NUMBER)
                .build()
        )
        //取得数のバリエーションをサブアクションとして列挙
        val subActions_forLimit = mutableListOf<GuidedAction>()

        for  ((id, num) in Settings.FETCH_LIMIT_ID_TO_NUM) {
            subActions_forLimit.add(GuidedAction.Builder(requireContext())
                .id(id)
                .title(num.toString())
                .description(Settings.FETCH_LIMIT_ID_TO_DESCRIPTION[id])
                .build())
        }
        // EPGStation APIで一度に取得する録画の数 のアクションを追加

        actions.add(
            GuidedAction.Builder(requireContext())
                .id(FETCH_LIMIT)
                .title(Settings.getFETCH_LIMIT(requireContext()).toString())
                .description(getString(R.string.number_of_items_to_get_at_one_time))
                .subActions(subActions_forLimit)
                .build()
        )

        //プレーヤーの選択肢をサブアクションとして列挙
        val subActions_for_Player = mutableListOf<GuidedAction>()
        for  ((id, name) in Settings.PLAYER_ID_TO_NAME){
            subActions_for_Player.add(GuidedAction.Builder(requireContext())
                .id(id)
                .title(name)
                .description(Settings.PLAYER_ID_TO_DESCRIPTION[id])
                .build())
        }

        // プレーヤー選択肢をアクションとして追加

        actions.add(
            GuidedAction.Builder(requireContext())
                .id(PLAYER_ID)
                .title(Settings.PLAYER_ID_TO_NAME[Settings.getPLAYER_ID(requireContext())])
                .description(getString(R.string.movie_player))
                .subActions(subActions_for_Player)
                .build()
        )

    }

    override fun onGuidedActionEditedAndProceed(action: GuidedAction?): Long {
        if (action != null) {
            when {
                action.id == IP_ADDR -> {
                    Settings.setIP_ADDRESS(requireContext(),action.title.toString())
                    EpgStation.reloadAPI(requireContext())
                }
                action.id == PORT_NUM -> {
                    if (action.title.toString().toInt() !in 0 .. 65535){
                        Toast.makeText(requireContext(), getString(R.string.valid_port_number_are_from_0_to_65535), Toast.LENGTH_SHORT).show()
                        return  GuidedAction.ACTION_ID_CURRENT
                    }
                    Settings.setIP_PORT_NUM(requireContext(),action.title.toString().toInt())
                    EpgStation.reloadAPI(requireContext())
                }

            }
        }
        return super.onGuidedActionEditedAndProceed(action)
    }

    override fun onSubGuidedActionClicked(action: GuidedAction): Boolean {
        // Check for which action was clicked, and handle as needed

        if (Settings.FETCH_LIMIT_ID_TO_NUM.containsKey(action.id)){
            Settings.setFETCH_LIMIT(
                requireContext(),
                Settings.FETCH_LIMIT_ID_TO_NUM[action.id]!!
            )

            notifyActionChanged(actions.indexOf(findActionById(FETCH_LIMIT)))
            EpgStation.reloadAPI(requireContext())
        }

        if (Settings.PLAYER_ID_TO_PACKAGE.containsKey(action.id)){
            Settings.setPLAYER_ID(requireContext(),action.id.toInt())
            findActionById(PLAYER_ID).title = Settings.PLAYER_ID_TO_NAME[action.id]
            notifyActionChanged(actions.indexOf(findActionById(PLAYER_ID)))
        }


        // Return true to collapse the subactions drop-down list, or
        // false to keep the drop-down list expanded.
        return true
    }

    companion object {
        private const val IP_ADDR = 1L
        private const val PORT_NUM = 2L
        private const val FETCH_LIMIT = 3L
        private const val PLAYER_ID = 4L

    }

}