package org.nixos.gradle2nix.dependencygraph

import java.util.concurrent.ConcurrentHashMap
import org.nixos.gradle2nix.model.DependencyCoordinates
import org.nixos.gradle2nix.model.Repository
import org.nixos.gradle2nix.model.impl.DefaultDependencyCoordinates


private val partRegex = Regex("\\[(?<attr>[^]]+)]|\\((?<optional>([^)]+))\\)")

private fun StringBuilder.appendPattern(
    input: String,
    seen: MutableList<String>,
) {
    var literalStart = 0
    partRegex.findAll(input).forEach { match ->
        val literal = input.substring(literalStart, match.range.first)
        if (literal.isNotEmpty()) {
            append(Regex.escape(literal))
        }
        literalStart = match.range.last + 1

        val optionalValue = match.groups["optional"]?.value
        val attrValue = match.groups["attr"]?.value
        if (optionalValue != null) {
            append("(")
            appendPattern(optionalValue, seen)
            append(")?")
        } else if (attrValue != null) {
            if (attrValue !in seen) {
                seen.add(attrValue)
                append("(?<$attrValue>[^/]+)")
            } else {
                append("\\k<$attrValue>")
            }
        }
    }
    val tail = input.substring(literalStart)
    if (tail.isNotEmpty()) {
        append(Regex.escape(input.substring(literalStart)))
    }
}

private fun String.replaceAttrs(
    attrs: Map<String, String>
): String {
    return partRegex.replace(this) { match ->
        val optionalValue = match.groups["optional"]?.value
        val attrValue = match.groups["attr"]?.value
        if (optionalValue != null) {
            val replaced = optionalValue.replaceAttrs(attrs)
            if (replaced != optionalValue) replaced else match.value
        } else if (attrValue != null) {
            attrs[attrValue] ?: match.value
        } else {
            match.value
        }
    }
}


private fun interface ArtifactMatcher {
    fun match(url: String): Map<String, String>?
}

private fun regexMatcher(regex: Regex, attrs: List<String>): ArtifactMatcher {
    return ArtifactMatcher { url ->
        regex.matchEntire(url)?.groups?.let { groups ->
            buildMap {
                for (attr in attrs) {
                    groups[attr]?.let { put(attr, it.value) }
                }
            }
        }
    }
}

private fun patternMatcher(pattern: String): ArtifactMatcher {
    val attrs = mutableListOf<String>()
    val exp = buildString { appendPattern(pattern, attrs) }.toRegex()
    return regexMatcher(exp, attrs)
}

private fun mavenMatcher(pattern: String): ArtifactMatcher {
    val attrs = mutableListOf<String>()
    val exp = buildString { appendPattern(pattern.replaceAfterLast("/", ""), attrs) }
        .replace("<organisation>[^/]+", "<organisation>.+")
        .plus("[^/]+")
        .toRegex()
    return regexMatcher(exp, attrs)
}

private val matcherCache: MutableMap<String, ArtifactMatcher> = ConcurrentHashMap()

private fun matcher(
    pattern: String,
): ArtifactMatcher = matcherCache.getOrPut(pattern) {
    if (pattern.endsWith(DependencyExtractor.M2_PATTERN)) mavenMatcher(pattern) else patternMatcher(pattern)
}

fun parseComponent(
    repositories: List<Repository>,
    url: String,
): Pair<DependencyCoordinates, String>? {
    for (repository in repositories) {
        for (pattern in (repository.metadataResources + repository.artifactResources).distinct()) {
            val matcher = matcher(pattern)
            val attrs = matcher.match(url)
            if (attrs != null) {
                val group = attrs["organisation"]?.replace('/', '.') ?: continue
                val artifact = attrs["module"] ?: continue
                val revision = attrs["revision"] ?: continue
                return DefaultDependencyCoordinates(group, artifact, revision) to pattern.replaceAttrs(attrs)
            }
        }
    }
    return null
}

fun parseArtifact(
    resource: String,
    component: DependencyCoordinates,
    url: String
): String {
    val attrs = mutableListOf<String>()
    var pattern = buildString { appendPattern(resource, attrs) }
    if (component.version.endsWith("-SNAPSHOT")) {
        val base = component.version.substringBeforeLast("-SNAPSHOT", "")
        pattern = pattern.replace("\\Q-${component.version}\\E", "\\Q-$base-\\E(?:.+)")
    }

    val values = regexMatcher(pattern.toRegex(), attrs).match(url)
    val artifact = values?.get("artifact")
    val classifier = values?.get("classifier")
    val ext = values?.get("ext")

    if (artifact == null) return artifactFromFilename(
        url.substringAfterLast('/').substringBefore('#').substringBefore('?'),
        component.version,
        classifier
    )

    return buildString {
        append("$artifact-${component.version}")
        if (classifier != null) append("-$classifier")
        if (ext != null) append(".$ext")
    }
}

private fun artifactFromFilename(filename: String, version: String, classifier: String?): String {
    val name = filename.substringBeforeLast('.')
    val extension = filename.substringAfterLast('.', "")
    return buildString {
        append("$name-$version")
        if (classifier != null) append("-$classifier")
        if (extension.isNotEmpty()) append(".$extension")
    }
}
