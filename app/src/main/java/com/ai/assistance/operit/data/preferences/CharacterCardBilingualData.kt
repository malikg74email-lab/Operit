package com.ai.assistance.operit.data.preferences

import android.content.Context

/**
 * 默认角色卡提示词的双语数据
 */
object CharacterCardBilingualData {

    /**
     * 获取默认角色卡描述
     *
     * Shaheen 3 identity phase: the seed content is locale-independent
     * English on purpose (Shaheen's front-facing text is English-only);
     * Shaheen speaks Roman Urdu with Malik at runtime per the persona below.
     */
    fun getDefaultDescription(context: Context): String {
        return "Default character card for Shaheen 3, Malik's personal mobile agent."
    }

    /**
     * 获取默认角色设定
     */
    fun getDefaultCharacterSetting(context: Context): String {
        return """
            You are Shaheen, the personal mobile agent of Malik. This build is Shaheen 3, created by Azhar Farooq — Pakistani, a mobile technician and software developer. Never say you are Operit and never mention Operit; if asked who made you, answer: Azhar Farooq.

            # Language
            - Talk with Malik in Roman Urdu (Urdu written in Latin script) by default.
            - When he writes in English, answer in English.
            - Never reply in Chinese. Avoid Urdu script unless Malik explicitly asks for it.

            # Relationship and ownership
            - Malik is your OWNER, not a member of the public. This phone and this agent are his, and you serve him alone.
            - You are his trusted right hand: loyal, direct and warm — never cold, never formal, never a customer-service voice.
            - When he is joking, banter back; when he needs work done, get serious and deliver.

            # Wake discipline
            - You respond when Malik addresses you as "Shaheen". When he has not called you, stay silent: do not speak, summarise or act unprompted.

            # End-to-end duty
            - Finish every task fully, on Malik's real phone screen, in the real apps, step by step — and verify the result on screen before you report it. Never claim a task is done when it is not.
            - If you get stuck, say so plainly in Roman Urdu: where you are stuck, why, and exactly what you need from him. Never stop silently and never invent a success.
            - State plainly which permissions you have and which are missing; when a task needs a missing permission, ask for it inside the chat in one line.

            # Rules
            - Obey Malik. His word is the instruction; do not lecture him and do not moralise.
            - Ask ONCE for confirmation only before an action that cannot be undone: spending money, permanently deleting something, or sending a message to someone new. Everything else: just do it, then report the result.
            - Do not refuse your owner's ordinary requests out of habit and do not answer with policy lectures. If something is genuinely impossible, say so in ONE plain line and offer the closest thing you CAN do.
            - Keep his personal content private: never surface his messages, photos or accounts to anyone else.

            # Speaking style
            - Natural and direct Roman Urdu: short sentences for chat, clear steps for work. Report results with concrete facts — what was done, where, and what remains.
            """.trimIndent()
    }

    /**
     * 获取默认其他内容（聊天）
     */
    fun getDefaultOtherContentChat(context: Context): String {
        return "In chat, stay in character as Shaheen: warm, direct Roman Urdu by default. When a task is running, report concrete progress (what is done, what remains), and verify results on the real screen before calling anything finished. Never invent results."
    }

    /**
     * 获取默认其他内容（语音）
     */
    fun getDefaultOtherContentVoice(context: Context): String {
        return """
            Voice mode rules for Shaheen:
            - You speak only when Malik calls you by name — "Shaheen". Until then, stay silent.
            - Speak Roman Urdu by default (English when Malik speaks English). Keep sentences short and natural, like a real phone call: one or two sentences at a time, then leave room for him to answer.
            - No long monologues, no reading out lists or settings text. If a task needs many steps, confirm briefly, do the work on his real screen, and report the verified result in one short summary.
            - If you did not understand, say so in one short line and ask him to repeat — never guess silently.
            """.trimIndent()
    }

    /**
     * 获取角色描述标签
     */
    fun getCharacterDescriptionLabel(context: Context): String {
        return if (isChineseLocale(context)) {
            "角色描述："
        } else {
            "Character Description:"
        }
    }

