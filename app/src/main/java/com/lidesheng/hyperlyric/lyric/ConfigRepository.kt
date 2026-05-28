package com.lidesheng.hyperlyric.lyric

import android.content.Context
import androidx.core.content.edit
import com.lidesheng.hyperlyric.common.PrefsBridge
import com.lidesheng.hyperlyric.ui.utils.Constants as UIConstants
import com.lidesheng.hyperlyric.service.Constants as ServiceConstants
import com.lidesheng.hyperlyric.root.utils.Constants as RootConstants
import com.lidesheng.hyperlyric.utils.LyricProviderManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

object ConfigRepository {

    private val _whitelistState = MutableStateFlow<Set<String>>(emptySet())
    val whitelistState = _whitelistState.asStateFlow()

    private val _hookWhitelistState = MutableStateFlow<Set<String>>(emptySet())
    val hookWhitelistState = _hookWhitelistState.asStateFlow()

    private val _hookAddedState = MutableStateFlow<Set<String>>(emptySet())
    val hookAddedState = _hookAddedState.asStateFlow()

    fun initWhitelist(context: Context) {
        val prefs = context.getSharedPreferences(UIConstants.PREF_NAME, Context.MODE_PRIVATE)
        val savedSet = prefs.getStringSet(ServiceConstants.KEY_NOTIFICATION_WHITELIST, emptySet())?.toSet() ?: emptySet()
        _whitelistState.value = savedSet

        val hookSavedSet = prefs.getStringSet(RootConstants.KEY_HOOK_WHITELIST, emptySet())?.toSet() ?: emptySet()
        _hookWhitelistState.value = hookSavedSet

        val hookAddedSet = prefs.getStringSet(RootConstants.KEY_HOOK_ADDED_LIST, emptySet())?.toSet() ?: emptySet()
        _hookAddedState.value = hookAddedSet
    }

    fun addPackageToWhitelist(context: Context, packageName: String): Boolean {
        val currentSet = _whitelistState.value.toMutableSet()
        if (!currentSet.add(packageName)) return false
        saveWhitelist(context, currentSet)
        return true
    }

    fun removePackageFromWhitelist(context: Context, packageName: String) {
        val currentSet = _whitelistState.value.toMutableSet()
        if (currentSet.remove(packageName)) {
            saveWhitelist(context, currentSet)
        }
    }

    private fun saveWhitelist(context: Context, set: Set<String>) {
        _whitelistState.value = set
        context.getSharedPreferences(UIConstants.PREF_NAME, Context.MODE_PRIVATE).edit {
            putStringSet(ServiceConstants.KEY_NOTIFICATION_WHITELIST, set)
        }
        PrefsBridge.putStringSet(ServiceConstants.KEY_NOTIFICATION_WHITELIST, set)
    }

    fun addPackageToHookList(context: Context, packageName: String): Boolean {
        val addedSet = _hookAddedState.value.toMutableSet()
        if (!addedSet.add(packageName)) return false

        val whitelistSet = _hookWhitelistState.value.toMutableSet()
        whitelistSet.add(packageName)

        saveHookLists(context, whitelistSet, addedSet)
        return true
    }

    fun toggleHookStatus(context: Context, packageName: String, enabled: Boolean) {
        val currentWhitelist = _hookWhitelistState.value.toMutableSet()
        val currentAdded = _hookAddedState.value.toMutableSet()

        if (enabled) {
            currentWhitelist.add(packageName)
            currentAdded.add(packageName)
        } else {
            currentWhitelist.remove(packageName)
        }
        saveHookLists(context, currentWhitelist, currentAdded)
    }

    fun removePackageFromHookPage(context: Context, packageName: String) {
        val addedSet = _hookAddedState.value.toMutableSet()
        val whitelistSet = _hookWhitelistState.value.toMutableSet()

        addedSet.remove(packageName)
        whitelistSet.remove(packageName)

        saveHookLists(context, whitelistSet, addedSet)
    }

    fun cleanupOrphanedPackages(context: Context, installedProviderPkgs: Set<String>) {
        val allPossibleTargets = LyricProviderManager.providerToTargetMap.values.flatten().toSet()
        val currentlyCoveredTargets = installedProviderPkgs.flatMap {
            LyricProviderManager.providerToTargetMap[it] ?: emptyList()
        }.toSet()

        val orphanedTargets = allPossibleTargets - currentlyCoveredTargets
        val newAddedSet = _hookAddedState.value.toMutableSet()
        val newWhitelistSet = _hookWhitelistState.value.toMutableSet()

        var changed = false
        orphanedTargets.forEach { orphaned: String ->
            if (newAddedSet.remove(orphaned)) changed = true
            if (newWhitelistSet.remove(orphaned)) changed = true
        }

        if (changed) {
            saveHookLists(context, newWhitelistSet, newAddedSet)
        }
    }

    private fun saveHookLists(context: Context, whitelist: Set<String>, added: Set<String>) {
        _hookWhitelistState.value = whitelist
        _hookAddedState.value = added

        context.getSharedPreferences(UIConstants.PREF_NAME, Context.MODE_PRIVATE).edit {
            putStringSet(RootConstants.KEY_HOOK_WHITELIST, whitelist)
            putStringSet(RootConstants.KEY_HOOK_ADDED_LIST, added)
        }

        PrefsBridge.putStringSet(RootConstants.KEY_HOOK_WHITELIST, whitelist)
        PrefsBridge.putStringSet(RootConstants.KEY_HOOK_ADDED_LIST, added)
    }
}
