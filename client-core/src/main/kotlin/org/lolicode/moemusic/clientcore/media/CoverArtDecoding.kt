package org.lolicode.moemusic.clientcore.media

fun computeCoverDecodeDownscaleFactor(
    sourceWidth: Int,
    sourceHeight: Int,
    maxTextureSize: Int,
    maxDecodeDownscaleFactor: Int,
): Int {
    if (sourceWidth <= 0 || sourceHeight <= 0) return 1
    val longestEdge = maxOf(sourceWidth, sourceHeight)
    val decodeTargetDimension = maxTextureSize.coerceAtLeast(1) * 2
    if (longestEdge <= decodeTargetDimension) return 1
    val idealFactor = ((longestEdge + decodeTargetDimension - 1) / decodeTargetDimension).coerceAtLeast(1)
    return idealFactor.coerceAtMost(maxDecodeDownscaleFactor)
}
