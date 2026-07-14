package com.lidesheng.hyperlyric.ui.page.hooksettings.media.island

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.lidesheng.hyperlyric.R
import com.lidesheng.hyperlyric.common.RootConstants
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference

fun LazyListScope.islandExpandedMediaCardSection(
    cardTheme: Int,
    onCardThemeChange: (Int) -> Unit,
    ambientFlowMode: Int,
    onAmbientFlowModeChange: (Int) -> Unit
) {
    item(key = "island_expanded_media_card") {
        SmallTitle(text = stringResource(R.string.title_island_expanded_media_card))
        Card(
            modifier = Modifier
                .padding(horizontal = 12.dp)
                .padding(bottom = 12.dp)
                .fillMaxWidth()
        ) {
            val themeValues = listOf(
                RootConstants.MEDIA_CARD_THEME_FOLLOW_SYSTEM,
                RootConstants.MEDIA_CARD_THEME_ALWAYS_LIGHT,
                RootConstants.MEDIA_CARD_THEME_ALWAYS_DARK
            )
            OverlayDropdownPreference(
                title = stringResource(R.string.title_media_card_background_theme),
                items = listOf(
                    stringResource(R.string.option_media_card_theme_follow_system),
                    stringResource(R.string.option_media_card_theme_always_light),
                    stringResource(R.string.option_media_card_theme_always_dark_default)
                ),
                selectedIndex = themeValues.indexOf(cardTheme).coerceAtLeast(0),
                onSelectedIndexChange = { index ->
                    onCardThemeChange(themeValues[index])
                }
            )
            val modeValues = listOf(
                RootConstants.ISLAND_EXPANDED_MEDIA_AMBIENT_FLOW_MODE_DEFAULT,
                RootConstants.ISLAND_EXPANDED_MEDIA_AMBIENT_FLOW_MODE_COVER_COLOR,
                RootConstants.ISLAND_EXPANDED_MEDIA_AMBIENT_FLOW_MODE_DISABLED
            )
            OverlayDropdownPreference(
                title = stringResource(R.string.title_island_media_ambient_flow_mode),
                items = listOf(
                    stringResource(R.string.option_island_media_ambient_flow_default),
                    stringResource(R.string.option_island_media_ambient_flow_cover_color),
                    stringResource(R.string.option_island_media_ambient_flow_disabled)
                ),
                selectedIndex = modeValues.indexOf(ambientFlowMode).coerceAtLeast(0),
                onSelectedIndexChange = { index ->
                    onAmbientFlowModeChange(modeValues[index])
                }
            )
        }
    }
}
