package com.lagradost.cloudstream3.ui.settings

import android.os.Bundle
import android.view.View
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceManager
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.APIHolder.allProviders
import com.lagradost.cloudstream3.APIHolder.getApiDubstatusSettings
import com.lagradost.cloudstream3.APIHolder.getApiFromName
import com.lagradost.cloudstream3.metaproviders.CrossTmdbProvider
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.network.initClient
import com.lagradost.cloudstream3.ui.APIRepository
import com.lagradost.cloudstream3.ui.search.SEARCH_PREF_PROVIDERS
import com.lagradost.cloudstream3.ui.settings.SettingsFragment.Companion.getPref
import com.lagradost.cloudstream3.ui.settings.SettingsFragment.Companion.setUpToolbar
import com.lagradost.cloudstream3.utils.DataStore.getKey
import com.lagradost.cloudstream3.utils.DataStore.setKey
import com.lagradost.cloudstream3.utils.HOMEPAGE_API
import com.lagradost.cloudstream3.utils.SingleSelectionHelper.showBottomDialog
import com.lagradost.cloudstream3.utils.SingleSelectionHelper.showMultiDialog
import com.lagradost.cloudstream3.utils.UIHelper.hideKeyboard
import com.lagradost.cloudstream3.utils.getExtractorApiFromName


class SettingsProviders : PreferenceFragmentCompat() {


    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setUpToolbar(R.string.category_providers)
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        hideKeyboard()
        setPreferencesFromResource(R.xml.settings_providers, rootKey)
        val settingsManager = PreferenceManager.getDefaultSharedPreferences(requireContext())

        getPref(R.string.display_sub_key)?.setOnPreferenceClickListener {
            activity?.getApiDubstatusSettings()?.let { current ->
                val dublist = DubStatus.values()
                val names = dublist.map { it.name }

                val currentList = ArrayList<Int>()
                for (i in current) {
                    currentList.add(dublist.indexOf(i))
                }

                activity?.showMultiDialog(
                    names,
                    currentList,
                    getString(R.string.display_subbed_dubbed_settings),
                    {}) { selectedList ->
                    APIRepository.dubStatusActive = selectedList.map { dublist[it] }.toHashSet()

                    settingsManager.edit().putStringSet(
                        this.getString(R.string.display_sub_key),
                        selectedList.map { names[it] }.toMutableSet()
                    ).apply()
                }
            }

            return@setOnPreferenceClickListener true
        }

        getPref(R.string.prefer_media_type_key)?.setOnPreferenceClickListener {
            val prefNames = resources.getStringArray(R.array.media_type_pref)
            val prefValues = resources.getIntArray(R.array.media_type_pref_values)

            val currentPrefMedia =
                settingsManager.getInt(getString(R.string.prefer_media_type_key), 0)

            activity?.showBottomDialog(
                prefNames.toList(),
                prefValues.indexOf(currentPrefMedia),
                getString(R.string.preferred_media_settings),
                true,
                {}) {
                settingsManager.edit()
                    .putInt(getString(R.string.prefer_media_type_key), prefValues[it])
                    .apply()

                AcraApplication.removeKey(HOMEPAGE_API)
                (context ?: AcraApplication.context)?.let { ctx -> app.initClient(ctx) }
            }
            return@setOnPreferenceClickListener true
        }
    }
}
