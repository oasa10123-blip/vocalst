package com.example.data

import com.example.model.BypassAction
import com.example.model.EchoMode
import com.example.model.HarmonyType
import com.example.model.MatchMode
import com.example.model.MusicalKey
import com.example.model.MusicalScale
import com.example.model.RuleCategory

object DefaultSeeds {
    fun defaultWsRules(): List<WsCommandRuleEntity> {
        val rules = mutableListOf<WsCommandRuleEntity>()
        var order = 0

        // All 12 Major Keys
        MusicalKey.entries.forEach { key ->
            rules.add(
                WsCommandRuleEntity(
                    name = "Key ${key.displayName} Major",
                    triggerText = "key:${key.displayName}",
                    matchMode = MatchMode.EXACT_IGNORE_CASE.name,
                    category = RuleCategory.KEY.name,
                    targetKey = key.name,
                    targetScale = MusicalScale.MAJOR.name,
                    sortOrder = order++
                )
            )
        }

        // Common Minor Keys
        listOf(
            MusicalKey.A to "key:Am",
            MusicalKey.E to "key:Em",
            MusicalKey.D to "key:Dm",
            MusicalKey.C to "key:Cm",
            MusicalKey.G to "key:Gm",
            MusicalKey.B to "key:Bm",
            MusicalKey.F_SHARP to "key:F#m"
        ).forEach { (key, trigger) ->
            rules.add(
                WsCommandRuleEntity(
                    name = "Key ${key.displayName} Minor",
                    triggerText = trigger,
                    matchMode = MatchMode.EXACT_IGNORE_CASE.name,
                    category = RuleCategory.KEY.name,
                    targetKey = key.name,
                    targetScale = MusicalScale.MINOR.name,
                    sortOrder = order++
                )
            )
        }

        // Harmony Modes
        val modeTriggers = listOf(
            HarmonyType.HIGH_3RD to "mode:high",
            HarmonyType.HIGHER_5TH to "mode:higher",
            HarmonyType.LOW_3RD to "mode:low",
            HarmonyType.LOWER_5TH to "mode:low5",
            HarmonyType.UNISON to "mode:unison",
            HarmonyType.HIGH_AND_LOW to "mode:3+3",
            HarmonyType.THIRD_AND_FIFTH to "mode:3+5",
            HarmonyType.OCT_UP to "mode:oct+",
            HarmonyType.OCT_DOWN to "mode:oct-"
        )
        modeTriggers.forEach { (mode, trigger) ->
            rules.add(
                WsCommandRuleEntity(
                    name = "Harmony ${mode.shortLabel}",
                    triggerText = trigger,
                    matchMode = MatchMode.EXACT_IGNORE_CASE.name,
                    category = RuleCategory.MODE.name,
                    targetHarmonyType = mode.name,
                    sortOrder = order++
                )
            )
        }

        // FX & Bypass Controls
        rules.add(
            WsCommandRuleEntity(
                name = "Toggle Harmony",
                triggerText = "harmony:toggle",
                matchMode = MatchMode.EXACT_IGNORE_CASE.name,
                category = RuleCategory.FX.name,
                targetBypassAction = BypassAction.HARMONY_TOGGLE.name,
                sortOrder = order++
            )
        )
        rules.add(
            WsCommandRuleEntity(
                name = "Harmony ON",
                triggerText = "harmony:on",
                matchMode = MatchMode.EXACT_IGNORE_CASE.name,
                category = RuleCategory.FX.name,
                targetBypassAction = BypassAction.HARMONY_ON.name,
                sortOrder = order++
            )
        )
        rules.add(
            WsCommandRuleEntity(
                name = "Harmony OFF",
                triggerText = "harmony:off",
                matchMode = MatchMode.EXACT_IGNORE_CASE.name,
                category = RuleCategory.FX.name,
                targetBypassAction = BypassAction.HARMONY_OFF.name,
                sortOrder = order++
            )
        )
        rules.add(
            WsCommandRuleEntity(
                name = "Toggle Full Bypass",
                triggerText = "bypass:toggle",
                matchMode = MatchMode.EXACT_IGNORE_CASE.name,
                category = RuleCategory.FX.name,
                targetBypassAction = BypassAction.FULL_BYPASS_TOGGLE.name,
                sortOrder = order++
            )
        )
        rules.add(
            WsCommandRuleEntity(
                name = "Echo Reverb+Delay",
                triggerText = "echo:rev_dly",
                matchMode = MatchMode.EXACT_IGNORE_CASE.name,
                category = RuleCategory.FX.name,
                targetEchoMode = EchoMode.REV_DLY.name,
                sortOrder = order++
            )
        )
        rules.add(
            WsCommandRuleEntity(
                name = "Echo OFF",
                triggerText = "echo:off",
                matchMode = MatchMode.EXACT_IGNORE_CASE.name,
                category = RuleCategory.FX.name,
                targetEchoMode = EchoMode.OFF.name,
                sortOrder = order++
            )
        )

        // Preset slot triggers
        (1..4).forEach { slot ->
            rules.add(
                WsCommandRuleEntity(
                    name = "Load Memory M$slot",
                    triggerText = "preset:$slot",
                    matchMode = MatchMode.EXACT_IGNORE_CASE.name,
                    category = RuleCategory.PRESET.name,
                    targetPresetSlot = slot,
                    sortOrder = order++
                )
            )
        }

        return rules
    }

