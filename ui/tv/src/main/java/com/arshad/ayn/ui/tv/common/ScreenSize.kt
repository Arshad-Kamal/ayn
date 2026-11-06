package com.arshad.ayn.ui.tv.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Dp

/** The window's width in the interface's dp: the configuration's would ignore the interface size. */
@Composable
@ReadOnlyComposable
internal fun screenWidth(): Dp = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.width.toDp() }

/** The window's height in the interface's dp (see [screenWidth]). */
@Composable
@ReadOnlyComposable
internal fun screenHeight(): Dp = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.height.toDp() }
