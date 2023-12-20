package org.nixos.gradle2nix.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

@Serializable(DependencyCoordinates.Serializer::class)
data class DependencyCoordinates(
    val group: String,
    val module: String,
    val version: String,
    val timestamp: String? = null
) : Comparable<DependencyCoordinates> {

    override fun toString(): String = if (timestamp != null) {
        "$group:$module:$version:$timestamp"
    } else {
        "$group:$module:$version"
    }

    val artifactVersion: String get() =
        timestamp?.let { version.replace("SNAPSHOT", it) } ?: version

    override fun compareTo(other: DependencyCoordinates): Int = comparator.compare(this, other)

    object Serializer : KSerializer<DependencyCoordinates> {
        override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor(
            DependencyCoordinates::class.qualifiedName!!,
            PrimitiveKind.STRING
        )

        override fun deserialize(decoder: Decoder): DependencyCoordinates {
            val encoded = decoder.decodeString()
            return parse(encoded)
        }

        override fun serialize(encoder: Encoder, value: DependencyCoordinates) {
            encoder.encodeString(value.toString())
        }
    }

    companion object {
        val comparator = compareBy<DependencyCoordinates> { it.group }
            .thenBy { it.module }
            .thenByDescending { it.artifactVersion }

        fun parse(id: String): DependencyCoordinates {
            val parts = id.split(":")
            return when (parts.size) {
                3 -> DependencyCoordinates(parts[0], parts[1], parts[2])
                4 -> DependencyCoordinates(parts[0], parts[1], parts[2], parts[3])
                else -> throw IllegalStateException(
                    "couldn't parse dependency coordinates: '$id'"
                )
            }
        }
    }
}