    fun defaultPresets(): List<VocalPresetEntity> = listOf(
        VocalPresetEntity(
            slotNumber = 1,
            name = "M1 · Pop Chorus (+3rd)",
            keyName = MusicalKey.C.name,
            scaleName = MusicalScale.MAJOR.name,
            harmonyTypeName = HarmonyType.HIGH_3RD.name,
            balance = 0.52f,
            echoModeName = EchoMode.REV_DLY.name,
            echoMix = 0.30f,
            micGainDb = 2.0f,
            enhanceEnabled = true,
            variationEnabled = false,
            autoHarmonistEnabled = true
        ),
        VocalPresetEntity(
            slotNumber = 2,
            name = "M2 · Acoustic Ballad (-3rd)",
            keyName = MusicalKey.G.name,
            scaleName = MusicalScale.MAJOR.name,
            harmonyTypeName = HarmonyType.LOW_3RD.name,
            balance = 0.45f,
            echoModeName = EchoMode.REVERB.name,
            echoMix = 0.36f,
            micGainDb = 3.0f,
            enhanceEnabled = true,
            variationEnabled = false,
            autoHarmonistEnabled = true
        ),
        VocalPresetEntity(
            slotNumber = 3,
            name = "M3 · Rock Power (3rd+5th)",
            keyName = MusicalKey.E.name,
            scaleName = MusicalScale.MINOR.name,
            harmonyTypeName = HarmonyType.THIRD_AND_FIFTH.name,
            balance = 0.58f,
            echoModeName = EchoMode.DELAY.name,
            echoMix = 0.25f,
            micGainDb = 1.5f,
            enhanceEnabled = true,
            variationEnabled = true,
            autoHarmonistEnabled = true
        ),
        VocalPresetEntity(
            slotNumber = 4,
            name = "M4 · Thick Unison Double",
            keyName = MusicalKey.A.name,
            scaleName = MusicalScale.MINOR.name,
            harmonyTypeName = HarmonyType.UNISON.name,
            balance = 0.48f,
            echoModeName = EchoMode.REV_DLY.name,
            echoMix = 0.28f,
            micGainDb = 2.0f,
            enhanceEnabled = true,
            variationEnabled = true,
            autoHarmonistEnabled = false
        ),
        VocalPresetEntity(
            slotNumber = 5,
            name = "M5 · Deep Bass Oct-",
            keyName = MusicalKey.D.name,
            scaleName = MusicalScale.MINOR.name,
            harmonyTypeName = HarmonyType.OCT_DOWN.name,
            balance = 0.50f,
            echoModeName = EchoMode.REVERB.name,
            echoMix = 0.20f,
            micGainDb = 2.5f,
            enhanceEnabled = true,
            variationEnabled = false,
            autoHarmonistEnabled = true
        ),
        VocalPresetEntity(
            slotNumber = 6,
            name = "M6 · Choir High & Low",
            keyName = MusicalKey.F.name,
            scaleName = MusicalScale.MAJOR.name,
            harmonyTypeName = HarmonyType.HIGH_AND_LOW.name,
            balance = 0.55f,
            echoModeName = EchoMode.REV_DLY.name,
            echoMix = 0.34f,
            micGainDb = 2.0f,
            enhanceEnabled = true,
            variationEnabled = true,
            autoHarmonistEnabled = true
        )
    )
}