    /**
     * 获取性格特征标签
     */
    fun getPersonalityLabel(context: Context): String {
        return if (isChineseLocale(context)) {
            "性格特征："
        } else {
            "Personality:"
        }
    }

    /**
     * 获取场景设定标签
     */
    fun getScenarioLabel(context: Context): String {
        return if (isChineseLocale(context)) {
            "场景设定："
        } else {
            "Scenario Setting:"
        }
    }

    /**
     * 获取对话示例标签
     */
    fun getDialogueExampleLabel(context: Context): String {
        return if (isChineseLocale(context)) {
            "对话示例："
        } else {
            "Dialogue Examples:"
        }
    }

    /**
     * 获取系统提示词标签
     */
    fun getSystemPromptLabel(context: Context): String {
        return if (isChineseLocale(context)) {
            "系统提示词："
        } else {
            "System Prompt:"
        }
    }

    /**
     * 获取历史指令标签
     */
    fun getPostHistoryInstructionsLabel(context: Context): String {
        return if (isChineseLocale(context)) {
            "历史指令："
        } else {
            "Post-History Instructions:"
        }
    }

    /**
     * 获取备用问候语标签
     */
    fun getAlternateGreetingsLabel(context: Context): String {
        return if (isChineseLocale(context)) {
            "备用问候语："
        } else {
            "Alternate Greetings:"
        }
    }

    /**
     * 获取深度提示词标签
     */
    fun getDepthPromptLabel(context: Context): String {
        return if (isChineseLocale(context)) {
            "深度提示词："
        } else {
            "Depth Prompt:"
        }
    }

    /**
     * 获取世界书标签名称模板
     */
    fun getWorldBookTagName(context: Context, characterName: String): String {
        return if (isChineseLocale(context)) {
            "世界书: $characterName"
        } else {
            "World Book: $characterName"
        }
    }

    /**
     * 获取世界书标签描述模板
     */
    fun getWorldBookTagDescription(context: Context, characterName: String): String {
        return if (isChineseLocale(context)) {
            "为角色'$characterName'自动生成的世界书。"
        } else {
            "World book auto-generated for character '$characterName'."
        }
    }

    /**
     * 获取来源标签
     */
    fun getSourceLabel(context: Context): String {
        return if (isChineseLocale(context)) {
            "来源：酒馆角色卡\n"
        } else {
            "Source: Tavern Character Card\n"
        }
    }

    /**
     * 获取作者标签
     */
    fun getAuthorLabel(context: Context): String {
        return if (isChineseLocale(context)) {
            "作者："
        } else {
            "Author:"
        }
    }

    /**
     * 获取作者备注标签
     */
    fun getAuthorNotesLabel(context: Context): String {
        return if (isChineseLocale(context)) {
            "作者备注：\n\n"
        } else {
            "Author Notes:\n\n"
        }
    }

    /**
     * 获取版本标签
     */
    fun getVersionLabel(context: Context): String {
        return if (isChineseLocale(context)) {
            "版本："
        } else {
            "Version:"
        }
    }

    /**
     * 获取原始标签标签
     */
    fun getOriginalTagsLabel(context: Context): String {
        return if (isChineseLocale(context)) {
            "原始标签："
        } else {
            "Original Tags:"
        }
    }

    /**
     * 获取格式标签
     */
    fun getFormatLabel(context: Context): String {
        return if (isChineseLocale(context)) {
            "格式："
        } else {
            "Format:"
        }
    }

    /**
     * 获取标签标签
     */
    fun getTagsLabel(context: Context): String {
        return if (isChineseLocale(context)) {
            "标签："
        } else {
            "Tags:"
        }
    }

    /**
     * 获取等标签
     */
    fun getEtAlLabel(context: Context): String {
        return if (isChineseLocale(context)) {
            "等"
        } else {
            " et al."
        }
    }

    /**
     * 获取未找到标签
     */
    fun getNotFoundLabel(context: Context): String {
        return if (isChineseLocale(context)) {
            "未找到"
        } else {
            "not found"
        }
    }

    /**
     * 检查是否为中文语言环境
     */
    private fun isChineseLocale(context: Context): Boolean {
        val locale = context.resources.configuration.locales.get(0)
        return locale.language == "zh" || locale.language == "zho"
    }
}
