package com.superstudent.core.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonPrimitive

/**
 * Accepts a JSON string, number or boolean and yields its literal text.
 * Model output for numeric answer fields drifts between `"2"` and `2`; rejecting either
 * would fail an otherwise correct artifact.
 */
object LooseTextSerializer : KSerializer<String> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("com.superstudent.core.model.LooseText", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: String) = encoder.encodeString(value)

    override fun deserialize(decoder: Decoder): String {
        val primitive = (decoder as? JsonDecoder)?.decodeJsonElement() as? JsonPrimitive
            ?: return decoder.decodeString()
        return primitive.content
    }
}
