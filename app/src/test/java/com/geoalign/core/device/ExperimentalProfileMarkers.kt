package com.geoalign.core.device

/**
 * Strings that appear in the compiled bytecode of `ExperimentalDeviceProfiles` and nowhere else in
 * the tree — profile ids and the one device model string that is not a substring of anything else.
 *
 * Shared by `testPlay` (asserts none of these are in the variant) and `testCommunity` (asserts all
 * of them are). Keeping one list means the two tests cannot drift into checking different things,
 * and the community half is what proves the play half is not passing because the scanner is broken
 * or the marker list is stale.
 */
internal val EXPERIMENTAL_PROFILE_MARKERS = listOf(
    "pixel_8",
    "galaxy_s24",
    "iphone_15_pro",
    "iphone_se",
    "desktop_mac_chrome",
    "desktop_win_chrome",
    "SM-S921B",
)
