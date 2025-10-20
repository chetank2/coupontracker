package com.example.coupontracker.extraction.region

import android.content.Context
import org.json.JSONObject

/**
 * Configuration backing the heuristic regionizer.
 */
data class CouponRegionizerConfig(
    val globalCrop: GlobalCrop,
    val poster: PosterConfig,
    val reward: RewardConfig,
    val grid: GridConfig,
    val mapOverlay: MapOverlayConfig
) {
    data class GlobalCrop(val topPct: Float, val bottomPct: Float)
    data class PosterConfig(val focusTopPct: Float, val focusHeightPct: Float)
    data class RewardConfig(val dropPhrases: List<String>)
    data class GridConfig(val minCardWidthPx: Int, val minGapPx: Int, val maxCols: Int)
    data class MapOverlayConfig(val brightnessThresh: Float, val overlayMinAreaPct: Float)

    companion object {
        private const val CONFIG_PATH = "coupon_regionizer.json"

        fun load(context: Context): CouponRegionizerConfig {
            return runCatching {
                context.assets.open(CONFIG_PATH).bufferedReader().use { reader ->
                    parse(JSONObject(reader.readText()))
                }
            }.getOrElse {
                default()
            }
        }

        private fun parse(json: JSONObject): CouponRegionizerConfig {
            val global = json.optJSONObject("globalCrop")
            val poster = json.optJSONObject("poster")
            val reward = json.optJSONObject("reward")
            val grid = json.optJSONObject("grid")
            val mapOverlay = json.optJSONObject("mapOverlay")

            return CouponRegionizerConfig(
                globalCrop = GlobalCrop(
                    topPct = global?.optDouble("topPct", 0.12)?.toFloat() ?: 0.12f,
                    bottomPct = global?.optDouble("bottomPct", 0.12)?.toFloat() ?: 0.12f
                ),
                poster = PosterConfig(
                    focusTopPct = poster?.optDouble("focusTopPct", 0.32)?.toFloat() ?: 0.32f,
                    focusHeightPct = poster?.optDouble("focusHeightPct", 0.56)?.toFloat() ?: 0.56f
                ),
                reward = RewardConfig(
                    dropPhrases = reward?.optJSONArray("dropPhrases")?.let { array ->
                        buildList(array.length()) {
                            for (i in 0 until array.length()) {
                                val value = array.optString(i)
                                if (!value.isNullOrBlank()) {
                                    add(value.lowercase())
                                }
                            }
                        }
                    } ?: emptyList()
                ),
                grid = GridConfig(
                    minCardWidthPx = grid?.optInt("minCardWidthPx", 260) ?: 260,
                    minGapPx = grid?.optInt("minGapPx", 12) ?: 12,
                    maxCols = grid?.optInt("maxCols", 3) ?: 3
                ),
                mapOverlay = MapOverlayConfig(
                    brightnessThresh = mapOverlay?.optDouble("brightnessThresh", 0.85)?.toFloat() ?: 0.85f,
                    overlayMinAreaPct = mapOverlay?.optDouble("overlayMinAreaPct", 0.2)?.toFloat() ?: 0.2f
                )
            )
        }

        private fun default(): CouponRegionizerConfig {
            return CouponRegionizerConfig(
                globalCrop = GlobalCrop(topPct = 0.12f, bottomPct = 0.12f),
                poster = PosterConfig(focusTopPct = 0.32f, focusHeightPct = 0.56f),
                reward = RewardConfig(dropPhrases = emptyList()),
                grid = GridConfig(minCardWidthPx = 260, minGapPx = 12, maxCols = 3),
                mapOverlay = MapOverlayConfig(brightnessThresh = 0.85f, overlayMinAreaPct = 0.2f)
            )
        }
    }
}
