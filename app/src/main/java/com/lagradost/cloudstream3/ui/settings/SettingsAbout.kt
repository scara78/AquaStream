package com.lagradost.cloudstream3.ui.settings

import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceManager
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.ui.settings.SettingsFragment.Companion.getPref
import com.lagradost.cloudstream3.ui.settings.SettingsFragment.Companion.setUpToolbar
import com.lagradost.cloudstream3.utils.UIHelper.hideKeyboard



class SettingsAbout : PreferenceFragmentCompat() {


    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setUpToolbar(R.string.category_about)
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        hideKeyboard()
        setPreferencesFromResource(R.xml.settings_about, rootKey)
        val settingsManager = PreferenceManager.getDefaultSharedPreferences(requireContext())

        try {
            SettingsFragment.beneneCount =
                settingsManager.getInt(getString(R.string.benene_count), 0)
            getPref(R.string.benene_count)?.let { pref ->
                pref.summary =
                    if (SettingsFragment.beneneCount <= 0) getString(R.string.benene_count_text_none) else getString(
                        R.string.benene_count_text
                    ).format(
                        SettingsFragment.beneneCount
                    )

                pref.setOnPreferenceClickListener {
                    try {
                        SettingsFragment.beneneCount++
                        settingsManager.edit().putInt(
                            getString(R.string.benene_count),
                            SettingsFragment.beneneCount
                        )
                            .apply()
                        it.summary =
                            getString(R.string.benene_count_text).format(SettingsFragment.beneneCount)
                    } catch (e: Exception) {
                        logError(e)
                    }

                    return@setOnPreferenceClickListener true
                }
            }

        } catch (e: Exception) {
            e.printStackTrace()
        }
        getPref(R.string.legal_notice_key)?.setOnPreferenceClickListener {
            val builder: AlertDialog.Builder =
                AlertDialog.Builder(it.context, R.style.AlertDialogCustom)
            builder.setTitle(R.string.legal_notice)
            builder.setMessage(R.string.legal_notice_text)
            builder.show()
            return@setOnPreferenceClickListener true
        }
    }
}
