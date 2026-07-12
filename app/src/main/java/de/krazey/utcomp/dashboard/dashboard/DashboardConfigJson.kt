package de.krazey.utcomp.dashboard.dashboard

import org.json.JSONArray
import org.json.JSONObject

internal object DashboardConfigJson {
    fun encode(pages: List<DashboardPageConfig>): String =
        JSONArray().apply {
            pages.forEach { pageConfig ->
                put(pageConfigToJson(pageConfig))
            }
        }.toString()

    fun decode(
        raw: String?,
        defaults: List<DashboardPageConfig> = DefaultDashboardPages.all,
    ): List<DashboardPageConfig>? {
        if (raw.isNullOrBlank()) return null

        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).map { index ->
                val fallback = defaults.getOrElse(index) { defaults.last() }
                pageConfigFromJson(array.optJSONObject(index), fallback)
            }
        }.getOrNull()
    }

    private fun pageConfigToJson(pageConfig: DashboardPageConfig): JSONObject =
        JSONObject().apply {
            put("id", pageConfig.id)
            put("title", pageConfig.title)
            put("rows", pageConfig.rows)
            put("columns", pageConfig.columns)
            put("minMaxAlwaysVisible", pageConfig.minMaxAlwaysVisible)
            put("showSourceLine", pageConfig.showSourceLine)
            put(
                "boxes",
                JSONArray().apply {
                    pageConfig.boxes.forEach { box ->
                        put(boxConfigToJson(box))
                    }
                },
            )
        }

    private fun boxConfigToJson(box: DashboardBoxConfig): JSONObject =
        JSONObject().apply {
            put("sensor", box.sensor.name)
            put("row", box.row)
            put("column", box.column)
            put("rowSpan", box.rowSpan)
            put("columnSpan", box.columnSpan)
            putFloat(this, "valueScale", box.valueScale)
            putFloat(this, "iconScale", box.iconScale)
            putFloat(this, "iconValueGapScale", box.iconValueGapScale)
            putFloat(this, "minMaxScale", box.minMaxScale)
            putFloat(this, "scaleMin", box.scaleMin)
            putFloat(this, "scaleMax", box.scaleMax)
            putFloat(this, "smoothingAlpha", box.smoothingAlpha)
            putFloat(this, "warningLow", box.warningLow)
            putFloat(this, "criticalLow", box.criticalLow)
            putFloat(this, "warningHigh", box.warningHigh)
            putFloat(this, "criticalHigh", box.criticalHigh)
            putFloat(
                this,
                "oilPressureBoostArmBar",
                box.oilPressureBoostArmBar,
            )
            putFloat(this, "oilPressureWarningBar", box.warningLow)
            putFloat(this, "oilPressureCriticalBar", box.criticalLow)
            put("decimalPlaces", box.decimalPlaces)
            put("backgroundColor", box.backgroundColor)
            put("foregroundColor", box.foregroundColor)
            put("unitColor", box.unitColor)
            put("alarmColor", box.alarmColor)
            put("minColor", box.minColor)
            put("maxColor", box.maxColor)
            put("borderColor", box.borderColor)
            put("valueColor", box.valueColor)
            put("warningColor", box.warningColor)
            put("criticalColor", box.criticalColor)
            put("warningValueColor", box.warningValueColor)
            put("criticalValueColor", box.criticalValueColor)
            put("splitValueDigits", box.splitValueDigits)
            put("alarmColorsBackground", box.alarmColorsBackground)
            put("alarmColorsValue", box.alarmColorsValue)
            put("oilPressureBoostAlarm", box.oilPressureBoostAlarm)
            put("showIcon", box.showIcon)
            put("showUnit", box.showUnit)
            put("showMinMax", box.showMinMax)
        }

    private fun pageConfigFromJson(
        json: JSONObject?,
        fallback: DashboardPageConfig,
    ): DashboardPageConfig {
        if (json == null) return fallback

        val boxArray = json.optJSONArray("boxes")
        val boxes = if (boxArray != null) {
            (0 until boxArray.length()).map { index ->
                val fallbackBox = fallback.boxes.getOrElse(index) {
                    fallback.boxes.lastOrNull()
                        ?: DashboardBoxConfig(
                            DashboardSensor.BOOST,
                            row = 0,
                            column = 0,
                        )
                }
                boxConfigFromJson(boxArray.optJSONObject(index), fallbackBox)
            }
        } else {
            fallback.boxes
        }

        return DashboardPageConfig(
            id = json.optString("id", fallback.id),
            title = json.optString("title", fallback.title),
            rows = json.optInt("rows", fallback.rows),
            columns = json.optInt("columns", fallback.columns),
            boxes = boxes,
            minMaxAlwaysVisible = json.optBoolean(
                "minMaxAlwaysVisible",
                fallback.minMaxAlwaysVisible,
            ),
            showSourceLine = json.optBoolean(
                "showSourceLine",
                fallback.showSourceLine,
            ),
        ).normalized()
    }

    private fun boxConfigFromJson(
        json: JSONObject?,
        fallback: DashboardBoxConfig,
    ): DashboardBoxConfig {
        if (json == null) return fallback

        return fallback.copy(
            sensor = sensorFromName(
                json.optString("sensor", fallback.sensor.name),
                fallback.sensor,
            ),
            row = json.optInt("row", fallback.row),
            column = json.optInt("column", fallback.column),
            rowSpan = json.optInt("rowSpan", fallback.rowSpan),
            columnSpan = json.optInt("columnSpan", fallback.columnSpan),
            valueScale = optFloat(json, "valueScale", fallback.valueScale),
            iconScale = optFloat(json, "iconScale", fallback.iconScale),
            iconValueGapScale = optFloat(
                json,
                "iconValueGapScale",
                fallback.iconValueGapScale,
            ),
            minMaxScale = optFloat(
                json,
                "minMaxScale",
                fallback.minMaxScale,
            ),
            scaleMin = optFloat(json, "scaleMin", fallback.scaleMin),
            scaleMax = optFloat(json, "scaleMax", fallback.scaleMax),
            smoothingAlpha = optFloat(
                json,
                "smoothingAlpha",
                fallback.smoothingAlpha,
            ),
            warningLow = optFloat(json, "warningLow", fallback.warningLow),
            criticalLow = optFloat(json, "criticalLow", fallback.criticalLow),
            warningHigh = optFloat(json, "warningHigh", fallback.warningHigh),
            criticalHigh = optFloat(
                json,
                "criticalHigh",
                fallback.criticalHigh,
            ),
            oilPressureBoostArmBar = optFloat(
                json,
                "oilPressureBoostArmBar",
                fallback.oilPressureBoostArmBar,
            ),
            oilPressureWarningBar = optFloat(
                json,
                "oilPressureWarningBar",
                fallback.oilPressureWarningBar,
            ),
            oilPressureCriticalBar = optFloat(
                json,
                "oilPressureCriticalBar",
                fallback.oilPressureCriticalBar,
            ),
            decimalPlaces = json.optInt(
                "decimalPlaces",
                fallback.decimalPlaces,
            ),
            backgroundColor = json.optInt(
                "backgroundColor",
                fallback.backgroundColor,
            ),
            foregroundColor = json.optInt(
                "foregroundColor",
                fallback.foregroundColor,
            ),
            unitColor = json.optInt("unitColor", fallback.unitColor),
            alarmColor = json.optInt("alarmColor", fallback.alarmColor),
            minColor = json.optInt("minColor", fallback.minColor),
            maxColor = json.optInt("maxColor", fallback.maxColor),
            borderColor = json.optInt("borderColor", fallback.borderColor),
            valueColor = json.optInt("valueColor", fallback.valueColor),
            warningColor = json.optInt(
                "warningColor",
                fallback.warningColor,
            ),
            criticalColor = json.optInt(
                "criticalColor",
                fallback.criticalColor,
            ),
            warningValueColor = json.optInt(
                "warningValueColor",
                fallback.warningValueColor,
            ),
            criticalValueColor = json.optInt(
                "criticalValueColor",
                fallback.criticalValueColor,
            ),
            splitValueDigits = json.optBoolean(
                "splitValueDigits",
                fallback.splitValueDigits,
            ),
            alarmColorsBackground = json.optBoolean(
                "alarmColorsBackground",
                fallback.alarmColorsBackground,
            ),
            alarmColorsValue = json.optBoolean(
                "alarmColorsValue",
                fallback.alarmColorsValue,
            ),
            oilPressureBoostAlarm = json.optBoolean(
                "oilPressureBoostAlarm",
                fallback.oilPressureBoostAlarm,
            ),
            showIcon = json.optBoolean("showIcon", fallback.showIcon),
            showUnit = json.optBoolean("showUnit", fallback.showUnit),
            showMinMax = json.optBoolean("showMinMax", fallback.showMinMax),
        )
    }

    private fun sensorFromName(
        name: String?,
        fallback: DashboardSensor,
    ): DashboardSensor =
        runCatching {
            DashboardSensor.valueOf(name ?: fallback.name)
        }.getOrDefault(fallback)

    private fun putFloat(json: JSONObject, key: String, value: Float) {
        if (value.isNaN() || value.isInfinite()) {
            json.put(key, JSONObject.NULL)
        } else {
            json.put(key, value.toDouble())
        }
    }

    private fun optFloat(
        json: JSONObject,
        key: String,
        fallback: Float,
    ): Float =
        if (!json.has(key) || json.isNull(key)) {
            fallback
        } else {
            json.optDouble(key, fallback.toDouble()).toFloat()
        }
}
