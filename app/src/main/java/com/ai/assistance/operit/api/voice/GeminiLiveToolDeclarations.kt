package com.ai.assistance.operit.api.voice

import com.ai.assistance.operit.data.model.ToolParameterSchema
import com.ai.assistance.operit.data.model.ToolPrompt
import org.json.JSONArray
import org.json.JSONObject

/**
 * Converts the chat path's tool definitions into Gemini Live `functionDeclarations`.
 *
 * Intent (why this duplicates code): the identical conversion already exists as
 * GeminiProvider.buildToolDefinitionsForGemini + buildSchemaFromStructured, operating on
 * the same List<ToolPrompt> model — but both are `private` to the text provider, so the
 * Live provider cannot call them. This object performs the exact same conversion over the
 * exact same model classes so a Live session exposes precisely the tools the chat path
 * exposes. If GeminiProvider's builder is ever made internal/public, delete this object
 * and call it instead.
 */
object GeminiLiveToolDeclarations {

    /** Mirrors GeminiProvider.buildToolDefinitionsForGemini (name, description+details, schema). */
    fun buildFunctionDeclarations(toolPrompts: List<ToolPrompt>): JSONArray {
        val functionDeclarations = JSONArray()

        for (tool in toolPrompts) {
            functionDeclarations.put(JSONObject().apply {
                put("name", tool.name)
                // Combine description and details into the full description, as the chat provider does
                val fullDescription = if (tool.details.isNotEmpty()) {
                    "${tool.description}\n${tool.details}"
                } else {
                    tool.description
                }
                put("description", fullDescription)

                val parametersSchema = buildSchemaFromStructured(tool.parametersStructured ?: emptyList())
                put("parameters", parametersSchema)
            })
        }

        return functionDeclarations
    }

    /** Mirrors GeminiProvider.buildSchemaFromStructured (Gemini-flavoured JSON Schema). */
    private fun buildSchemaFromStructured(params: List<ToolParameterSchema>): JSONObject {
        val schema = JSONObject().apply {
            put("type", "object")
        }

        val properties = JSONObject()
        val required = JSONArray()

        for (param in params) {
            properties.put(param.name, JSONObject().apply {
                put("type", param.type)
                put("description", param.description)
                if (param.default != null) {
                    put("default", param.default)
                }
            })

            if (param.required) {
                required.put(param.name)
            }
        }

        schema.put("properties", properties)
        if (required.length() > 0) {
            schema.put("required", required)
        }

        return schema
    }
}
