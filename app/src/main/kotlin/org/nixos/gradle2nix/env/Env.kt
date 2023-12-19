package org.nixos.gradle2nix.env

import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import org.gradle.internal.impldep.com.google.common.collect.ImmutableMap
import org.gradle.internal.impldep.com.google.common.primitives.Longs

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

@Serializable(ModuleVersionId.Serializer::class)
data class ModuleVersionId(
    val moduleId: ModuleId,
    val version: Version
) : Comparable<ModuleVersionId> {
    constructor(group: String, name: String, version: Version) : this(ModuleId(group, name), version)

    val group: String get() = moduleId.group
    val name: String get() = moduleId.name

    override fun compareTo(other: ModuleVersionId): Int =
        compareValuesBy(
            this,
            other,
            ModuleVersionId::moduleId,
            ModuleVersionId::version
        )


    override fun toString(): String = "$group:$name:$version"

    internal object Serializer : KSerializer<ModuleVersionId> {
        override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor(
            Version::class.qualifiedName!!,
            PrimitiveKind.STRING
        )

        override fun serialize(encoder: Encoder, value: ModuleVersionId) {
            encoder.encodeString(value.toString())
        }

        override fun deserialize(decoder: Decoder): ModuleVersionId {
            val encoded = decoder.decodeString()
            val parts = encoded.split(":")
            if (parts.size != 3 || parts.any(String::isBlank)) {
                throw SerializationException("invalid module version id: $encoded")
            }
            return ModuleVersionId(
                moduleId = ModuleId(parts[0], parts[1]),
                version = Version(parts[3])
            )
        }
    }
}

@Serializable(Version.Serializer::class)
class Version(val source: String, val parts: List<String>, base: Version?) : Comparable<Version> {

    private val base: Version
    val numericParts: List<Long?>

    init {
        this.base = base ?: this
        this.numericParts = parts.map(Longs::tryParse)
    }

    override fun compareTo(other: Version): Int = compare(this, other)

    override fun toString(): String = source

    override fun equals(other: Any?): Boolean = when {
        other === this -> true
        other == null || other !is Version -> false
        else -> source == other.source
    }

    override fun hashCode(): Int = source.hashCode()

    object Comparator : kotlin.Comparator<Version> {
        override fun compare(o1: Version, o2: Version): Int =
            Version.compare(o1, o2)
    }

    internal object Serializer : KSerializer<Version> {
        override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor(
            Version::class.qualifiedName!!,
            PrimitiveKind.STRING
        )

        override fun serialize(encoder: Encoder, value: Version) {
            encoder.encodeString(value.source)
        }

        override fun deserialize(decoder: Decoder): Version {
            return Version(decoder.decodeString())
        }
    }

    companion object {
        private val SPECIAL_MEANINGS: Map<String, Int> = ImmutableMap.builderWithExpectedSize<String, Int>(7)
            .put("dev", -1)
            .put("rc", 1)
            .put("snapshot", 2)
            .put("final", 3).put("ga", 4).put("release", 5)
            .put("sp", 6).build()

        private val cache = ConcurrentHashMap<String, Version>()

        // From org.gradle.api.internal.artifacts.ivyservice.ivyresolve.strategy.VersionParser
        operator fun invoke(original: String): Version = cache.getOrPut(original) {
            val parts = mutableListOf<String>()
            var digit = false
            var startPart = 0
            var pos = 0
            var endBase = 0
            var endBaseStr = 0
            while (pos < original.length) {
                val ch = original[pos]
                if (ch == '.' || ch == '_' || ch == '-' || ch == '+') {
                    parts.add(original.substring(startPart, pos))
                    startPart = pos + 1
                    digit = false
                    if (ch != '.' && endBaseStr == 0) {
                        endBase = parts.size
                        endBaseStr = pos
                    }
                } else if (ch in '0'..'9') {
                    if (!digit && pos > startPart) {
                        if (endBaseStr == 0) {
                            endBase = parts.size + 1
                            endBaseStr = pos
                        }
                        parts.add(original.substring(startPart, pos))
                        startPart = pos
                    }
                    digit = true
                } else {
                    if (digit) {
                        if (endBaseStr == 0) {
                            endBase = parts.size + 1
                            endBaseStr = pos
                        }
                        parts.add(original.substring(startPart, pos))
                        startPart = pos
                    }
                    digit = false
                }
                pos++
            }
            if (pos > startPart) {
                parts.add(original.substring(startPart, pos))
            }
            var base: Version? = null
            if (endBaseStr > 0) {
                base = Version(original.substring(0, endBaseStr), parts.subList(0, endBase), null)
            }
            Version(original, parts, base)
        }

        // From org.gradle.api.internal.artifacts.ivyservice.ivyresolve.strategy.StaticVersionComparator
        private fun compare(version1: Version, version2: Version): Int {
            if (version1 == version2) {
                return 0
            }

            val parts1 = version1.parts
            val parts2 = version2.parts
            val numericParts1 = version1.numericParts
            val numericParts2 = version2.numericParts
            var lastIndex = -1

            for (i in 0..<(minOf(parts1.size, parts2.size))) {
                lastIndex = i

                val part1 = parts1[i]
                val part2 = parts2[i]

                val numericPart1 = numericParts1[i]
                val numericPart2 = numericParts2[i]

                when {
                    part1 == part2 -> continue
                    numericPart1 != null && numericPart2 == null -> return 1
                    numericPart2 != null && numericPart1 == null -> return -1
                    numericPart1 != null && numericPart2 != null -> {
                        val result = numericPart1.compareTo(numericPart2)
                        if (result == 0) continue
                        return result
                    }
                    else -> {
                        // both are strings, we compare them taking into account special meaning
                        val sm1 = SPECIAL_MEANINGS[part1.lowercase()]
                        val sm2 = SPECIAL_MEANINGS[part2.lowercase()]
                        if (sm1 != null) return sm1 - (sm2 ?: 0)
                        if (sm2 != null) return -sm2
                        return part1.compareTo(part2)
                    }
                }
            }
            if (lastIndex < parts1.size) {
                return if (numericParts1[lastIndex] == null) -1 else 1
            }
            if (lastIndex < parts2.size) {
                return if (numericParts2[lastIndex] == null) 1 else -1
            }

            return 0
        }
    }
}
