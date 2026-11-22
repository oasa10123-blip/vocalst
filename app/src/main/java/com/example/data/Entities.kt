package com.example.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "ws_command_rules")
data class WsCommandRuleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val triggerText: String,
    val matchMode: String = "EXACT_IGNORE_CASE",
    val category: String = "KEY",
    val targetKey: String? = null,
    val targetScale: String? = null,
    val targetHarmonyType: String? = null,
    val targetEchoMode: String? = null,
    val targetEnhance: Boolean? = null,
    val targetVariation: Boolean? = null,
    val targetBypassAction: String = "NONE",
    val targetPresetSlot: Int? = null,
    val enabled: Boolean = true,
    val sortOrder: Int = 0
)

@Entity(tableName = "vocal_presets")
data class VocalPresetEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val slotNumber: Int,
    val name: String,
    val keyName: String,
    val scaleName: String,
    val harmonyTypeName: String,
    val balance: Float,
    val echoModeName: String,
    val echoMix: Float,
    val micGainDb: Float,
    val enhanceEnabled: Boolean,
    val variationEnabled: Boolean,
    val autoHarmonistEnabled: Boolean
)
