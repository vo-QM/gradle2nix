package org.nixos.gradle2nix.env

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import org.nixos.gradle2nix.model.Version

typealias Env = Map<ModuleId, Module>

@Serializable
@JvmInline
value class Module(
    val versions: Map<Version, ArtifactSet>,
)

@Serializable
@JvmInline
value class ArtifactSet(
    val files: Map<String, ArtifactFile>
)

@Serializable
data class ArtifactFile internal constructor(
    val urls: List<String>,
    val hash: String,
) {

    companion object {
        operator fun invoke(urls: List<String>, hash: String) = ArtifactFile(urls.sorted(), hash)
    }
}

@Serializable(ModuleId.Serializer::class)
data class ModuleId(
    val group: String,
    val name: String,
) : Comparable<ModuleId> {

    override fun compareTo(other: ModuleId): Int =
        compareValuesBy(this, other, ModuleId::group, ModuleId::name)

    override fun toString(): String = "$group:$name"

    companion object Serializer : KSerializer<ModuleId> {
        override val descriptor: SerialDescriptor get() = PrimitiveSerialDescriptor(
            ModuleId::class.qualifiedName!!,
            PrimitiveKind.STRING
        )

        override fun serialize(encoder: Encoder, value: ModuleId) {
            encoder.encodeString(value.toString())
        }

        override fun deserialize(decoder: Decoder): ModuleId {
            val encoded = decoder.decodeString()
            val parts = encoded.split(":")
            if (parts.size != 2 || parts.any(String::isBlank)) {
                throw SerializationException("invalid module id: $encoded")
            }
            return ModuleId(parts[0], parts[1])
        }
    }
}
