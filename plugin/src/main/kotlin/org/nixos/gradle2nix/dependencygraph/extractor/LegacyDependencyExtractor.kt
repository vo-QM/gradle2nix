package org.nixos.gradle2nix.dependencygraph.extractor

open class LegacyDependencyExtractor(
    private val rendererClassName: String
) : DependencyExtractor() {

    override fun getRendererClassName(): String {
        return rendererClassName
    }
}
