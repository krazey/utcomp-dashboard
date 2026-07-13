package de.krazey.utcomp.dashboard.dashboard.render

import kotlin.math.min

internal data class DesignCanvasFit(
    val scale: Float,
    val offsetX: Float,
    val offsetY: Float,
)

internal fun fitDesignCanvas(
    containerWidth: Int,
    containerHeight: Int,
    designWidth: Int,
    designHeight: Int,
): DesignCanvasFit {
    if (
        containerWidth <= 0 ||
        containerHeight <= 0 ||
        designWidth <= 0 ||
        designHeight <= 0
    ) {
        return DesignCanvasFit(scale = 1f, offsetX = 0f, offsetY = 0f)
    }

    val scale = min(
        containerWidth.toFloat() / designWidth.toFloat(),
        containerHeight.toFloat() / designHeight.toFloat(),
    )
    val scaledWidth = designWidth * scale
    val scaledHeight = designHeight * scale
    return DesignCanvasFit(
        scale = scale,
        offsetX = (containerWidth - scaledWidth) * 0.5f,
        offsetY = (containerHeight - scaledHeight) * 0.5f,
    )
}
